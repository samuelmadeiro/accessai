package dev.accessai.ia;

import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * O que o provider devolveu, com a procedencia grudada.
 *
 * @param recomendacoes  uma por achado, cada uma citando o `regraId` que a gerou
 * @param procedencia    FIXTURE ou MODELO — vai ate a resposta da API
 * @param modelo         nome do modelo ou do fake
 * @param custoEstimadoEmCentavos  zero quando nao houve chamada paga
 */
public record RespostaDeIa(List<Recomendacao> recomendacoes,
                           AiProvider.Procedencia procedencia,
                           String modelo,
                           long custoEstimadoEmCentavos) {

    public RespostaDeIa {
        recomendacoes = List.copyOf(recomendacoes);
    }

    /**
     * Uma recomendacao, presa ao achado que a originou.
     *
     * <p>{@code regraId} nao e enfeite de rastreabilidade: e o que o guardrail
     * confere. Recomendacao que cita regra ausente da analise e recusada antes
     * de chegar ao usuario, porque texto plausivel sobre problema inexistente e
     * exatamente o modo de falha de LLM que o §1 proibe.
     */
    public record Recomendacao(String regraId, String criterioWcag, String texto) {
    }

    public static @NonNull RespostaDeIa vazia(AiProvider.Procedencia procedencia,
                                              String modelo) {
        return new RespostaDeIa(List.of(), procedencia, modelo, 0L);
    }
}
