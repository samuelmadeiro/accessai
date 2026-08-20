package dev.accessai.correlacao;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Coloca o correlationId no MDC no inicio de cada requisicao e devolve o valor
 * no cabecalho da resposta.
 *
 * <p>Devolver o id importa: sem isso, quem chamou a API nao tem como citar a
 * requisicao ao relatar um problema, e o suporte volta a pedir "que horas foi?".
 *
 * <p>Ordem mais alta possivel: um filtro que rode antes deste produz log sem
 * correlacao, que e exatamente o buraco que ele existe para fechar.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FiltroDeCorrelacao extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta,
                                    FilterChain cadeia) throws ServletException, IOException {
        String correlationId = Correlacao.normalizar(requisicao.getHeader(Correlacao.CABECALHO));
        Correlacao.definir(correlationId);
        resposta.setHeader(Correlacao.CABECALHO, correlationId);
        try {
            cadeia.doFilter(requisicao, resposta);
        } finally {
            // Thread de servlet e reaproveitada: nao limpar vaza o id de uma
            // requisicao para a proxima, e o log passa a mentir.
            Correlacao.limpar();
        }
    }
}
