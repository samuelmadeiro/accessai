package dev.accessai.analise.outbox;

import dev.accessai.config.PropriedadesAccessAi;
import dev.accessai.correlacao.Correlacao;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Le o outbox e publica no Kafka.
 *
 * <p>Ordem das operacoes, e ela e o ponto: publica, ESPERA o broker confirmar,
 * e so entao marca {@code publicadoEm}. O inverso (marcar antes) transformaria
 * qualquer falha de rede em evento perdido em silencio — o defeito que o outbox
 * existe para eliminar. Com esta ordem, morrer no meio produz uma republicacao,
 * que a deduplicacao do consumidor absorve.
 *
 * <p>A transacao envolve a leitura e a marcacao. Como a consulta usa
 * {@code FOR UPDATE SKIP LOCKED}, duas instancias do backend nunca pegam a
 * mesma linha.
 *
 * <p>O ciclo tem orcamento de tempo. A transacao segura as linhas travadas e
 * uma conexao do pool enquanto espera o broker, e broker LENTO e o caso ruim:
 * nao dispara nenhum alarme e multiplicaria o timeout de cada evento pelo
 * tamanho do lote. Estourado o orcamento, o ciclo devolve o que sobrou para o
 * proximo — as linhas continuam pendentes e nada se perde.
 *
 * <p>O payload vai como bytes, exatamente como foi gravado. Reserializar aqui
 * abriria espaco para o que esta no banco e o que foi publicado divergirem
 * quando o formato do evento mudar.
 */
@Component
public class PublicadorDeOutbox {

    private static final Logger log = LoggerFactory.getLogger(PublicadorDeOutbox.class);

    private final EventoDeOutboxRepository repositorio;
    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final PropriedadesAccessAi.Outbox configuracao;
    private final Clock clock;

    public PublicadorDeOutbox(EventoDeOutboxRepository repositorio,
                              @Qualifier("kafkaTemplateDeBytes")
                              KafkaTemplate<String, byte[]> kafkaTemplate,
                              PropriedadesAccessAi propriedades,
                              Clock clock) {
        this.repositorio = repositorio;
        this.kafkaTemplate = kafkaTemplate;
        this.configuracao = propriedades.outbox();
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${accessai.outbox.intervalo-ms}")
    @Transactional
    public void publicarPendentes() {
        List<EventoDeOutbox> pendentes = repositorio.pegarPendentes(
                configuracao.tamanhoDoLote(), configuracao.maxTentativas());
        if (pendentes.isEmpty()) {
            return;
        }
        log.debug("outbox: {} evento(s) pendente(s)", pendentes.size());

        // nanoTime e nao o Clock injetado: aqui a pergunta e "quanto tempo
        // passou", nao "que horas sao". Clock fixo de teste responde a segunda.
        long limite = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(configuracao.orcamentoDoLoteMs());
        int publicadosNoCiclo = 0;
        for (EventoDeOutbox evento : pendentes) {
            if (publicadosNoCiclo > 0 && System.nanoTime() - limite >= 0) {
                log.warn("orcamento do ciclo esgotado; {} evento(s) ficam para o proximo",
                        pendentes.size() - publicadosNoCiclo);
                break;
            }
            publicar(evento);
            publicadosNoCiclo++;
        }
    }

    private void publicar(EventoDeOutbox evento) {
        Correlacao.definir(evento.getCorrelationId().toString());
        try {
            kafkaTemplate.send(registroPara(evento))
                    .get(configuracao.timeoutDePublicacaoMs(), TimeUnit.MILLISECONDS);
            evento.marcarPublicado(clock.instant());
            log.info("evento publicado topico={} eventoId={} analiseId={}",
                    evento.getTopico(), evento.getId(), evento.getAgregadoId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            evento.registrarFalha("interrompido ao publicar");
            log.warn("outbox interrompido ao publicar eventoId={}", evento.getId());
        } catch (Exception e) {
            // Nao relanca: uma linha problematica nao pode impedir o lote
            // inteiro de sair. A tentativa fica registrada e o proximo ciclo
            // tenta de novo.
            evento.registrarFalha(e.getMessage());
            log.warn("falha ao publicar eventoId={} tentativa={}: {}",
                    evento.getId(), evento.getTentativas(), e.toString());
        } finally {
            Correlacao.limpar();
        }
    }

    private ProducerRecord<String, byte[]> registroPara(EventoDeOutbox evento) {
        ProducerRecord<String, byte[]> registro = new ProducerRecord<>(
                evento.getTopico(),
                evento.getChave(),
                evento.getPayload().getBytes(StandardCharsets.UTF_8));
        // O correlationId viaja no cabecalho, e nao so no corpo: assim o
        // consumidor pode preencher o MDC antes de desserializar o payload.
        registro.headers().add(new RecordHeader(Correlacao.CABECALHO,
                evento.getCorrelationId().toString().getBytes(StandardCharsets.UTF_8)));
        return registro;
    }
}
