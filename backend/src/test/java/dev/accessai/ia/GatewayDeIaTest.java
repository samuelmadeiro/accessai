package dev.accessai.ia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A ORDEM das etapas do gateway, que e a decisao inteira daquela classe.
 */
@DisplayName("GatewayDeIa")
class GatewayDeIaTest {

    private final ContadorDeGastoDeIa contador = mock(ContadorDeGastoDeIa.class);
    private final ProviderEspiao provider = new ProviderEspiao();
    private final GatewayDeIa gateway =
            new GatewayDeIa(provider, new GuardrailDeFundamentacao(), contador,
                    new MontadorDePrompt());

    @Test
    @DisplayName("guardrail vem ANTES do provider: pergunta sem base nao chega ao modelo")
    void guardrailAntesDoProvider() {
        // Recusar depois de chamar seria pagar para descobrir que a pergunta nao
        // tinha base.
        assertThatThrownBy(() -> gateway.recomendar(comAchados("e o 1.4.3?")))
                .isInstanceOf(GuardrailDeFundamentacao.SemFundamentoException.class);

        assertThat(provider.chamadas).isZero();
        verify(contador, never()).registrar(anyLong());
    }

    @Test
    @DisplayName("orcamento esgotado impede a chamada")
    void orcamentoAntesDoProvider() {
        doThrow(new ContadorDeGastoDeIa.OrcamentoEsgotadoException(1000, 1000))
                .when(contador).conferir();

        assertThatThrownBy(() -> gateway.recomendar(comAchados(null)))
                .isInstanceOf(ContadorDeGastoDeIa.OrcamentoEsgotadoException.class);

        assertThat(provider.chamadas).isZero();
    }

    @Test
    @DisplayName("o custo e registrado com o valor da chamada, depois dela")
    void registraCustoDepois() {
        gateway.recomendar(comAchados(null));

        assertThat(provider.chamadas).isEqualTo(1);
        verify(contador).registrar(0L);
    }

    @Test
    @DisplayName("a saida passa pelo guardrail antes de sair do gateway")
    void saidaFiltrada() {
        provider.inventar = true;

        RespostaDeIa resposta = gateway.recomendar(comAchados(null));

        assertThat(resposta.recomendacoes())
                .as("o que o provider inventou nao pode sair do gateway")
                .isEmpty();
    }

    private static AiProvider.Fundamento comAchados(String pergunta) {
        return new AiProvider.Fundamento(UUID.randomUUID(), List.of(
                new AiProvider.Fundamento.Achado("IMAGEM_SEM_TEXTO_ALTERNATIVO",
                        "1.1.1", "ALTA", "x")), pergunta);
    }

    /** Conta chamadas e, sob demanda, inventa uma regra que nao esta na analise. */
    private static final class ProviderEspiao implements AiProvider {
        int chamadas;
        boolean inventar;

        @Override
        public Procedencia procedencia() {
            return Procedencia.MODELO;
        }

        @Override
        public String modelo() {
            return "espiao";
        }

        @Override
        public RespostaDeIa recomendar(Fundamento fundamento, String prompt) {
            chamadas++;
            String regra = inventar ? "REGRA_ALUCINADA" : "IMAGEM_SEM_TEXTO_ALTERNATIVO";
            return new RespostaDeIa(
                    List.of(new RespostaDeIa.Recomendacao(regra, "1.1.1", "texto")),
                    Procedencia.MODELO, "espiao", 0L);
        }
    }
}
