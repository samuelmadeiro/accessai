package dev.accessai.analise.score;

import static org.assertj.core.api.Assertions.assertThat;

import dev.accessai.analise.dominio.PrincipioWcag;
import dev.accessai.analise.dominio.Problema;
import dev.accessai.config.PropriedadesAccessAi;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/**
 * Testes da calculadora de score.
 *
 * <p>Todos os numeros abaixo estao escritos a mao no teste. E de proposito: se
 * a conta mudar, o teste tem que ser reescrito por alguem que entendeu a
 * mudanca — um teste que recalcula a formula so prova que a formula e igual a
 * ela mesma.
 */
@DisplayName("CalculadoraDeScore")
class CalculadoraDeScoreTest {

    private static final UUID ANALISE = UUID.randomUUID();
    private static final Instant AGORA = Instant.parse("2026-08-19T12:00:00Z");

    private final CalculadoraDeScore calculadora = comPesos(25, 25, 25, 25);

    @Nested
    @DisplayName("penalidade por severidade")
    class Penalidades {

        @Test
        @DisplayName("documento sem problema tira 100 em tudo")
        void semProblemas() {
            ScoreDaAnalise score = calculadora.calcular(List.of(), todosOsPrincipios());

            assertThat(score.global()).isEqualTo(100);
            assertThat(score.categorias()).allSatisfy(c -> assertThat(c.score()).isEqualTo(100));
            assertThat(score.naoAvaliados()).isEmpty();
        }

        @Test
        @DisplayName("cada severidade desconta o seu peso")
        void descontoPorSeveridade() {
            // ALTA 15 + MEDIA 8 + BAIXA 3 = 26 no principio Perceptivel.
            ScoreDaAnalise score = calculadora.calcular(
                    List.of(problema("1.1.1", Problema.Severidade.ALTA),
                            problema("1.3.1", Problema.Severidade.MEDIA),
                            problema("1.4.3", Problema.Severidade.BAIXA)),
                    todosOsPrincipios());

            assertThat(categoria(score, PrincipioWcag.PERCEPTIVEL)).satisfies(c -> {
                assertThat(c.penalidade()).isEqualTo(26);
                assertThat(c.score()).isEqualTo(74);
                assertThat(c.problemas()).isEqualTo(3);
            });
        }

        @Test
        @DisplayName("CRITICA desconta mais que ALTA")
        void criticaDescontaMais() {
            ScoreDaAnalise critica = calculadora.calcular(
                    List.of(problema("1.1.1", Problema.Severidade.CRITICA)), todosOsPrincipios());
            ScoreDaAnalise alta = calculadora.calcular(
                    List.of(problema("1.1.1", Problema.Severidade.ALTA)), todosOsPrincipios());

            assertThat(categoria(critica, PrincipioWcag.PERCEPTIVEL).score())
                    .isLessThan(categoria(alta, PrincipioWcag.PERCEPTIVEL).score());
        }

        @Test
        @DisplayName("a nota para em zero: nao existe categoria negativa")
        void notaNaoFicaNegativa() {
            List<Problema> muitos = List.of(
                    problema("1.1.1", Problema.Severidade.ALTA),
                    problema("1.1.1", Problema.Severidade.ALTA),
                    problema("1.1.1", Problema.Severidade.ALTA),
                    problema("1.1.1", Problema.Severidade.ALTA),
                    problema("1.1.1", Problema.Severidade.ALTA),
                    problema("1.1.1", Problema.Severidade.ALTA),
                    problema("1.1.1", Problema.Severidade.ALTA),
                    problema("1.1.1", Problema.Severidade.CRITICA));

            ScoreDaAnalise score = calculadora.calcular(muitos, todosOsPrincipios());

            assertThat(categoria(score, PrincipioWcag.PERCEPTIVEL)).satisfies(c -> {
                assertThat(c.score()).isZero();
                assertThat(c.penalidade())
                        .as("a penalidade bruta continua visivel, mesmo maior que 100")
                        .isEqualTo(130);
            });
        }

        @Test
        @DisplayName("o problema cai na categoria do numero do criterio")
        void categoriaVemDoCriterio() {
            ScoreDaAnalise score = calculadora.calcular(
                    List.of(problema("2.4.2", Problema.Severidade.MEDIA),
                            problema("3.1.1", Problema.Severidade.ALTA)),
                    todosOsPrincipios());

            assertThat(categoria(score, PrincipioWcag.PERCEPTIVEL).score()).isEqualTo(100);
            assertThat(categoria(score, PrincipioWcag.OPERAVEL).score()).isEqualTo(92);
            assertThat(categoria(score, PrincipioWcag.COMPREENSIVEL).score()).isEqualTo(85);
        }
    }

    @Nested
    @DisplayName("nota global")
    class Global {

        @Test
        @DisplayName("com pesos iguais, a global e a media das categorias avaliadas")
        void mediaSimples() {
            // Perceptivel 100-15=85, Operavel 100-8=92, Compreensivel 100 -> (85+92+100)/3 = 92,33
            ScoreDaAnalise score = calculadora.calcular(
                    List.of(problema("1.1.1", Problema.Severidade.ALTA),
                            problema("2.4.4", Problema.Severidade.MEDIA)),
                    Set.of(PrincipioWcag.PERCEPTIVEL, PrincipioWcag.OPERAVEL,
                            PrincipioWcag.COMPREENSIVEL));

            assertThat(score.global()).isEqualTo(92);
        }

        @Test
        @DisplayName("peso maior puxa a global para a categoria pesada")
        void pesoImporta() {
            CalculadoraDeScore comPerceptivelPesado = comPesos(70, 10, 10, 10);
            List<Problema> problemas = List.of(problema("1.1.1", Problema.Severidade.CRITICA));
            Set<PrincipioWcag> avaliados = todosOsPrincipios();

            int global = comPerceptivelPesado.calcular(problemas, avaliados).global();
            int globalComPesosIguais = calculadora.calcular(problemas, avaliados).global();

            assertThat(global).isLessThan(globalComPesosIguais);
        }
    }

    @Nested
    @DisplayName("categoria sem regra implementada")
    class CategoriaNaoAvaliada {

        @Test
        @DisplayName("fica fora da media em vez de entrar valendo 100")
        void ficaForaDaMedia() {
            // Se Robusto entrasse valendo 100, a global subiria de 85 para 88,75.
            ScoreDaAnalise score = calculadora.calcular(
                    List.of(problema("1.1.1", Problema.Severidade.ALTA)),
                    Set.of(PrincipioWcag.PERCEPTIVEL, PrincipioWcag.OPERAVEL,
                            PrincipioWcag.COMPREENSIVEL));

            assertThat(score.global()).isEqualTo(95);
            assertThat(score.categorias()).hasSize(3);
            assertThat(score.naoAvaliados()).containsExactly(PrincipioWcag.ROBUSTO);
        }

        @Test
        @DisplayName("a resposta declara o que nao foi verificado")
        void declaraOQueNaoFoiVerificado() {
            ScoreDaAnalise score = calculadora.calcular(List.of(),
                    Set.of(PrincipioWcag.PERCEPTIVEL));

            assertThat(score.naoAvaliados()).containsExactly(PrincipioWcag.OPERAVEL,
                    PrincipioWcag.COMPREENSIVEL, PrincipioWcag.ROBUSTO);
        }

        @Test
        @DisplayName("sem nenhuma regra ativa nao ha score: null, e nao zero")
        void nenhumPrincipioAvaliado() {
            ScoreDaAnalise score = calculadora.calcular(List.of(), Set.of());

            assertThat(score.foiCalculado()).isFalse();
            assertThat(score.global())
                    .as("zero diria 'documento inacessivel'; null diz 'ninguem mediu'")
                    .isNull();
        }

        @Test
        @DisplayName("todos os pesos zerados nao viram divisao por zero")
        void pesosTodosZerados() {
            ScoreDaAnalise score = comPesos(0, 0, 0, 0).calcular(List.of(), todosOsPrincipios());

            assertThat(score.global()).isNull();
            assertThat(score.categorias()).as("as categorias continuam visiveis").hasSize(4);
        }
    }

    // ------------------------------------------------------------------

    private static Set<PrincipioWcag> todosOsPrincipios() {
        return Set.of(PrincipioWcag.values());
    }

    private static ScorePorPrincipio categoria(ScoreDaAnalise score, PrincipioWcag principio) {
        return score.categorias().stream()
                .filter(c -> c.principio() == principio)
                .findFirst()
                .orElseThrow(() -> new AssertionError("categoria ausente: " + principio));
    }

    private static Problema problema(String criterio, Problema.Severidade severidade) {
        return Problema.registrar(ANALISE, "REGRA_DE_TESTE", criterio, Problema.Nivel.A,
                severidade, "word/document.xml", "evidencia", AGORA);
    }

    private static CalculadoraDeScore comPesos(int perceptivel, int operavel, int compreensivel,
                                               int robusto) {
        PropriedadesAccessAi propriedades = new PropriedadesAccessAi(
                new PropriedadesAccessAi.Upload(DataSize.ofMegabytes(25)),
                new PropriedadesAccessAi.Kafka("topico", 1, (short) 1,
                        new PropriedadesAccessAi.Kafka.Retry(4, 500, 2.0, 10_000)),
                new PropriedadesAccessAi.Score(
                        new PropriedadesAccessAi.Score.Pesos(perceptivel, operavel, compreensivel,
                                robusto),
                        new PropriedadesAccessAi.Score.Penalidades(25, 15, 8, 3)),
                new PropriedadesAccessAi.Outbox(500, 50, 2_000, 10_000, 10));
        return new CalculadoraDeScore(propriedades);
    }
}
