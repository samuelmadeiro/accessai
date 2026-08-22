package dev.accessai.integracao.ml;

/**
 * O corpo enviado ao ML Service.
 *
 * <p>Os nomes dos componentes sao os do JSON que o servico Python espera
 * ({@code altText}, {@code contextoAntes}, {@code contextoDepois}). Como o
 * schema de la recusa campo desconhecido, qualquer renomeacao aqui vira 422 na
 * primeira chamada — e nao um campo ignorado em silencio.
 *
 * <p>O contexto viaja mesmo sem ser usado pelo modelo de hoje: a inadequacao de
 * um alt costuma so ser visivel ao lado do que esta em volta, e mudar o contrato
 * depois custa mais que carregar o campo agora.
 */
public record RequisicaoMlDTO(String altText, String contextoAntes,
                              String contextoDepois) {

    public static RequisicaoMlDTO de(String altText) {
        return new RequisicaoMlDTO(altText, "", "");
    }
}
