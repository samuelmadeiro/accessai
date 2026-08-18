package dev.accessai.analise.extracao;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.stream.XMLStreamReader;

/**
 * Coleta os paragrafos que sao titulo, na ordem do corpo.
 *
 * <p>So {@code word/document.xml}: cabecalho de pagina e nota de rodape nao
 * entram no sumario do documento, e mistura-los produziria salto de nivel
 * inventado.
 *
 * <p>O nivel vem de duas fontes, nesta ordem:
 *
 * <ol>
 *   <li>{@code w:outlineLvl} (0 a 8) — independente de idioma e de nome de
 *       estilo, e o que o Word usa para montar o sumario;</li>
 *   <li>o identificador do estilo, quando nao ha outlineLvl. Aqui o nome varia
 *       por exportador e por idioma: {@code Heading1}, {@code Titulo1},
 *       {@code Ttulo1} (Word pt-BR grava sem acento). Por isso a comparacao
 *       normaliza o texto antes.</li>
 * </ol>
 *
 * <p>{@code w:outlineLvl w:val="9"} significa "corpo de texto" e nao e titulo.
 */
final class ColetorDeCabecalhos implements ColetorDeParte {

    private static final Pattern ESTILO_DE_TITULO =
            Pattern.compile("^(?:heading|titulo|ttulo)([1-9])$");

    private final List<CabecalhoDoDocumento> cabecalhos = new ArrayList<>();
    private final Deque<Paragrafo> abertos = new ArrayDeque<>();
    private String parteAtual;

    @Override
    public boolean aceita(String parte) {
        return Ooxml.PARTE_DOCUMENTO.equals(parte);
    }

    @Override
    public void aoIniciarParte(String parte) {
        this.parteAtual = parte;
        abertos.clear();
    }

    @Override
    public void aoAbrirElemento(XMLStreamReader r, int profundidade) {
        if (ehElemento(r, "p")) {
            abertos.push(new Paragrafo(profundidade));
            return;
        }
        Paragrafo atual = abertos.peek();
        if (atual == null) {
            return;
        }
        if (ehElemento(r, "outlineLvl")) {
            nivelDeOutline(r.getAttributeValue(Ooxml.NS_W, "val"))
                    .ifPresent(atual::definirNivelForte);
        } else if (ehElemento(r, "pStyle")) {
            nivelDeEstilo(r.getAttributeValue(Ooxml.NS_W, "val"))
                    .ifPresent(atual::definirNivelFraco);
        }
    }

    @Override
    public void aoTexto(String texto) {
        Paragrafo atual = abertos.peek();
        if (atual != null) {
            atual.texto.append(texto);
        }
    }

    @Override
    public void aoFecharElemento(int profundidade) {
        Paragrafo atual = abertos.peek();
        if (atual == null || atual.profundidade != profundidade) {
            return;
        }
        abertos.pop();
        if (atual.nivel > 0) {
            cabecalhos.add(new CabecalhoDoDocumento(parteAtual, atual.nivel,
                    atual.texto.toString()));
        }
    }

    List<CabecalhoDoDocumento> cabecalhos() {
        return List.copyOf(cabecalhos);
    }

    private static java.util.Optional<Integer> nivelDeOutline(String val) {
        if (val == null) {
            return java.util.Optional.empty();
        }
        try {
            int nivel = Integer.parseInt(val.trim());
            return nivel >= 0 && nivel <= 8
                    ? java.util.Optional.of(nivel + 1)
                    : java.util.Optional.empty();
        } catch (NumberFormatException e) {
            // Valor fora do schema: o documento e do usuario, nao da para confiar.
            return java.util.Optional.empty();
        }
    }

    private static java.util.Optional<Integer> nivelDeEstilo(String styleId) {
        if (styleId == null) {
            return java.util.Optional.empty();
        }
        String normalizado = styleId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        Matcher m = ESTILO_DE_TITULO.matcher(normalizado);
        return m.matches()
                ? java.util.Optional.of(Integer.parseInt(m.group(1)))
                : java.util.Optional.empty();
    }

    private static boolean ehElemento(XMLStreamReader r, String local) {
        return Ooxml.NS_W.equals(r.getNamespaceURI()) && local.equals(r.getLocalName());
    }

    private static final class Paragrafo {

        private final int profundidade;
        private final StringBuilder texto = new StringBuilder();
        private int nivel;
        private boolean nivelVeioDoOutline;

        private Paragrafo(int profundidade) {
            this.profundidade = profundidade;
        }

        void definirNivelForte(int nivel) {
            this.nivel = nivel;
            this.nivelVeioDoOutline = true;
        }

        void definirNivelFraco(int nivel) {
            if (!nivelVeioDoOutline) {
                this.nivel = nivel;
            }
        }
    }
}
