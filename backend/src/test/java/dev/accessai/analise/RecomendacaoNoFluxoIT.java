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
 * Slice 6 ponta a ponta: recomendacao fundamentada, e a recusa quando nao ha base.
 *
 * <p>O criterio de pronto do §7 e o segundo teste desta classe. Os outros
 * existem para que ele nao passe por acidente — um guardrail que recusa tudo
 * tambem recusaria a pergunta sem base.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@EnabledIfDockerAvailable
@DisplayName("Slice 6: recomendacoes fundamentadas na analise")
class RecomendacaoNoFluxoIT {

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
    @DisplayName("recomendacao cita a regra que a analise encontrou, e declara a procedencia")
    void recomendacaoEhFundamentada() {
        UUID analiseId = analisarDocumentoComProblema();

        ResponseEntity<Map> resposta = gerar(analiseId, null);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resposta.getBody()).isNotNull();
        assertThat(resposta.getBody().get("procedencia"))
                .as("FIXTURE diz, em uma palavra, que nenhum modelo foi consultado — "
                        + "sem isso o consumidor acreditaria em IA onde ha fixture")
                .isEqualTo("FIXTURE");
        assertThat(resposta.getBody().toString())
                .contains("IMAGEM_SEM_TEXTO_ALTERNATIVO")
                .contains("1.1.1");
    }

    @Test
    @DisplayName("CRITERIO DO §7: pergunta sem base na analise e recusada")
    void perguntaSemBaseEhRecusada() {
        UUID analiseId = analisarDocumentoComProblema();

        // A analise encontrou 1.1.1. Contraste (1.4.3) nunca foi verificado
        // neste documento — a regra nem existe ainda no Rule Engine.
        ResponseEntity<Map> resposta = gerar(analiseId, "por que o contraste 1.4.3 falhou?");

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(resposta.getBody()).isNotNull();
        assertThat(resposta.getBody().get("codigo")).isEqualTo("SEM_FUNDAMENTO_NA_ANALISE");
        assertThat(resposta.getBody().get("mensagem").toString())
                .contains("1.4.3")
                .contains("so responde sobre o que mediu");
    }

    @Test
    @DisplayName("pergunta sobre o que a analise ENCONTROU passa")
    void perguntaComBasePassa() {
        UUID analiseId = analisarDocumentoComProblema();

        assertThat(gerar(analiseId, "como corrijo o 1.1.1?").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("gerar duas vezes nao gera duas vezes")
    void geracaoEhIdempotente() {
        UUID analiseId = analisarDocumentoComProblema();

        Map primeira = gerar(analiseId, null).getBody();
        Map segunda = gerar(analiseId, null).getBody();

        // Com provider pago, regenerar cobraria duas vezes pelo mesmo documento;
        // com qualquer provider generativo, o texto mudaria entre as chamadas.
        assertThat(segunda).isEqualTo(primeira);
    }

    @Test
    @DisplayName("o GET le o que foi gravado, sem chamar a IA")
    void getLeOGravado() {
        UUID analiseId = analisarDocumentoComProblema();
        gerar(analiseId, null);

        ResponseEntity<Map> lido = http.get()
                .uri("/analyses/" + analiseId + "/recommendations")
                .retrieve()
                .toEntity(Map.class);

        assertThat(lido.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lido.getBody().toString()).contains("IMAGEM_SEM_TEXTO_ALTERNATIVO");
    }

    @Test
    @DisplayName("documento sem problema nenhum nao rende recomendacao")
    void documentoLimpoNaoRendeRecomendacao() {
        // Pedir a um LLM que fale sobre resultado limpo produz conselho generico
        // apresentado como analise deste documento.
        UUID analiseId = analisar(pacote()
                .comCorpo(tituloPorEstilo(1, "Documento"))
                .comTitulo("Documento")
                .comIdiomaPadrao("pt-BR")
                .bytes(), "limpo.docx");

        ResponseEntity<Map> resposta = gerar(analiseId, null);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(resposta.getBody().get("codigo")).isEqualTo("SEM_FUNDAMENTO_NA_ANALISE");
    }

    @Test
    @DisplayName("recomendacao de outro usuario responde 404")
    void recomendacaoDeOutroUsuario() {
        UUID analiseId = analisarDocumentoComProblema();
        gerar(analiseId, null);

        RestClient outro = RestClient.builder()
                .baseUrl("http://localhost:" + porta)
                .defaultHeader("Authorization", "Bearer " + TokenDeTeste.novaConta(porta))
                .defaultStatusHandler(status -> true, (requisicao, resposta) -> { })
                .build();

        assertThat(outro.get().uri("/analyses/" + analiseId + "/recommendations")
                .retrieve().toEntity(String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ------------------------------------------------------------------

    private ResponseEntity<Map> gerar(UUID analiseId, String pergunta) {
        var pedido = http.post().uri("/analyses/" + analiseId + "/recommendations")
                .contentType(MediaType.APPLICATION_JSON);
        return (pergunta == null
                ? pedido.body(Map.of())
                : pedido.body(Map.of("pergunta", pergunta)))
                .retrieve().toEntity(Map.class);
    }

    private UUID analisarDocumentoComProblema() {
        // Imagem sem alt: a regra mais barata do Rule Engine, e a que garante
        // pelo menos um achado para a recomendacao se apoiar.
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
