package dev.accessai.analise.regras;

import static dev.accessai.analise.regras.Documentos.comTitulo;
import static org.assertj.core.api.Assertions.assertThat;

import dev.accessai.analise.dominio.Problema;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RegraTituloAusente")
class RegraTituloAusenteTest {

    private final RegraTituloAusente regra = new RegraTituloAusente();

    @Test
    @DisplayName("dc:title ausente vira achado MEDIA apontando docProps/core.xml")
    void tituloAusente() {
        assertThat(regra.avaliar(comTitulo(Optional.empty())))
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.severidade()).isEqualTo(Problema.Severidade.MEDIA);
                    assertThat(a.partePacote()).isEqualTo("docProps/core.xml");
                    assertThat(a.evidencia()).contains("nao declara dc:title");
                });
    }

    @Test
    @DisplayName("dc:title em branco tambem e achado, com evidencia diferente")
    void tituloEmBranco() {
        assertThat(regra.avaliar(comTitulo(Optional.of("   "))))
                .singleElement()
                .extracting(Achado::evidencia).asString()
                .contains("esta em branco");
    }

    @Test
    @DisplayName("titulo preenchido nao vira achado")
    void tituloPreenchido() {
        assertThat(regra.avaliar(comTitulo(Optional.of("Edital 3/2026")))).isEmpty();
    }

    @Test
    @DisplayName("um documento so pode ter um problema de titulo")
    void achadoUnico() {
        assertThat(regra.avaliar(comTitulo(Optional.empty()))).hasSize(1);
    }

    @Test
    @DisplayName("declara 2.4.2")
    void identificacao() {
        assertThat(regra.id()).isEqualTo("TITULO_AUSENTE");
        assertThat(regra.criterioWcag()).isEqualTo("2.4.2");
    }
}
