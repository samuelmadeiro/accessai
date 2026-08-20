package dev.accessai.correlacao;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Correlacao")
class CorrelacaoTest {

    @AfterEach
    void limpar() {
        Correlacao.limpar();
    }

    @Test
    @DisplayName("id valido do cliente e mantido")
    void idValidoDoCliente() {
        String id = UUID.randomUUID().toString();

        assertThat(Correlacao.normalizar(id)).isEqualTo(id);
    }

    @Test
    @DisplayName("id curto e alfanumerico tambem serve: nem todo cliente usa UUID")
    void idAlfanumerico() {
        assertThat(Correlacao.normalizar("pedido-4711")).isEqualTo("pedido-4711");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
        "",
        "   ",
        "id com espaco",
        "quebra\nde linha",
        "injecao\r\n2026-01-01 ERROR [x] log falsificado",
        "ponto.e.virgula;",
        "<script>alert(1)</script>",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    })
    @DisplayName("valor hostil e descartado e um id novo e gerado")
    void valorHostil(String recebido) {
        // Log com quebra de linha injetada parece confiavel e nao e — pior que
        // log ausente. Por isso o filtro descarta em vez de sanear.
        String normalizado = Correlacao.normalizar(recebido);

        assertThat(normalizado).isNotEqualTo(recebido);
        assertThat(UUID.fromString(normalizado)).isNotNull();
    }

    @Test
    @DisplayName("sem MDC preenchido, atual() gera um id em vez de devolver nulo")
    void semMdc() {
        assertThat(Correlacao.atual()).isNotBlank();
    }

    @Test
    @DisplayName("atual() devolve o que esta no MDC")
    void comMdc() {
        Correlacao.definir("pedido-99");

        assertThat(Correlacao.atual()).isEqualTo("pedido-99");
    }

    @Test
    @DisplayName("id que ja e UUID vira o mesmo UUID")
    void uuidPreservado() {
        UUID id = UUID.randomUUID();
        Correlacao.definir(id.toString());

        assertThat(Correlacao.atualComoUuid()).isEqualTo(id);
    }

    @Test
    @DisplayName("id que nao e UUID vira um UUID derivado, estavel")
    void uuidDerivado() {
        Correlacao.definir("pedido-4711");
        UUID primeiro = Correlacao.atualComoUuid();
        Correlacao.definir("pedido-4711");

        assertThat(Correlacao.atualComoUuid())
                .as("o mesmo texto precisa produzir sempre o mesmo UUID")
                .isEqualTo(primeiro);
    }
}
