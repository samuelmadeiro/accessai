package dev.accessai.ia;

import org.jspecify.annotations.NonNull;

/**
 * O que o provider respondeu num turno de conversa, com a procedencia grudada.
 *
 * <p>Tipo proprio, e nao {@link RespostaDeIa}: a resposta de recomendacao e uma
 * LISTA presa a {@code regraId}, e e essa amarra que permite ao guardrail
 * descartar item por item. Conversa e texto corrido — nao ha item a descartar, e
 * fingir que ha faria a conferencia de saida parecer mais fina do que e.
 *
 * <p>A consequencia esta em {@link GuardrailDeFundamentacao#conferirSaidaDeConversa}:
 * em conversa o guardrail recusa a resposta INTEIRA, porque nao existe recorte.
 *
 * @param texto      a resposta ao ultimo turno
 * @param procedencia FIXTURE ou MODELO — vai ate o corpo HTTP e ate o banco (I5)
 * @param modelo     nome do modelo ou do fake
 * @param custoEstimadoEmCentavos zero quando nao houve chamada paga
 */
public record RespostaDeConversa(String texto,
                                 AiProvider.Procedencia procedencia,
                                 String modelo,
                                 long custoEstimadoEmCentavos) {

    public @NonNull String textoOuVazio() {
        return texto == null ? "" : texto;
    }
}
