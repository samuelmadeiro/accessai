package dev.accessai.analise.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("PrincipioWcag")
class PrincipioWcagTest {

    @ParameterizedTest
    @CsvSource({
        "1.1.1, PERCEPTIVEL",
        "1.4.3, PERCEPTIVEL",
        "2.4.2, OPERAVEL",
        "3.1.1, COMPREENSIVEL",
        "4.1.2, ROBUSTO"
    })
    @DisplayName("o principio sai do primeiro digito do criterio")
    void principioDoCriterio(String criterio, PrincipioWcag esperado) {
        assertThat(PrincipioWcag.doCriterio(criterio)).isEqualTo(esperado);
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.1.1", "0.1.1", "x.1.1", "", " "})
    @DisplayName("criterio fora da WCAG nao tem principio, e isso e erro alto")
    void criterioInvalido(String criterio) {
        // Cair num principio errado colocaria a penalidade na categoria errada
        // sem ninguem perceber.
        assertThatThrownBy(() -> PrincipioWcag.doCriterio(criterio))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("criterio nulo tambem e erro")
    void criterioNulo() {
        assertThatThrownBy(() -> PrincipioWcag.doCriterio(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
