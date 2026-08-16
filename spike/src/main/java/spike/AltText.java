package spike;

/**
 * Modelo compartilhado pelos dois extratores.
 *
 * <p>A distincao que da nome ao spike esta em {@link Status}: alt ausente e
 * alt vazio sao coisas diferentes. Ausente e defeito (WCAG 1.1.1). Vazio e
 * declaracao deliberada de imagem decorativa, e valido.
 */
public final class AltText {

    private AltText() {
    }

    public enum Status {
        /** Existe texto alternativo com conteudo. */
        PRESENTE,
        /** Atributo existe mas esta vazio ou so com espacos: imagem decorativa. */
        VAZIO,
        /** Atributo nao existe: alt faltando. */
        AUSENTE
    }

    /**
     * @param parte      parte do pacote onde a imagem foi achada (word/document.xml,
     *                   word/header2.xml, ...)
     * @param nomeImagem valor de @name do docPr, ou identificador equivalente
     * @param status     classificacao do alt text
     * @param texto      texto alternativo cru, sem trim; null quando AUSENTE
     */
    public record Imagem(String parte, String nomeImagem, Status status, String texto) {

        public static Imagem de(String parte, String nomeImagem, String descr) {
            if (descr == null) {
                return new Imagem(parte, nomeImagem, Status.AUSENTE, null);
            }
            if (descr.isBlank()) {
                return new Imagem(parte, nomeImagem, Status.VAZIO, descr);
            }
            return new Imagem(parte, nomeImagem, Status.PRESENTE, descr);
        }
    }
}
