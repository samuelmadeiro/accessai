package dev.accessai.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Cria os topicos na subida.
 *
 * <p>O broker sobe com {@code auto.create.topics.enable=false} de proposito:
 * topico criado por acidente nasce com particionamento e retencao default e
 * ninguem descobre ate a producao. Aqui o contrato e explicito.
 */
@Configuration
public class TopicosKafkaConfig {

    @Bean
    public NewTopic topicoAnaliseSolicitada(PropriedadesAccessAi propriedades) {
        PropriedadesAccessAi.Kafka kafka = propriedades.kafka();
        return TopicBuilder.name(kafka.topicoAnaliseSolicitada())
                .partitions(kafka.particoes())
                .replicas(kafka.replicas())
                .build();
    }

    /**
     * A DLT tem o MESMO numero de particoes do topico de origem.
     *
     * <p>O desvio preserva a particao do registro original; com menos particoes
     * aqui, a mensagem cairia numa particao inexistente e o proprio desvio
     * falharia — no pior momento possivel, que e quando algo ja deu errado.
     */
    @Bean
    public NewTopic topicoAnaliseSolicitadaDlt(PropriedadesAccessAi propriedades) {
        PropriedadesAccessAi.Kafka kafka = propriedades.kafka();
        return TopicBuilder.name(kafka.topicoAnaliseSolicitada() + ".DLT")
                .partitions(kafka.particoes())
                .replicas(kafka.replicas())
                .build();
    }
}
