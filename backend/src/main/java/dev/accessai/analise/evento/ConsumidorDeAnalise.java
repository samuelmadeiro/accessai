package dev.accessai.analise.evento;

import dev.accessai.analise.app.ProcessadorDeAnalise;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Ponto de entrada do Kafka. Nao tem regra: so coloca o correlationId no
 * contexto de log e delega.
 *
 * <p>O correlationId no MDC e o que permite seguir uma jornada do upload ate o
 * ultimo log do processamento, atravessando a fronteira do topico.
 */
@Component
public class ConsumidorDeAnalise {

    private static final String CHAVE_MDC = "correlationId";

    private final ProcessadorDeAnalise processador;

    public ConsumidorDeAnalise(ProcessadorDeAnalise processador) {
        this.processador = processador;
    }

    @KafkaListener(
            topics = "${accessai.kafka.topico-analise-solicitada}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void aoReceber(AnaliseSolicitadaV1 evento) {
        MDC.put(CHAVE_MDC, String.valueOf(evento.correlationId()));
        try {
            processador.processar(evento);
        } finally {
            MDC.remove(CHAVE_MDC);
        }
    }
}
