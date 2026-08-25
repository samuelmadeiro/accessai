package dev.accessai.analise;

import static dev.accessai.analise.extracao.DocxDeTeste.pacote;
import static dev.accessai.analise.extracao.DocxDeTeste.tituloPorEstilo;
import static org.assertj.core.api.Assertions.assertThat;

import dev.accessai.apoio.SegredoDeTeste;
import dev.accessai.apoio.TokenDeTeste;
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
 * Rate limit de upload por usuario (D4), o ultimo item da Slice 5A.
 *
 * <p>O teto e reduzido para 2 nesta suite. Testar com os 30 de producao custaria
 * trinta uploads reais — cada um descompactando OOXML e passando pelo pipeline —
 * para provar uma regra de contagem.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@EnabledIfDockerAvailable
@DisplayName("Slice 5A: rate limit de upload por usuario")
class RateLimitDeUploadIT {

    private static final int TETO = 2;

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
        registro.add("accessai.rate-limit.uploads-por-janela", () -> TETO);
        registro.add("accessai.rate-limit.janela-segundos", () -> 3600);
    }

    @LocalServerPort
    private int porta;

    @Test
    @DisplayName("passar do teto responde 429 com Retry-After")
    void tetoRecusaComRetryAfter() {
        RestClient cliente = clienteDe(TokenDeTeste.novaConta(porta));

        for (int i = 1; i <= TETO; i++) {
            assertThat(enviar(cliente).getStatusCode())
                    .as("upload %d, dentro do teto", i)
                    .isEqualTo(HttpStatus.CREATED);
        }

        ResponseEntity<String> recusado = enviar(cliente);

        assertThat(recusado.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(recusado.getHeaders().getFirst("Retry-After"))
                .as("sem Retry-After, um cliente automatizado so pode adivinhar — "
                        + "e adivinhar e repetir imediatamente")
                .isEqualTo("3600");
        assertThat(recusado.getBody()).contains("LIMITE_DE_UPLOAD_EXCEDIDO");
    }

    @Test
    @DisplayName("o teto e por conta: outro usuario nao herda a contagem")
    void tetoEhPorConta() {
        RestClient primeiro = clienteDe(TokenDeTeste.novaConta(porta));
        for (int i = 0; i < TETO; i++) {
            enviar(primeiro);
        }
        assertThat(enviar(primeiro).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // Uma conta que estourou o teto nao pode bloquear as outras — seria
        // negacao de servico com uma conta gratuita.
        RestClient segundo = clienteDe(TokenDeTeste.novaConta(porta));

        assertThat(enviar(segundo).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // ------------------------------------------------------------------

    private RestClient clienteDe(String token) {
        return RestClient.builder()
                .baseUrl("http://localhost:" + porta)
                .defaultHeader("Authorization", "Bearer " + token)
                .defaultStatusHandler(status -> true, (requisicao, resposta) -> { })
                .build();
    }

    private ResponseEntity<String> enviar(RestClient cliente) {
        byte[] docx = pacote()
                .comCorpo(tituloPorEstilo(1, "Documento"))
                .comTitulo("Documento")
                .comIdiomaPadrao("pt-BR")
                .bytes();

        MultiValueMap<String, Object> corpo = new LinkedMultiValueMap<>();
        corpo.add("file", new ByteArrayResource(docx) {
            @Override
            public String getFilename() {
                return "rate-limit.docx";
            }
        });

        return cliente.post()
                .uri("/analyses")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(corpo)
                .retrieve()
                .toEntity(String.class);
    }
}
