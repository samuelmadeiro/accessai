package dev.accessai.ia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * O criterio de pronto da Slice 6: pergunta sem base na analise e RECUSADA.
 *
 * <p>Os casos de recusa importam mais que os de aceitacao. Um guardrail que
 * deixa passar e indistinguivel de nao ter guardrail, e a diferenca so aparece
 * quando o modelo generativo chegar — tarde demais para descobrir.
 */
@DisplayName("GuardrailDeFundamentacao")
class GuardrailDeFundamentacaoTest {

    private static final UUID ANALISE = UUID.randomUUID();

    private final GuardrailDeFundamentacao guardrail = new GuardrailDeFundamentacao();

    // ------------------------------------------------------------- entrada

    @Test
    @DisplayName("analise sem problema nenhum nao rende recomendacao")
    void semAchadosRecusa() {
        // Pedir a um LLM que fale sobre um resultado limpo produz conselho
        // generico apresentado como analise DESTE documento.
        assertThatThrownBy(() -> guardrail.conferirEntrada(
                new AiProvider.Fundamento(ANALISE, List.of(), null)))
                .isInstanceOf(GuardrailDeFundamentacao.SemFundamentoException.class)
                .hasMessageContaining("nao encontrou problema nenhum");
    }

    @Test
    @DisplayName("pergunta sobre criterio que a analise NAO verificou e recusada")
    void perguntaSemBaseRecusa() {
        // O caso nomeado no §7. A analise achou 1.1.1; a pergunta fala de 1.4.3,
        // que este documento nunca teve verificado. Responder produziria um
        // texto plausivel sobre um problema inexistente.
        assertThatThrownBy(() -> guardrail.conferirEntrada(comAchados(
                "por que o contraste 1.4.3 esta ruim neste documento?")))
                .isInstanceOf(GuardrailDeFundamentacao.SemFundamentoException.class)
                .hasMessageContaining("1.4.3")
                .hasMessageContaining("so responde sobre o que mediu");
    }

    @Test
    @DisplayName("a recusa diz o que a analise TEM, nao so o que falta")
    void recusaDizOQueExiste() {
        assertThatThrownBy(() -> guardrail.conferirEntrada(comAchados("e o 2.4.7?")))
                .hasMessageContaining("1.1.1");
    }

    @Test
    @DisplayName("pergunta sobre criterio que a analise encontrou passa")
    void perguntaComBasePassa() {
        assertThatCode(() -> guardrail.conferirEntrada(
                comAchados("como corrijo o 1.1.1?"))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"como melhoro este documento?", "resuma os problemas",
                            "   ", "o que fazer primeiro?"})
    @DisplayName("pergunta generica, sem citar criterio, passa")
    void perguntaGenericaPassa(String pergunta) {
        // Sem numero de criterio nao ha o que conferir objetivamente, e inventar
        // um classificador de intencao a mao teria falso positivo e falso
        // negativo. O que protege este caso e o guardrail de SAIDA.
        assertThatCode(() -> guardrail.conferirEntrada(comAchados(pergunta)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("pergunta nula passa: e o caminho de gerar sem perguntar nada")
    void perguntaNulaPassa() {
        assertThatCode(() -> guardrail.conferirEntrada(comAchados(null)))
                .doesNotThrowAnyException();
    }

    // --------------------------------------------------------------- saida

    @Test
    @DisplayName("recomendacao que cita regra ausente da analise e descartada")
    void saidaAlucinadaEhDescartada() {
        // O modo de falha mais provavel de um modelo generativo: citar criterio
        // WCAG plausivel que ninguem verificou.
        RespostaDeIa bruta = new RespostaDeIa(List.of(
                new RespostaDeIa.Recomendacao("IMAGEM_SEM_TEXTO_ALTERNATIVO", "1.1.1", "ok"),
                new RespostaDeIa.Recomendacao("CONTRASTE_INSUFICIENTE", "1.4.3", "inventado")),
                AiProvider.Procedencia.MODELO, "modelo-x", 3L);

        RespostaDeIa filtrada = guardrail.filtrarSaida(comAchados(null), bruta);

        assertThat(filtrada.recomendacoes()).singleElement()
                .satisfies(r -> assertThat(r.regraId())
                        .isEqualTo("IMAGEM_SEM_TEXTO_ALTERNATIVO"));
    }

    @Test
    @DisplayName("filtrar a saida preserva procedencia, modelo e custo")
    void filtrarPreservaMetadados() {
        // O custo ja foi pago mesmo pelo que foi descartado: zerar aqui faria o
        // contador do teto perder gasto que aconteceu.
        RespostaDeIa bruta = new RespostaDeIa(
                List.of(new RespostaDeIa.Recomendacao("NAO_EXISTE", "9.9.9", "x")),
                AiProvider.Procedencia.MODELO, "modelo-x", 7L);

        RespostaDeIa filtrada = guardrail.filtrarSaida(comAchados(null), bruta);

        assertThat(filtrada.recomendacoes()).isEmpty();
        assertThat(filtrada.procedencia()).isEqualTo(AiProvider.Procedencia.MODELO);
        assertThat(filtrada.modelo()).isEqualTo("modelo-x");
        assertThat(filtrada.custoEstimadoEmCentavos()).isEqualTo(7L);
    }

    @Test
    @DisplayName("regraId nulo nao estoura e nao passa")
    void regraNulaNaoPassa() {
        RespostaDeIa bruta = new RespostaDeIa(
                List.of(new RespostaDeIa.Recomendacao(null, "1.1.1", "x")),
                AiProvider.Procedencia.MODELO, "modelo-x", 0L);

        assertThat(guardrail.filtrarSaida(comAchados(null), bruta).recomendacoes())
                .isEmpty();
    }

    // ------------------------------------------------------------------

    private static AiProvider.Fundamento comAchados(String pergunta) {
        return new AiProvider.Fundamento(ANALISE, List.of(
                new AiProvider.Fundamento.Achado("IMAGEM_SEM_TEXTO_ALTERNATIVO",
                        "1.1.1", "ALTA", "word/document.xml")), pergunta);
    }
}
