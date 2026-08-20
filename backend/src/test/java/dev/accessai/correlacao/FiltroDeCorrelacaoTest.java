package dev.accessai.correlacao;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("FiltroDeCorrelacao")
class FiltroDeCorrelacaoTest {

    private final FiltroDeCorrelacao filtro = new FiltroDeCorrelacao();

    @Test
    @DisplayName("o id do cliente atravessa a requisicao e volta no cabecalho")
    void idDoCliente() throws ServletException, IOException {
        String id = UUID.randomUUID().toString();
        MockHttpServletRequest requisicao = new MockHttpServletRequest();
        requisicao.addHeader(Correlacao.CABECALHO, id);
        MockHttpServletResponse resposta = new MockHttpServletResponse();
        CadeiaQueObservaOMdc cadeia = new CadeiaQueObservaOMdc();

        filtro.doFilter(requisicao, resposta, cadeia);

        assertThat(cadeia.correlationIdVisto).isEqualTo(id);
        assertThat(resposta.getHeader(Correlacao.CABECALHO))
                .as("sem devolver o id, quem chamou nao tem como citar a requisicao")
                .isEqualTo(id);
    }

    @Test
    @DisplayName("sem cabecalho, o filtro gera um id")
    void semCabecalho() throws ServletException, IOException {
        MockHttpServletResponse resposta = new MockHttpServletResponse();
        CadeiaQueObservaOMdc cadeia = new CadeiaQueObservaOMdc();

        filtro.doFilter(new MockHttpServletRequest(), resposta, cadeia);

        assertThat(cadeia.correlationIdVisto).isNotBlank();
        assertThat(resposta.getHeader(Correlacao.CABECALHO)).isEqualTo(cadeia.correlationIdVisto);
    }

    @Test
    @DisplayName("cabecalho hostil e trocado por um id gerado")
    void cabecalhoHostil() throws ServletException, IOException {
        MockHttpServletRequest requisicao = new MockHttpServletRequest();
        requisicao.addHeader(Correlacao.CABECALHO, "id\nfalsificado");
        CadeiaQueObservaOMdc cadeia = new CadeiaQueObservaOMdc();

        filtro.doFilter(requisicao, new MockHttpServletResponse(), cadeia);

        assertThat(cadeia.correlationIdVisto).doesNotContain("falsificado");
    }

    @Test
    @DisplayName("o MDC e limpo no fim: thread de servlet e reaproveitada")
    void mdcLimpoNoFim() throws ServletException, IOException {
        filtro.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                new MockFilterChain());

        assertThat(MDC.get(Correlacao.CHAVE_MDC))
                .as("id vazado para a proxima requisicao faz o log mentir")
                .isNull();
    }

    /** Guarda o que o MDC continha DURANTE a requisicao, nao depois dela. */
    private static final class CadeiaQueObservaOMdc extends MockFilterChain {

        private String correlationIdVisto;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest requisicao,
                             jakarta.servlet.ServletResponse resposta) {
            this.correlationIdVisto = MDC.get(Correlacao.CHAVE_MDC);
        }
    }
}
