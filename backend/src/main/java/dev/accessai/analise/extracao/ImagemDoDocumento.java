package dev.accessai.analise.extracao;

/**
 * Uma imagem encontrada no pacote OOXML.
 *
 * @param partePacote parte onde a imagem esta (word/document.xml, word/header1.xml, ...)
 * @param nome        nome do desenho, para evidencia legivel
 * @param situacaoAlt classificacao do texto alternativo
 * @param texto       texto alternativo cru; null quando AUSENTE
 */
public record ImagemDoDocumento(String partePacote, String nome, SituacaoDoAlt situacaoAlt,
                                String texto) {

    /**
     * Ausente e vazio sao estados diferentes, e a diferenca importa: alt
     * ausente e defeito; alt vazio e declaracao deliberada de imagem
     * decorativa, que o WCAG 1.1.1 permite.
     */
    public enum SituacaoDoAlt {
        PRESENTE,
        VAZIO,
        AUSENTE
    }

    public static ImagemDoDocumento de(String partePacote, String nome, String descr) {
        if (descr == null) {
            return new ImagemDoDocumento(partePacote, nome, SituacaoDoAlt.AUSENTE, null);
        }
        if (descr.isBlank()) {
            return new ImagemDoDocumento(partePacote, nome, SituacaoDoAlt.VAZIO, descr);
        }
        return new ImagemDoDocumento(partePacote, nome, SituacaoDoAlt.PRESENTE, descr);
    }
}
