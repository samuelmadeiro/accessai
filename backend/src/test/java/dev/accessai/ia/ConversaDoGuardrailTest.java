package dev.accessai.ia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O guardrail na saida de CONVERSA, que e diferente do de recomendacao.
 *
 * <p>Em recomendacao ele descarta item a item e entrega o resto. Em conversa ele
 * recusa a resposta inteira, porque texto corrido nao tem item a descartar —
 * cortar a frase que cita um criterio inventado deixaria o paragrafo apoiado
 * numa premissa que sumiu.
 */
@DisplayName("Slice 7: guardrail de saida em conversa")
class ConversaDoGuardrailTest {

    private final GuardrailDeFundamentacao guardrail = new GuardrailDeFundamentacao();

    private static final UUID ANALISE = UUID.randomUUID();

    @Test
    @DisplayName("resposta que cita criterio ausente da analise e recusada por inteiro")
    void recusaCriterioInventado() {
        AiProvider.Fundamento fundamento = fundamentoCom("1.1.1");
        RespostaDeConversa alucinada = new RespostaDeConversa(
                "O alt esta faltando (1.1.1) e o contraste tambem falha em 1.4.3.",
                AiProvider.Procedencia.MODELO, "modelo-x", 3L);

        assertThatThrownBy(() -> guardrail.conferirSaidaDeConversa(fundamento, alucinada))
                .isInstanceOf(GuardrailDeFundamentacao.SemFundamentoException.class)
                .hasMessageContaining("1.4.3");
    }

    @Test
    @DisplayName("resposta que so cita o que a analise encontrou passa inteira")
    void aceitaCriterioMedido() {
        AiProvider.Fundamento fundamento = fundamentoCom("1.1.1");
        RespostaDeConversa fundamentada = new RespostaDeConversa(
                "A imagem esta sem texto alternativo (1.1.1).",
                AiProvider.Procedencia.FIXTURE, "fixture-local", 0L);

        assertThat(guardrail.conferirSaidaDeConversa(fundamento, fundamentada))
                .isSameAs(fundamentada);
    }

    @Test
    @DisplayName("resposta sem numero de criterio nenhum passa")
    void aceitaRespostaSemCriterio() {
        // A conferencia e por numero de criterio: e o unico gancho objetivo que
        // existe em texto livre. Resposta que nao cita numero nenhum nao tem
        // como estar citando criterio que a analise nao mediu.
        AiProvider.Fundamento fundamento = fundamentoCom("1.1.1");
        RespostaDeConversa semNumero = new RespostaDeConversa(
                "Descreva a imagem no campo de texto alternativo.",
                AiProvider.Procedencia.FIXTURE, "fixture-local", 0L);

        assertThat(guardrail.conferirSaidaDeConversa(fundamento, semNumero).texto())
                .isEqualTo("Descreva a imagem no campo de texto alternativo.");
    }

    private static AiProvider.Fundamento fundamentoCom(String criterio) {
        return new AiProvider.Fundamento(ANALISE,
                List.of(new AiProvider.Fundamento.Achado(
                        "IMAGEM_SEM_TEXTO_ALTERNATIVO", criterio, "ALTA", "logo.png")),
                "e sobre a imagem?");
    }
}
