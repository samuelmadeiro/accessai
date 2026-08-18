package dev.accessai.analise.extracao;

/**
 * Uma ocorrencia de {@code w:lang} no pacote.
 *
 * @param partePacote onde foi encontrada; {@code word/styles.xml} significa
 *                    padrao do documento, qualquer outra e local a um trecho
 * @param valor       o codigo declarado, por exemplo {@code pt-BR}
 */
public record IdiomaDeclarado(String partePacote, String valor) {

    public boolean ehPadraoDoDocumento() {
        return Ooxml.PARTE_ESTILOS.equals(partePacote);
    }
}
