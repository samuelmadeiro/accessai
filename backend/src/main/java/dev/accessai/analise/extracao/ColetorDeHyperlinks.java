package dev.accessai.analise.extracao;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import javax.xml.stream.XMLStreamReader;

/**
 * Coleta hyperlinks e o texto visivel de cada um.
 *
 * <p>O destino nao esta aqui: {@code w:hyperlink} guarda um {@code r:id} que so
 * o arquivo de relacionamentos resolve. Por isso o coletor devolve o id cru e a
 * juncao acontece no {@link ExtratorDeDocumento}, depois que o pacote inteiro
 * foi lido — a ordem das entradas no zip nao e garantida.
 *
 * <p>Limite conhecido: link escrito como campo {@code HYPERLINK} (instrucao
 * {@code w:instrText}) nao e visto. E forma legada, ainda presente em documento
 * antigo; entra quando aparecer no corpus.
 */
final class ColetorDeHyperlinks implements ColetorDeParte {

    private final List<LinkBruto> links = new ArrayList<>();
    private final Deque<Link> abertos = new ArrayDeque<>();
    private String parteAtual;

    @Override
    public boolean aceita(String parte) {
        return Ooxml.ehParteComConteudo(parte);
    }

    @Override
    public void aoIniciarParte(String parte) {
        this.parteAtual = parte;
        abertos.clear();
    }

    @Override
    public void aoAbrirElemento(XMLStreamReader r, int profundidade) {
        if (Ooxml.NS_W.equals(r.getNamespaceURI()) && "hyperlink".equals(r.getLocalName())) {
            abertos.push(new Link(profundidade, r.getAttributeValue(Ooxml.NS_R, "id")));
        }
    }

    @Override
    public void aoTexto(String texto) {
        Link atual = abertos.peek();
        if (atual != null) {
            atual.texto.append(texto);
        }
    }

    @Override
    public void aoFecharElemento(int profundidade) {
        Link atual = abertos.peek();
        if (atual == null || atual.profundidade != profundidade) {
            return;
        }
        abertos.pop();
        links.add(new LinkBruto(parteAtual, atual.texto.toString(), atual.relacionamento));
    }

    List<LinkBruto> links() {
        return List.copyOf(links);
    }

    /** Link antes de o destino ser resolvido. */
    record LinkBruto(String partePacote, String texto, String relacionamento) {
    }

    private static final class Link {

        private final int profundidade;
        private final String relacionamento;
        private final StringBuilder texto = new StringBuilder();

        private Link(int profundidade, String relacionamento) {
            this.profundidade = profundidade;
            this.relacionamento = relacionamento;
        }
    }
}
