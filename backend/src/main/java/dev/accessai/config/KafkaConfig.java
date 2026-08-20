package dev.accessai.config;

import dev.accessai.analise.app.AnaliseNaoEncontradaException;
import dev.accessai.analise.app.BinarioAusenteException;
import dev.accessai.analise.app.DocumentoInvalidoException;
import dev.accessai.analise.extracao.ParteIlegivelException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Politica de entrega do consumidor: quantas vezes tentar, com que intervalo, e
 * para onde vai o que nao deu certo.
 *
 * <p>Isto mora na configuracao da mensageria, e nao num {@code try/catch} dentro
 * do consumidor, porque e decisao de infraestrutura: mudar de 4 para 6
 * tentativas nao pode exigir tocar em codigo de dominio.
 */
@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    /**
     * O template padrao, declarado a mao.
     *
     * <p>Ele vinha da auto-configuracao ate esta classe existir: o
     * {@code @ConditionalOnMissingBean(KafkaTemplate.class)} do Boot desiste de
     * criar o template assim que QUALQUER bean de KafkaTemplate aparece — e o
     * de bytes, logo abaixo, e um. Sem esta declaracao explicita, declarar o
     * segundo template apagaria o primeiro em silencio.
     */
    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<?, ?> fabrica) {
        return new KafkaTemplate<>(tipar(fabrica));
    }

    /**
     * Template que publica bytes crus.
     *
     * <p>O outbox guarda o payload ja serializado. Republicar esses bytes
     * exatamente como estao evita que "o que esta no banco" e "o que foi
     * publicado" divirjam no dia em que o formato do evento mudar. O template
     * padrao serializa objeto e nao serve para isso.
     */
    @Bean
    public KafkaTemplate<String, byte[]> kafkaTemplateDeBytes(ProducerFactory<?, ?> fabrica) {
        Map<String, Object> propriedades = new HashMap<>(fabrica.getConfigurationProperties());
        propriedades.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(propriedades));
    }

    /**
     * A fabrica auto-configurada e publicada como {@code ProducerFactory<?, ?>}
     * — o Boot nao sabe que serializadores o projeto escolheu. O cast reconhece
     * isso num lugar so, em vez de espalhar generico cru pelos beans.
     */
    @SuppressWarnings("unchecked")
    private static ProducerFactory<String, Object> tipar(ProducerFactory<?, ?> fabrica) {
        return (ProducerFactory<String, Object>) fabrica;
    }

    /**
     * Retry com backoff exponencial e, esgotadas as tentativas, DLT.
     *
     * <p>Backoff exponencial e nao intervalo fixo: a falha tipica de um
     * consumidor e o banco ou o broker respondendo devagar, e tentar de novo na
     * mesma cadencia so aumenta a carga sobre quem ja esta sofrendo.
     *
     * <p>A DLT recebe o nome do topico original mais {@code .DLT}, na MESMA
     * particao — assim a ordem relativa das mensagens de uma analise sobrevive
     * ao desvio.
     */
    @Bean
    public DefaultErrorHandler tratadorDeErroDoConsumidor(
            @Qualifier("kafkaTemplate") KafkaTemplate<String, Object> template,
            PropriedadesAccessAi propriedades) {

        PropriedadesAccessAi.Kafka.Retry retry = propriedades.kafka().retry();

        // O template do desvio e o PADRAO (serializa objeto), e nao o de bytes:
        // o recuperador republica o valor ja desserializado do registro que
        // falhou, nao os bytes originais.
        DeadLetterPublishingRecoverer recuperador = new DeadLetterPublishingRecoverer(template,
                (registro, excecao) -> new TopicPartition(
                        registro.topic() + ".DLT", registro.partition()));

        // No Spring 7 o ExponentialBackOffWithMaxRetries deixou de existir: o
        // proprio ExponentialBackOff passou a ter maxAttempts. Sem definir o
        // teto, o padrao e tentar por tempo (maxElapsedTime), o que produziria
        // um numero de tentativas que varia com a duracao de cada falha.
        ExponentialBackOff backoff = new ExponentialBackOff();
        backoff.setMaxAttempts(retry.tentativas());
        backoff.setInitialInterval(retry.intervaloInicialMs());
        backoff.setMultiplier(retry.multiplicador());
        backoff.setMaxInterval(retry.intervaloMaximoMs());

        DefaultErrorHandler tratador = new DefaultErrorHandler(recuperador, backoff);

        // Falha permanente nao ganha retry: o pacote nao vai abrir na quarta
        // tentativa, e insistir so atrasa o desfecho e enche o log. Vai direto
        // para a DLT, onde a analise e marcada como FALHOU.
        tratador.addNotRetryableExceptions(
                ParteIlegivelException.class,
                UncheckedIOException.class,
                BinarioAusenteException.class,
                AnaliseNaoEncontradaException.class,
                DocumentoInvalidoException.class);

        tratador.setRetryListeners((registro, excecao, tentativa) ->
                log.warn("tentativa {} falhou topico={} offset={} erro={}",
                        tentativa, registro.topic(), registro.offset(), excecao.toString()));

        return tratador;
    }
}
