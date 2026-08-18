package dev.accessai.analise.extracao;

/**
 * Um hyperlink do documento, com o destino ja resolvido pelo arquivo de
 * relacionamentos.
 *
 * @param partePacote parte onde o link esta
 * @param texto       texto visivel; vazio quando o link so envolve uma imagem
 * @param destino     URL externa, ou {@code null} para ancora interna e para
 *                    link cujo relacionamento nao foi encontrado
 */
public record HyperlinkDoDocumento(String partePacote, String texto, String destino) {

    public String textoNormalizado() {
        return texto == null ? "" : texto.strip();
    }

    public boolean temTexto() {
        return !textoNormalizado().isEmpty();
    }

    public boolean ehExterno() {
        return destino != null && !destino.isBlank();
    }
}
