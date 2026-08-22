package dev.accessai.integracao.ml;

/**
 * A resposta do ML Service, ou a ausencia dela.
 *
 * <p>{@code usouHeuristica} nao e detalhe do outro lado: e a diferenca entre "um
 * modelo classificou isto" e "um punhado de regras classificou isto". Sem
 * distinguir, o produto apresentaria regra como predicao de ML — o que a secao 1
 * do CONTRIBUTING.md proibe.
 *
 * <p>{@code confianca} e {@link Double}, e nao {@code double}: a heuristica nao
 * tem probabilidade, e um {@code 0.0} implicito faria o consumidor ler ausencia
 * de confianca como confianca zero. Nulo diz "esta resposta nao tem confianca".
 *
 * @param categoria      GOOD, WEAK ou INSUFFICIENT; nulo quando nao houve resposta
 * @param confianca      probabilidade da classe; nula quando veio de regra
 * @param modeloVersao   versao do artefato que respondeu; nula sem modelo
 * @param usouHeuristica true quando a resposta veio de regra, nao de modelo
 */
public record RespostaMlDTO(String categoria, Double confianca, String modeloVersao,
                            boolean usouHeuristica) {

    /**
     * O ML Service nao respondeu. O motor de regras segue sozinho.
     *
     * <p>Existe para que "o servico disse que nao sabe" e "o servico nao
     * respondeu" nao cheguem ao chamador como a mesma coisa: so a segunda
     * significa que nao ha informacao de ML nenhuma para esta analise.
     */
    public static RespostaMlDTO indisponivel() {
        return new RespostaMlDTO(null, null, null, false);
    }

    /** Falso quando nao houve predicao — a analise segue so com as regras. */
    public boolean temPredicao() {
        return categoria != null;
    }
}
