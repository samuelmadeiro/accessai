package dev.accessai.config;

import dev.accessai.analise.app.AnaliseNaoEncontradaException;
import dev.accessai.analise.app.BinarioAusenteException;
import dev.accessai.analise.extracao.ParteIlegivelException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;

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
            @Qualifier("kafkaTemplateDeBytes") KafkaTemplate<String, byte[]> templateDeBytes,
            PropriedadesAccessAi propriedades) {

        PropriedadesAccessAi.Kafka.Retry retry = propriedades.kafka().retry();

        // DOIS templates, escolhidos pelo tipo do valor. Quando a falha e do
        // dominio, o recuperador republica o valor ja desserializado e o
        // template padrao (JSON) da conta. Quando a falha e de DESSERIALIZACAO
        // nao existe objeto: o ErrorHandlingDeserializer entrega os bytes crus,
        // e serializa-los como JSON produziria um segundo lixo em cima do
        // primeiro. A ordem do mapa importa — byte[] e testado antes de Object,
        // que casa com tudo.
        Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
        templates.put(byte[].class, templateDeBytes);
        templates.put(Object.class, template);

        DeadLetterPublishingRecoverer recuperador = new DeadLetterPublishingRecoverer(templates,
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
        // DocumentoInvalidoException ficou de fora: ela so e lancada no caminho
        // HTTP (controller e validador), nunca a partir do consumidor. Listar
        // aqui uma excecao inalcancavel descreve um caminho de falha que nao
        // existe.
        tratador.addNotRetryableExceptions(
                ParteIlegivelException.class,
                UncheckedIOException.class,
                BinarioAusenteException.class,
                AnaliseNaoEncontradaException.class);

        tratador.setRetryListeners((registro, excecao, tentativa) ->
                log.warn("tentativa {} falhou topico={} offset={} erro={}",
                        tentativa, registro.topic(), registro.offset(), excecao.toString()));

        return tratador;
    }

    /**
     * Fabrica exclusiva do consumidor da DLT. A DLT e fim de linha.
     *
     * <p>O consumidor da DLT nao pode usar a fabrica padrao. Aquela desvia o que
     * falha para {@code <topico>.DLT}; aplicada a uma mensagem que JA esta na
     * DLT, ela procuraria {@code ...v1.DLT.DLT} — topico que nao existe, num
     * broker com {@code auto.create.topics.enable=false}. O desvio falharia, o
     * registro seria reentregue, e o ciclo nao terminaria nunca.
     *
     * <p>O caso nao e hipotetico: com o {@code ErrorHandlingDeserializer} ligado,
     * a DLT passa a receber os BYTES CRUS das mensagens que nao desserializaram.
     * O {@code @Payload AnaliseSolicitadaV1} do consumidor da DLT nao converte
     * esses bytes, e a conversao acontece ANTES do corpo do metodo — o
     * {@code try/catch} de la nao a alcanca.
     *
     * <p>Por isso aqui: zero retentativa, log de erro e confirmacao do offset.
     * Perder o registro e desfecho aceitavel; travar a DLT nao e.
     *
     * <p>O tratador nasce dentro deste metodo, e nao como bean. Um segundo bean
     * de {@code CommonErrorHandler} no contexto faria o {@code getIfUnique()} do
     * Boot devolver nulo, e a fabrica PADRAO perderia em silencio o retry com
     * backoff e o desvio para a DLT.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> fabricaDaDlt(
            ConsumerFactory<?, ?> fabricaDeConsumidor) {

        DefaultErrorHandler fimDeLinha = new DefaultErrorHandler(
                (registro, excecao) -> log.error(
                        "registro descartado na DLT topico={} particao={} offset={}",
                        registro.topic(), registro.partition(), registro.offset(), excecao),
                new FixedBackOff(0L, 0L));
        fimDeLinha.setAckAfterHandle(true);

        ConcurrentKafkaListenerContainerFactory<String, Object> fabrica =
                new ConcurrentKafkaListenerContainerFactory<>();
        fabrica.setConsumerFactory(tiparConsumidor(fabricaDeConsumidor));
        fabrica.setCommonErrorHandler(fimDeLinha);
        return fabrica;
    }

    /** Mesmo motivo de {@link #tipar(ProducerFactory)}, do lado do consumidor. */
    @SuppressWarnings("unchecked")
    private static ConsumerFactory<String, Object> tiparConsumidor(ConsumerFactory<?, ?> fabrica) {
        return (ConsumerFactory<String, Object>) fabrica;
    }
}
