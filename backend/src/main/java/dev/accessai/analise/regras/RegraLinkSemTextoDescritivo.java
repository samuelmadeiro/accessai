package dev.accessai.analise.regras;

import dev.accessai.analise.dominio.Problema;
import dev.accessai.analise.extracao.DocumentoExtraido;
import dev.accessai.analise.extracao.HyperlinkDoDocumento;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Link cujo texto nao diz para onde ele vai — WCAG 2.4.4, nivel A.
 *
 * <p>Leitor de tela oferece a lista de links da pagina como forma de navegacao.
 * Numa lista, "clique aqui" cinco vezes nao distingue nada, e uma URL crua e
 * lida caractere a caractere.
 *
 * <p>Dois casos, os dois deterministicos:
 *
 * <ul>
 *   <li>texto na lista de expressoes genericas;</li>
 *   <li>texto que e a propria URL — igual ao destino, ou com cara de endereco.</li>
 * </ul>
 *
 * <p><b>Link sem texto nenhum nao entra aqui.</b> Ele costuma envolver uma
 * imagem, e nesse caso quem responde e a regra de texto alternativo (1.1.1).
 * Marcar os dois transformaria um defeito em dois problemas no relatorio.
 */
@Component
public class RegraLinkSemTextoDescritivo implements RegraDeAcessibilidade {

    private static final String ID = "LINK_SEM_TEXTO_DESCRITIVO";
    private static final String CRITERIO = "2.4.4";

    /**
     * Lista fechada e em minusculas, sem acento removido de proposito: o texto
     * do documento e comparado depois de normalizado. Heuristica de
     * "texto curto demais" ficou de fora — "Edital 3/2024" tem 13 caracteres e
     * descreve o destino perfeitamente.
     */
    private static final Set<String> EXPRESSOES_GENERICAS = Set.of(
            "clique aqui", "clique", "aqui", "link", "este link", "veja aqui",
            "saiba mais", "leia mais", "mais", "mais informacoes", "mais informacoes aqui",
            "acesse", "acesse aqui", "baixe aqui", "download", "ver", "veja",
            "click here", "here", "read more", "more", "learn more");

    private static final Set<String> ESQUEMAS_DE_URL = Set.of(
            "http://", "https://", "www.", "ftp://", "mailto:");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String criterioWcag() {
        return CRITERIO;
    }

    @Override
    public List<Achado> avaliar(DocumentoExtraido documento) {
        return documento.links().stream()
                .filter(HyperlinkDoDocumento::temTexto)
                .map(RegraLinkSemTextoDescritivo::avaliarLink)
                .flatMap(Optional::stream)
                .toList();
    }

    private static Optional<Achado> avaliarLink(HyperlinkDoDocumento link) {
        String texto = link.textoNormalizado();
        String normalizado = normalizar(texto);

        if (EXPRESSOES_GENERICAS.contains(normalizado)) {
            return Optional.of(achado(link, "texto de link generico: '" + texto + "'"));
        }
        if (ehUrl(texto) || (link.ehExterno() && texto.equalsIgnoreCase(link.destino()))) {
            return Optional.of(achado(link,
                    "o texto do link e a propria URL: '" + resumir(texto) + "'"));
        }
        return Optional.empty();
    }

    private static Achado achado(HyperlinkDoDocumento link, String evidencia) {
        String destino = link.ehExterno() ? " (destino: " + resumir(link.destino()) + ")" : "";
        // MEDIA: o link funciona e o destino existe; o que se perde e saber para
        // onde ele vai sem ler o texto ao redor.
        return new Achado(Problema.Severidade.MEDIA, link.partePacote(), evidencia + destino);
    }

    /** Minusculas, sem pontuacao final e sem acento — 'Saiba Mais!' casa com 'saiba mais'. */
    private static String normalizar(String texto) {
        String semAcento = java.text.Normalizer.normalize(texto, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return semAcento.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}]+$", "")
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static boolean ehUrl(String texto) {
        String minusculo = texto.toLowerCase(Locale.ROOT);
        return ESQUEMAS_DE_URL.stream().anyMatch(minusculo::startsWith);
    }

    private static String resumir(String valor) {
        return valor.length() <= 60 ? valor : valor.substring(0, 57) + "...";
    }
}
