package dev.accessai.ia;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O provider de fixture, e o que impede ele de ser "IA que e template string".
 *
 * <p>A diferenca entre fixture declarada e IA falsa cabe num campo:
 * {@code procedencia}. Estes testes existem para que esse campo nao possa ser
 * removido sem alguem perceber.
 */
@DisplayName("FakeAiProvider")
class FakeAiProviderTest {

    private final FakeAiProvider provider = new FakeAiProvider();

    @Test
    @DisplayName("declara que e fixture, nunca modelo")
    void declaraProcedencia() {
        assertThat(provider.procedencia()).isEqualTo(AiProvider.Procedencia.FIXTURE);
        assertThat(provider.modelo()).isEqualTo(FakeAiProvider.NOME);
        assertThat(provider.recomendar(comAchados("IMAGEM_SEM_TEXTO_ALTERNATIVO"))
                .procedencia()).isEqualTo(AiProvider.Procedencia.FIXTURE);
    }

    @Test
    @DisplayName("custo zero: nenhuma chamada paga aconteceu")
    void custoZero() {
        // Zero, e nao "desconhecido": somar estimativa ao contador do teto faria
        // o orcamento acabar por um numero que ninguem gastou.
        assertThat(provider.recomendar(comAchados("TITULO_AUSENTE"))
                .custoEstimadoEmCentavos()).isZero();
    }

    @Test
    @DisplayName("uma recomendacao por achado, presa ao regraId de origem")
    void umaPorAchado() {
        RespostaDeIa resposta = provider.recomendar(comAchados(
                "IMAGEM_SEM_TEXTO_ALTERNATIVO", "IDIOMA_NAO_DECLARADO"));

        assertThat(resposta.recomendacoes()).hasSize(2);
        assertThat(resposta.recomendacoes()).extracting(RespostaDeIa.Recomendacao::regraId)
                .containsExactly("IMAGEM_SEM_TEXTO_ALTERNATIVO", "IDIOMA_NAO_DECLARADO");
        assertThat(resposta.recomendacoes()).allSatisfy(
                r -> assertThat(r.texto()).isNotBlank());
    }

    @Test
    @DisplayName("nunca inventa achado: sem entrada, sem saida")
    void naoInventa() {
        RespostaDeIa resposta = provider.recomendar(
                new AiProvider.Fundamento(UUID.randomUUID(), List.of(), null));

        assertThat(resposta.recomendacoes()).isEmpty();
    }

    @Test
    @DisplayName("regra sem fixture propria cai no texto generico, nao em nada")
    void regraDesconhecidaTemTextoGenerico() {
        RespostaDeIa resposta = provider.recomendar(comAchados("REGRA_QUE_AINDA_NAO_EXISTE"));

        assertThat(resposta.recomendacoes()).singleElement()
                .satisfies(r -> assertThat(r.texto()).isNotBlank());
    }

    private static AiProvider.Fundamento comAchados(String... regras) {
        return new AiProvider.Fundamento(UUID.randomUUID(),
                java.util.Arrays.stream(regras)
                        .map(r -> new AiProvider.Fundamento.Achado(r, "1.1.1", "ALTA", "x"))
                        .toList(),
                null);
    }
}
