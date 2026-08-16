package dev.accessai.analise.evento;

import dev.accessai.config.PropriedadesAccessAi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Publica {@link AnaliseSolicitadaV1}. Unico ponto que conhece o nome do topico. */
@Component
public class ProdutorDeAnalise {

    private static final Logger log = LoggerFactory.getLogger(ProdutorDeAnalise.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topico;

    public ProdutorDeAnalise(KafkaTemplate<String, Object> kafkaTemplate,
                             PropriedadesAccessAi propriedades) {
        this.kafkaTemplate = kafkaTemplate;
        this.topico = propriedades.kafka().topicoAnaliseSolicitada();
    }

    public void publicar(AnaliseSolicitadaV1 evento) {
        // A chave e o analiseId: garante que tudo de uma mesma analise cai na
        // mesma particao e portanto e processado em ordem.
        kafkaTemplate.send(topico, evento.analiseId().toString(), evento);
        log.info("evento publicado topico={} analiseId={} eventId={} correlationId={}",
                topico, evento.analiseId(), evento.eventId(), evento.correlationId());
    }
}
