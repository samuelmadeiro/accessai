package spike;

import static spike.AltText.Status.AUSENTE;
import static spike.AltText.Status.PRESENTE;
import static spike.AltText.Status.VAZIO;

import java.util.List;
import java.util.Map;

/**
 * A verdade esperada para cada arquivo do corpus.
 *
 * <p>Esta e a especificacao, escrita a partir do conteudo dos arquivos e nao a
 * partir do que os extratores devolvem. Os dois caminhos sao medidos contra ela.
 * Onde um caminho diverge, a divergencia e registrada como limitacao daquele
 * caminho — nunca reescrevendo a expectativa para o teste passar.
 */
final class EspecificacaoCorpus {

    private EspecificacaoCorpus() {
    }

    record ImagemEsperada(String parte, AltText.Status status, String texto) {
    }

    static final Map<String, List<ImagemEsperada>> ESPERADO = Map.ofEntries(
            Map.entry("01", List.of(
                    new ImagemEsperada("word/document.xml", PRESENTE,
                            "Grafico de barras da receita trimestral"))),

            Map.entry("02", List.of(
                    new ImagemEsperada("word/document.xml", VAZIO, ""))),

            Map.entry("03", List.of(
                    new ImagemEsperada("word/document.xml", AUSENTE, null))),

            // wp:anchor, nao wp:inline. Mesma estrutura do export real do Google Docs.
            Map.entry("04", List.of(
                    new ImagemEsperada("word/document.xml", AUSENTE, null))),

            // LibreOffice escreve descr e title; descr manda.
            Map.entry("05", List.of(
                    new ImagemEsperada("word/document.xml", PRESENTE,
                            "Fluxograma do processo de compra"))),

            // mc:AlternateContent declara o mesmo desenho duas vezes.
            // O correto e UMA imagem.
            Map.entry("06", List.of(
                    new ImagemEsperada("word/document.xml", PRESENTE,
                            "Selo de acessibilidade"))),

            Map.entry("07", List.of(
                    new ImagemEsperada("word/document.xml", PRESENTE,
                            "Logotipo da prefeitura"))),

            // Imagem so no cabecalho.
            Map.entry("08", List.of(
                    new ImagemEsperada("word/header2.xml", AUSENTE, null))),

            Map.entry("09", List.of(
                    new ImagemEsperada("word/document.xml", PRESENTE, "Mapa da regiao sul"),
                    new ImagemEsperada("word/document.xml", VAZIO, ""),
                    new ImagemEsperada("word/document.xml", AUSENTE, null))),

            // descr="   " conta como decorativa, nao como preenchida.
            Map.entry("10", List.of(
                    new ImagemEsperada("word/document.xml", VAZIO, "   ")))
    );

    /** Casos que o caminho POI nao consegue atender. Ver ExtratorPoiTest. */
    static final List<String> POI_NAO_ATENDE = List.of("06");
}
