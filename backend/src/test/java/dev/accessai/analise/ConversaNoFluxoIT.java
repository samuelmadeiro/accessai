package dev.accessai.analise;

import static dev.accessai.analise.extracao.DocxDeTeste.imagemInline;
import static dev.accessai.analise.extracao.DocxDeTeste.pacote;
import static dev.accessai.analise.extracao.DocxDeTeste.tituloPorEstilo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.accessai.analise.api.AnaliseDto;
import dev.accessai.apoio.SegredoDeTeste;
import dev.accessai.apoio.TokenDeTeste;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Slice 7 ponta a ponta: copiloto conversacional sobre a analise, com historico.
 *
 * <p>O criterio de pronto do §7 e "idem [guardrail testado], com historico".
 * Os dois testes que o cumprem sao {@code perguntaSemBaseEhRecusadaNoTurno} e
 * {@code historicoAcumulaOsDoisLados}; os outros existem para que eles nao
 * passem por acidente — um copiloto que recusasse tudo tambem recusaria a
 * pergunta sem base.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@EnabledIfDockerAvailable
@DisplayName("Slice 7: copiloto conversacional sobre a analise")
class ConversaNoFluxoIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8.2-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void apontar(DynamicPropertyRegistry registro) {
        registro.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registro.add("accessai.jwt.segredo", SegredoDeTeste::valor);
    }

    @LocalServerPort
    private int porta;

    private RestClient http;

    @BeforeEach
    void preparar() {
        http = RestClient.builder()
                .baseUrl("http://localhost:" + porta)
                .defaultHeader("Authorization", "Bearer " + TokenDeTeste.novaConta(porta))
                .defaultStatusHandler(status -> true, (requisicao, resposta) -> { })
                .build();
    }

    @Test
    @DisplayName("o turno responde sobre o que a analise encontrou, declarando a procedencia")
    void turnoRespondeSobreAAnalise() {
        UUID analiseId = analisarDocumentoComProblema();

        ResponseEntity<Map> resposta = perguntar(analiseId, "o que voce encontrou?");

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resposta.getBody().get("papel")).isEqualTo("ASSISTENTE");
        assertThat(resposta.getBody().get("procedencia"))
                .as("I5 do ADR 0012: se a resposta veio de fixture, isso aparece")
                .isEqualTo("FIXTURE");
        assertThat(resposta.getBody().get("texto").toString())
                .contains("IMAGEM_SEM_TEXTO_ALTERNATIVO");
    }

    @Test
    @DisplayName("CRITERIO DO §7: pergunta sem base na analise e recusada NO TURNO")
    void perguntaSemBaseEhRecusadaNoTurno() {
        UUID analiseId = analisarDocumentoComProblema();

        // Primeiro turno legitimo: a conversa ja existe e ja tem contexto.
        assertThat(perguntar(analiseId, "o que voce encontrou?").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        // O segundo turno pergunta por contraste, que esta analise nunca mediu —
        // a regra 1.4.3 nem existe no Rule Engine. Conferir so na abertura da
        // conversa deixaria isto passar.
        ResponseEntity<Map> recusada = perguntar(analiseId, "e o contraste 1.4.3?");

        assertThat(recusada.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(recusada.getBody().get("codigo")).isEqualTo("SEM_FUNDAMENTO_NA_ANALISE");
        assertThat(recusada.getBody().get("mensagem").toString()).contains("1.4.3");
    }

    @Test
    @DisplayName("o turno recusado nao deixa rastro: nem a pergunta e gravada")
    void turnoRecusadoNaoGravaNada() {
        UUID analiseId = analisarDocumentoComProblema();
        perguntar(analiseId, "o que voce encontrou?");

        int antes = turnos(analiseId).size();
        perguntar(analiseId, "e o contraste 1.4.3?");

        // Gravar a pergunta de um turno cuja resposta nunca existiu produziria um
        // historico que nao aconteceu — e ele voltaria como contexto depois.
        assertThat(turnos(analiseId)).hasSize(antes);
    }

    @Test
    @DisplayName("CRITERIO DO §7: o historico acumula os dois lados, em ordem")
    void historicoAcumulaOsDoisLados() {
        UUID analiseId = analisarDocumentoComProblema();

        perguntar(analiseId, "o que voce encontrou?");
        perguntar(analiseId, "e como eu corrijo isso?");

        List<Map<String, Object>> turnos = turnos(analiseId);

        assertThat(turnos).hasSize(4);
        assertThat(turnos).extracting(t -> t.get("papel"))
                .containsExactly("USUARIO", "ASSISTENTE", "USUARIO", "ASSISTENTE");
        assertThat(turnos.getFirst().get("texto")).isEqualTo("o que voce encontrou?");
        assertThat(turnos.getFirst().get("procedencia"))
                .as("a fala do usuario nao veio de provider nenhum")
                .isNull();
        assertThat(turnos.get(1).get("procedencia")).isEqualTo("FIXTURE");
    }

    @Test
    @DisplayName("documento sem problema nenhum nao rende conversa")
    void documentoLimpoNaoRendeConversa() {
        // Consequencia registrada no ADR 0012: o guardrail recusa quando nao ha
        // achado, e em multi-turno isso recusa a conversa inteira. E intencional
        // — sobre documento limpo so haveria conselho generico apresentado como
        // analise deste documento.
        UUID analiseId = analisar(pacote()
                .comCorpo(tituloPorEstilo(1, "Documento"))
                .comTitulo("Documento")
                .comIdiomaPadrao("pt-BR")
                .bytes(), "limpo.docx");

        ResponseEntity<Map> resposta = perguntar(analiseId, "como esta meu documento?");

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(resposta.getBody().get("codigo")).isEqualTo("SEM_FUNDAMENTO_NA_ANALISE");
    }

    @Test
    @DisplayName("turno sem pergunta e recusado antes de qualquer chamada")
    void turnoSemPergunta() {
        UUID analiseId = analisarDocumentoComProblema();

        ResponseEntity<Map> resposta = perguntar(analiseId, "   ");

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(resposta.getBody().get("codigo")).isEqualTo("PERGUNTA_VAZIA");
    }

    @Test
    @DisplayName("a conversa de outro usuario responde 404, no POST e no GET")
    void conversaDeOutroUsuario() {
        UUID analiseId = analisarDocumentoComProblema();
        perguntar(analiseId, "o que voce encontrou?");

        RestClient outro = RestClient.builder()
                .baseUrl("http://localhost:" + porta)
                .defaultHeader("Authorization", "Bearer " + TokenDeTeste.novaConta(porta))
                .defaultStatusHandler(status -> true, (requisicao, resposta) -> { })
                .build();

        // A conversa nao tem owner_id proprio: o isolamento vem de carregar a
        // analise por findByIdAndOwnerId antes de tocar na tabela de turnos.
        // 404 e nao 403 — dizer "existe, mas nao e sua" ja vaza a existencia.
        assertThat(outro.get().uri("/analyses/" + analiseId + "/chat")
                .retrieve().toEntity(String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(outro.post().uri("/analyses/" + analiseId + "/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("pergunta", "o que tem ai?"))
                .retrieve().toEntity(String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ------------------------------------------------------------------

    private ResponseEntity<Map> perguntar(UUID analiseId, String pergunta) {
        return http.post().uri("/analyses/" + analiseId + "/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("pergunta", pergunta))
                .retrieve().toEntity(Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> turnos(UUID analiseId) {
        Map corpo = http.get().uri("/analyses/" + analiseId + "/chat")
                .retrieve().toEntity(Map.class).getBody();
        return (List<Map<String, Object>>) corpo.get("turnos");
    }

    private UUID analisarDocumentoComProblema() {
        return analisar(pacote()
                .comCorpo(tituloPorEstilo(1, "Documento") + imagemInline("logo.png", null))
                .comTitulo("Documento")
                .comIdiomaPadrao("pt-BR")
                .bytes(), "com-problema.docx");
    }

    private UUID analisar(byte[] docx, String nome) {
        MultiValueMap<String, Object> corpo = new LinkedMultiValueMap<>();
        corpo.add("file", new ByteArrayResource(docx) {
            @Override
            public String getFilename() {
                return nome;
            }
        });

        ResponseEntity<AnaliseDto.RespostaDeRecebimento> criada = http.post()
                .uri("/analyses")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(corpo)
                .retrieve()
                .toEntity(AnaliseDto.RespostaDeRecebimento.class);

        assertThat(criada.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID analiseId = criada.getBody().analiseId();

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250))
                .until(() -> "CONCLUIDA".equals(http.get().uri("/analyses/" + analiseId)
                        .retrieve().toEntity(AnaliseDto.RespostaDeAnalise.class)
                        .getBody().situacao()));
        return analiseId;
    }
}
