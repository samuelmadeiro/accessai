package dev.accessai.analise.evento;

import dev.accessai.analise.app.ExecucaoDaAnalise;
import dev.accessai.correlacao.Correlacao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Ponto de entrada do Kafka. Nao tem regra e nao trata erro: coloca o
 * correlationId no MDC e delega.
 *
 * <p>Erro sobe de proposito. Quem decide retry, backoff e DLT e o
 * {@code DefaultErrorHandler} configurado em {@code KafkaConfig} — politica de
 * entrega e assunto da infraestrutura de mensageria, nao de um try/catch
 * espalhado no consumidor.
 *
 * <p>O correlationId vem do CABECALHO da mensagem, nao do corpo: assim o MDC ja
 * esta preenchido mesmo quando a desserializacao do payload falha, que e
 * justamente quando o log importa. O corpo continua carregando o id como
 * segunda fonte, para mensagem antiga publicada antes desta slice.
 */
@Component
public class ConsumidorDeAnalise {

    private static final Logger log = LoggerFactory.getLogger(ConsumidorDeAnalise.class);

    private final ExecucaoDaAnalise execucao;

    public ConsumidorDeAnalise(ExecucaoDaAnalise execucao) {
        this.execucao = execucao;
    }

    @KafkaListener(
            topics = "${accessai.kafka.topico-analise-solicitada}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void aoReceber(@Payload AnaliseSolicitadaV1 evento,
                          @Header(name = Correlacao.CABECALHO, required = false)
                          String correlationIdDoCabecalho,
                          @Header(KafkaHeaders.RECEIVED_TOPIC) String topico) {
        String correlationId = correlationIdDoCabecalho != null
                ? correlationIdDoCabecalho
                : String.valueOf(evento.correlationId());
        Correlacao.definir(correlationId);
        try {
            log.debug("mensagem recebida topico={} eventoId={}", topico, evento.eventId());
            execucao.executar(evento);
        } finally {
            Correlacao.limpar();
        }
    }
}
