package dev.accessai.analise;

import static dev.accessai.analise.extracao.DocxDeTeste.imagemInline;
import static dev.accessai.analise.extracao.DocxDeTeste.pacote;
import static dev.accessai.analise.extracao.DocxDeTeste.tituloPorEstilo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import dev.accessai.analise.api.AnaliseDto;
import dev.accessai.analise.app.ExecucaoDaAnalise;
import dev.accessai.analise.dominio.AnaliseRepository;
import dev.accessai.analise.dominio.ProblemaRepository;
import dev.accessai.analise.dominio.SituacaoAnalise;
import dev.accessai.analise.outbox.EventoDeOutbox;
import dev.accessai.analise.outbox.EventoDeOutboxRepository;
import dev.accessai.analise.outbox.EventoEmDlt;
import dev.accessai.analise.outbox.EventoEmDltRepository;
import dev.accessai.correlacao.Correlacao;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Testes de resiliencia do pipeline: outbox, retry com backoff, DLT,
 * idempotencia e correlationId.
 *
 * <p>Nenhum destes cenarios pode ser provado com mock de broker: retry e
 * backoff sao comportamento do container de listener, e o desvio para a DLT
 * acontece dentro do Kafka. Um teste com fila em memoria provaria que o codigo
 * chama os metodos certos, nao que o pipeline sobrevive a uma falha.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@EnabledIfDockerAvailable
@DisplayName("Slice 3: outbox, retry, DLT e correlacao")
class ResilienciaDoPipelineIT {

    private static final String TOPICO = "accessai.analise.solicitada.v1";
    private static final Duration LIMITE = Duration.ofSeconds(60);
    private static final Duration SONDAGEM = Duration.ofMillis(250);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    @DynamicPropertySource
    static void apontarKafka(DynamicPropertyRegistry registro) {
        registro.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        // Backoff curto: o teste precisa ver o retry acontecer, nao esperar por ele.
        registro.add("accessai.kafka.retry.tentativas", () -> 3);
        registro.add("accessai.kafka.retry.intervalo-inicial-ms", () -> 200);
        registro.add("accessai.kafka.retry.intervalo-maximo-ms", () -> 1000);
        // Uma particao so. O teste da mensagem ilegivel depende disso: com tres
        // particoes, o lixo e o documento seguinte cairiam em particoes
        // diferentes e o documento passaria mesmo com o consumidor travado na
        // outra — o teste ficaria verde sem provar nada.
        registro.add("accessai.kafka.particoes", () -> 1);
    }

    @LocalServerPort
    private int porta;

    @Autowired
    private AnaliseRepository analiseRepository;

    @Autowired
    private ProblemaRepository problemaRepository;

    @Autowired
    private EventoDeOutboxRepository outboxRepository;

    @Autowired
    private EventoEmDltRepository dltRepository;

    @Autowired
    @Qualifier("kafkaTemplateDeBytes")
    private KafkaTemplate<String, byte[]> kafkaDeBytes;

    /**
     * Espiao para forcar falha transitoria. Nao ha como derrubar o Postgres no
     * meio do teste sem tornar o teste lento e instavel; o espiao produz
     * exatamente a mesma excecao que uma indisponibilidade produziria.
     */
    @MockitoSpyBean
    private ExecucaoDaAnalise execucao;

    private RestClient http;

    @BeforeEach
    void prepararCliente() {
        http = RestClient.builder()
                .baseUrl("http://localhost:" + porta)
                .defaultStatusHandler(status -> true, (requisicao, resposta) -> { })
                .build();
    }

    @Test
    @DisplayName("o POST grava o evento no outbox e o publicador marca como publicado")
    void outboxGravaEPublica() {
        UUID analiseId = enviar(documentoAcessivel(), "acessivel.docx", null);

        await().atMost(LIMITE).pollInterval(SONDAGEM).untilAsserted(() -> {
            List<EventoDeOutbox> eventos = outboxRepository.findAll().stream()
                    .filter(e -> e.getAgregadoId().equals(analiseId))
                    .toList();

            assertThat(eventos).singleElement().satisfies(evento -> {
                assertThat(evento.foiPublicado())
                        .as("marcado so depois de o broker confirmar")
                        .isTrue();
                assertThat(evento.getTentativas()).isZero();
                assertThat(evento.getUltimoErro()).isNull();
                assertThat(evento.getChave()).isEqualTo(analiseId.toString());
            });
        });

        aguardarSituacao(analiseId, SituacaoAnalise.CONCLUIDA);
    }

    @Test
    @DisplayName("falha transitoria e reentregue com backoff e a analise conclui")
    void falhaTransitoriaEhReentregue() {
        // Duas falhas seguidas, depois o processamento real. Com o retry
        // configurado em 3 tentativas, a terceira tem que dar certo.
        doThrow(new IllegalStateException("banco indisponivel"))
                .doThrow(new IllegalStateException("banco indisponivel"))
                .doCallRealMethod()
                .when(execucao).executar(any());

        UUID analiseId = enviar(documentoAcessivel(), "transitoria.docx", null);

        aguardarSituacao(analiseId, SituacaoAnalise.CONCLUIDA);
        assertThat(dltRepository.findByAnaliseId(analiseId))
                .as("falha transitoria que se resolveu nao pode acabar na DLT")
                .isEmpty();
    }

    @Test
    @DisplayName("falha permanente vai para a DLT e a analise vira FALHOU")
    void falhaPermanenteVaiParaDlt() {
        // Pacote que passa na validacao do upload e so quebra no parsing:
        // ParteIlegivelException esta na lista de nao-retentaveis, entao a
        // mensagem vai direto para a DLT, sem gastar as tentativas.
        byte[] quebrado = pacote().com("word/document.xml", "<w:document><w:body>").bytes();

        UUID analiseId = enviar(quebrado, "quebrado.docx", null);

        aguardarSituacao(analiseId, SituacaoAnalise.FALHOU);

        await().atMost(LIMITE).pollInterval(SONDAGEM).untilAsserted(() -> {
            List<EventoEmDlt> registros = dltRepository.findByAnaliseId(analiseId);
            assertThat(registros).singleElement().satisfies(registro -> {
                assertThat(registro.getExcecao())
                        .as("guardar o wrapper do Spring Kafka nao diria nada: "
                                + "o que importa e a causa")
                        .isEqualTo("dev.accessai.analise.extracao.ParteIlegivelException");
                assertThat(registro.getTopicoOrigem()).isEqualTo(TOPICO);
            });
        });

        assertThat(problemaRepository.findByAnaliseIdOrderByCriadoEmAsc(analiseId))
                .as("analise que falhou nao pode ter resultado parcial gravado")
                .isEmpty();
    }

    @Test
    @DisplayName("evento duplicado nao duplica problema: reentrega e esperada, nao excecao")
    void eventoDuplicadoEhIgnorado() {
        UUID analiseId = enviar(documentoComUmProblema(), "com-problema.docx", null);
        aguardarSituacao(analiseId, SituacaoAnalise.CONCLUIDA);

        int problemasAntes = problemaRepository.findByAnaliseIdOrderByCriadoEmAsc(analiseId).size();
        assertThat(problemasAntes).isEqualTo(1);

        // Republica exatamente os mesmos bytes, com o mesmo eventId: e o que
        // acontece quando o publicador morre entre enviar e marcar.
        EventoDeOutbox evento = outboxRepository.findAll().stream()
                .filter(e -> e.getAgregadoId().equals(analiseId))
                .findFirst()
                .orElseThrow();
        kafkaDeBytes.send(new ProducerRecord<>(evento.getTopico(), evento.getChave(),
                evento.getPayload().getBytes(StandardCharsets.UTF_8)));

        // Espera o consumidor digerir a duplicata antes de conferir.
        await().during(Duration.ofSeconds(3)).atMost(LIMITE).untilAsserted(() ->
                assertThat(problemaRepository.findByAnaliseIdOrderByCriadoEmAsc(analiseId))
                        .as("a chave de deduplicacao e o eventId")
                        .hasSize(problemasAntes));
    }

    @Test
    @DisplayName("mensagem que nao desserializa nao trava o consumidor")
    void mensagemIlegivelNaoTravaOConsumidor() {
        // Bytes que nunca virao um AnaliseSolicitadaV1. A falha acontece DENTRO
        // do poll(), antes de existir registro para o DefaultErrorHandler tratar.
        kafkaDeBytes.send(new ProducerRecord<>(TOPICO, UUID.randomUUID().toString(),
                "isto nao e json".getBytes(StandardCharsets.UTF_8)));

        // O que prova o conserto nao e o destino do lixo, e sim que o proximo
        // documento valido continua sendo processado. Sem o
        // ErrorHandlingDeserializer o container repolla o mesmo offset para
        // sempre e este upload nunca sairia de RECEBIDA. E sem uma fabrica de
        // fim de linha para a DLT, o lixo desviado seria reentregue eternamente
        // procurando um topico .DLT.DLT que nao existe.
        UUID analiseId = enviar(documentoAcessivel(), "depois-do-lixo.docx", null);

        aguardarSituacao(analiseId, SituacaoAnalise.CONCLUIDA);
    }

    @Test
    @DisplayName("o correlationId do cliente volta na resposta e atravessa o pipeline")
    void correlationIdAtravessaOPipeline() {
        String correlationId = UUID.randomUUID().toString();

        UUID analiseId = enviar(documentoAcessivel(), "correlacionado.docx", correlationId);

        AnaliseDto.RespostaDeAnalise resultado = aguardarSituacao(analiseId,
                SituacaoAnalise.CONCLUIDA);
        assertThat(resultado.correlationId()).hasToString(correlationId);

        EventoDeOutbox evento = outboxRepository.findAll().stream()
                .filter(e -> e.getAgregadoId().equals(analiseId))
                .findFirst()
                .orElseThrow();
        assertThat(evento.getCorrelationId())
                .as("o mesmo id no HTTP, no banco e no evento — senao nao ha jornada")
                .hasToString(correlationId);
    }

    // ------------------------------------------------------------------

    private static byte[] documentoAcessivel() {
        return pacote()
                .comCorpo(tituloPorEstilo(1, "Documento"))
                .comTitulo("Documento")
                .comIdiomaPadrao("pt-BR")
                .bytes();
    }

    private static byte[] documentoComUmProblema() {
        return pacote()
                .comCorpo(tituloPorEstilo(1, "Documento"), imagemInline("foto.png", null))
                .comTitulo("Documento")
                .comIdiomaPadrao("pt-BR")
                .bytes();
    }

    private UUID enviar(byte[] docx, String nomeArquivo, String correlationId) {
        MultiValueMap<String, Object> corpo = new LinkedMultiValueMap<>();
        corpo.add("file", new ByteArrayResource(docx) {
            @Override
            public String getFilename() {
                return nomeArquivo;
            }
        });

        ResponseEntity<AnaliseDto.RespostaDeRecebimento> resposta = http.post()
                .uri("/analyses")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .headers(cabecalhos -> {
                    if (correlationId != null) {
                        cabecalhos.add(Correlacao.CABECALHO, correlationId);
                    }
                })
                .body(corpo)
                .retrieve()
                .toEntity(AnaliseDto.RespostaDeRecebimento.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resposta.getHeaders().getFirst(Correlacao.CABECALHO))
                .as("a API sempre devolve o id da jornada")
                .isNotBlank();
        if (correlationId != null) {
            assertThat(resposta.getHeaders().getFirst(Correlacao.CABECALHO))
                    .isEqualTo(correlationId);
        }
        assertThat(resposta.getBody()).isNotNull();
        return resposta.getBody().analiseId();
    }

    private AnaliseDto.RespostaDeAnalise aguardarSituacao(UUID analiseId,
                                                          SituacaoAnalise esperada) {
        await().atMost(LIMITE).pollInterval(SONDAGEM).untilAsserted(() ->
                assertThat(analiseRepository.findById(analiseId).orElseThrow().getSituacao())
                        .isEqualTo(esperada));

        return http.get()
                .uri("/analyses/{id}", analiseId)
                .retrieve()
                .toEntity(AnaliseDto.RespostaDeAnalise.class)
                .getBody();
    }
}
