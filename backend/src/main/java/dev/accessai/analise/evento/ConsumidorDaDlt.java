package dev.accessai.analise.evento;

import dev.accessai.analise.app.RegistroDeFalha;
import dev.accessai.correlacao.Correlacao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Consome a Dead Letter Topic e fecha o ciclo de vida da analise.
 *
 * <p>Sem este consumidor, a DLT seria um cemiterio: a mensagem para de circular
 * e a analise fica em RECEBIDA para sempre, do ponto de vista de quem consulta a
 * API. Aqui a chegada na DLT vira transicao explicita para FALHOU, com a excecao
 * original registrada em {@code evento_em_dlt}.
 *
 * <p>Este consumidor NAO relanca excecao. Se ele falhasse e a mensagem voltasse,
 * a DLT precisaria de uma DLT — e o ciclo nao terminaria nunca.
 */
@Component
public class ConsumidorDaDlt {

    private static final Logger log = LoggerFactory.getLogger(ConsumidorDaDlt.class);

    private final RegistroDeFalha registroDeFalha;

    public ConsumidorDaDlt(RegistroDeFalha registroDeFalha) {
        this.registroDeFalha = registroDeFalha;
    }

    @KafkaListener(
            topics = "${accessai.kafka.topico-analise-solicitada}.DLT",
            groupId = "${spring.kafka.consumer.group-id}-dlt")
    public void aoReceber(@Payload AnaliseSolicitadaV1 evento,
                          @Header(name = Correlacao.CABECALHO, required = false)
                          String correlationId,
                          @Header(name = KafkaHeaders.DLT_ORIGINAL_TOPIC, required = false)
                          String topicoOriginal,
                          @Header(name = KafkaHeaders.DLT_EXCEPTION_FQCN, required = false)
                          String excecaoDeFora,
                          @Header(name = KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN, required = false)
                          String causa,
                          @Header(name = KafkaHeaders.DLT_EXCEPTION_MESSAGE, required = false)
                          String mensagemDeErro) {
        Correlacao.definir(correlationId != null
                ? correlationId : String.valueOf(evento.correlationId()));
        // O Spring Kafka poe no cabecalho a excecao de FORA, que e sempre
        // ListenerExecutionFailedException — nome que nao diz nada sobre o que
        // aconteceu. A causa e o que interessa para quem for diagnosticar.
        String excecao = causa != null ? causa : excecaoDeFora;
        try {
            log.error("mensagem na DLT analiseId={} eventoId={} excecao={} mensagem={}",
                    evento.analiseId(), evento.eventId(), excecao, mensagemDeErro);
            registroDeFalha.registrar(evento, topicoOriginal, excecao, mensagemDeErro);
        } catch (RuntimeException e) {
            // Falhar aqui exigiria uma DLT da DLT. O registro se perde; o log
            // de erro acima e o ultimo recurso.
            log.error("falha ao registrar mensagem da DLT analiseId={}", evento.analiseId(), e);
        } finally {
            Correlacao.limpar();
        }
    }
}
