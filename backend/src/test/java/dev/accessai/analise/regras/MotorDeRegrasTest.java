package dev.accessai.analise.regras;

import static dev.accessai.analise.regras.CatalogoWcagTest.catalogoCom;
import static dev.accessai.analise.regras.CatalogoWcagTest.criterio;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.accessai.analise.dominio.Problema;
import dev.accessai.analise.extracao.ImagemDoDocumento;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Testes do motor de regras.
 *
 * <p>O que este teste protege e a invariante de CLAUDE.md secao 6: nivel WCAG
 * vem da tabela, nunca da regra, e criterio inexistente derruba a aplicacao em
 * vez de virar relatorio publicado.
 */
@DisplayName("MotorDeRegras")
class MotorDeRegrasTest {

    private static final UUID ANALISE = UUID.randomUUID();
    private static final Instant AGORA = Instant.parse("2026-08-19T12:00:00Z");

    @Test
    @DisplayName("regra que cita criterio inexistente derruba a subida da aplicacao")
    void criterioInexistenteDerrubaASubida() {
        CatalogoWcag catalogo = catalogoCom(criterio("1.1.1", "direta"));

        assertThatThrownBy(() -> new MotorDeRegras(
                List.of(new RegraFalsa("REGRA_TORTA", "1.1.1.1")), catalogo))
                .isInstanceOf(CatalogoWcag.CriterioDesconhecidoException.class);
    }

    @Test
    @DisplayName("o nivel do problema vem da tabela, nao da regra")
    void nivelVemDaTabela() {
        // A regra so declara o id do criterio. Quem diz que 2.4.2 e AA e a tabela.
        MotorDeRegras motor = new MotorDeRegras(
                List.of(new RegraFalsa("REGRA_AA", "2.4.2")),
                catalogoCom(criterio("2.4.2", "direta")));

        List<Problema> problemas = motor.executar(ANALISE, List.of(), AGORA);

        assertThat(problemas).singleElement().satisfies(p -> {
            assertThat(p.getNivelWcag()).isEqualTo(Problema.Nivel.AA);
            assertThat(p.getCriterioWcag()).isEqualTo("2.4.2");
            assertThat(p.getRegraId()).isEqualTo("REGRA_AA");
            assertThat(p.getAnaliseId()).isEqualTo(ANALISE);
            assertThat(p.getCriadoEm()).isEqualTo(AGORA);
        });
    }

    @Test
    @DisplayName("regra ligada a criterio inaplicavel nao produz problema")
    void criterioInaplicavelNaoProduzProblema() {
        MotorDeRegras motor = new MotorDeRegras(
                List.of(new RegraFalsa("REGRA_INAPLICAVEL", "2.4.5")),
                catalogoCom(criterio("2.4.5", "inaplicavel")));

        assertThat(motor.executar(ANALISE, List.of(), AGORA))
                .as("criterio inaplicavel vira recomendacao, e recomendacao nao e problema")
                .isEmpty();
    }

    @Test
    @DisplayName("motor sem regra nenhuma devolve lista vazia")
    void semRegras() {
        MotorDeRegras motor = new MotorDeRegras(List.of(), catalogoCom());

        assertThat(motor.executar(ANALISE, List.of(), AGORA)).isEmpty();
    }

    /** Regra de teste: sempre acha um problema, para isolar o motor da regra real. */
    private record RegraFalsa(String id, String criterioWcag) implements RegraDeAcessibilidade {

        @Override
        public List<Achado> avaliar(List<ImagemDoDocumento> imagens) {
            return List.of(new Achado(Problema.Severidade.MEDIA, "word/document.xml",
                    "achado de teste"));
        }
    }
}
