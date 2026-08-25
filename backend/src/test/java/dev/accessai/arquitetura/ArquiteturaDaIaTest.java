package dev.accessai.arquitetura;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import dev.accessai.analise.dominio.Problema;
import dev.accessai.analise.dominio.ProblemaRepository;
import dev.accessai.analise.regras.MotorDeRegras;
import dev.accessai.analise.score.CalculadoraDeScore;
import dev.accessai.ia.AiProvider;
import dev.accessai.ia.GatewayDeIa;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * As invariantes do ADR 0012, travadas por teste em vez de por disciplina.
 *
 * <p>Escrito ANTES do copiloto existir, de proposito. Fronteira verificada
 * depois que o codigo existe e fronteira negociada com o codigo que ja a
 * violou — e a violacao chega como diff grande, no fim da slice, quando desfazer
 * custa caro.
 *
 * <p><b>Nenhuma regra aqui pode passar por vacuidade.</b> Regra de arquitetura
 * sobre pacote inexistente passa sem verificar nada, entao
 * {@code allowEmptyShould(false)} esta ligado e {@code dev.accessai.copiloto}
 * existe desde ja, com {@code package-info.java}. Sem isso, este arquivo seria
 * um teste verde que nao afirma nada.
 */
@DisplayName("ADR 0012: fronteira do copiloto e porta unica de IA")
class ArquiteturaDaIaTest {

    private static final String PACOTE_COPILOTO = "dev.accessai.copiloto..";
    private static final String PACOTE_EXTRACAO = "dev.accessai.analise.extracao..";

    private static JavaClasses producao;

    @BeforeAll
    static void importar() {
        // Sem as classes de teste: fixture PODE tocar o extrator — e o teste do
        // proprio extrator existe para isso. A invariante e sobre producao.
        producao = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("dev.accessai");
    }

    @Test
    @DisplayName("o pacote do copiloto existe, para nenhuma regra abaixo passar por vacuidade")
    void oPacoteExiste() {
        assertThat(producao.stream()
                .anyMatch(c -> c.getPackageName().startsWith("dev.accessai.copiloto")))
                .as("dev.accessai.copiloto sumiu: as regras deste arquivo parariam de "
                        + "verificar qualquer coisa em silencio")
                .isTrue();
    }

    @Nested
    @DisplayName("I2 - o contexto e a Analise, nunca o documento")
    class ContextoEhAnalise {

        @Test
        @DisplayName("copiloto nao depende da extracao nem do pacote OOXML")
        void naoDependeDaExtracao() {
            ArchRule regra = noClasses()
                    .that().resideInAPackage(PACOTE_COPILOTO)
                    .should().dependOnClassesThat().resideInAPackage(PACOTE_EXTRACAO)
                    .because("o copiloto conversa sobre a Analise ja produzida (ADR 0012). "
                            + "Com acesso ao documento ele viraria um segundo analisador: "
                            + "LLM opinando sobre acessibilidade sem regra, sem criterio "
                            + "versionado e sem evidencia")
                    .allowEmptyShould(false);

            regra.check(producao);
        }
    }

    @Nested
    @DisplayName("I1 - o copiloto nao produz achado")
    class NaoProduzAchado {

        /**
         * Os quatro proibidos sao os caminhos de ESCRITA de achado e de score.
         *
         * <p>{@code Problema.Nivel} e {@code Problema.Severidade} ficam de fora
         * da proibicao: sao tipos aninhados que {@code VisaoDaAnalise.ProblemaVisto}
         * ja expoe, e ler a severidade de um problema e leitura legitima. Proibir
         * o nome inteiro faria a regra impedir o copiloto de LER a analise — que
         * e exatamente o que ele deve fazer.
         *
         * <p>Pelo mesmo motivo {@code ScoreDaAnalise} nao entra: ele viaja dentro
         * de {@code VisaoDaAnalise}. I1 proibe calcular e gravar, nao ler.
         */
        @Test
        @DisplayName("copiloto nao alcanca Problema, o repositorio, o motor nem a calculadora")
        void naoEscreveAchadoNemScore() {
            ArchRule regra = noClasses()
                    .that().resideInAPackage(PACOTE_COPILOTO)
                    .should().dependOnClassesThat().haveFullyQualifiedName(
                            Problema.class.getName())
                    .orShould().dependOnClassesThat().haveFullyQualifiedName(
                            ProblemaRepository.class.getName())
                    .orShould().dependOnClassesThat().haveFullyQualifiedName(
                            MotorDeRegras.class.getName())
                    .orShould().dependOnClassesThat().haveFullyQualifiedName(
                            CalculadoraDeScore.class.getName())
                    .because("problema e score saem do motor deterministico (ADR 0009). "
                            + "O copiloto explica o resultado; nao acrescenta linha a ele")
                    .allowEmptyShould(false);

            regra.check(producao);
        }
    }

    @Nested
    @DisplayName("I4 - AiProvider continua porta unica")
    class PortaUnica {

        /**
         * Nao havia teste desta invariante ate aqui: ela era javadoc e
         * disciplina. Este e o primeiro que falha se alguem abrir a segunda
         * porta — inclusive na Slice 7, que ESTENDE a interface para multi-turno
         * e teria como abrir uma via nova sem querer.
         *
         * <p>A regra fala do tipo {@code AiProvider} pelo nome exato. Os tipos
         * aninhados ({@code Fundamento}, {@code Procedencia}) sao dados que
         * atravessam a fronteira de proposito: quem chama o gateway precisa
         * montar um {@code Fundamento}, e e justamente por ele nao aceitar texto
         * livre que a fundamentacao e contrato de compilador.
         */
        @Test
        @DisplayName("so o gateway e as implementacoes do provider dependem de AiProvider")
        void apenasOGatewayDependeDoProvider() {
            ArchRule regra = noClasses()
                    .that().areNotAssignableTo(AiProvider.class)
                    .and().doNotHaveFullyQualifiedName(GatewayDeIa.class.getName())
                    .should().dependOnClassesThat().haveFullyQualifiedName(
                            AiProvider.class.getName())
                    .because("CONTRIBUTING.md secao 5: chamadas a LLM passam exclusivamente "
                            + "pela interface AiProvider, e o gateway e o unico chamador. "
                            + "A Slice 7 estende essa porta; nao abre outra")
                    .allowEmptyShould(false);

            regra.check(producao);
        }
    }
}
