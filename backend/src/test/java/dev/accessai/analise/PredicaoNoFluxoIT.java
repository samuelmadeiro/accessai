package dev.accessai.analise;

import static dev.accessai.analise.extracao.DocxDeTeste.imagemInline;
import static dev.accessai.analise.extracao.DocxDeTeste.pacote;
import static dev.accessai.analise.extracao.DocxDeTeste.tituloPorEstilo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpServer;
import dev.accessai.analise.api.AnaliseDto;
import dev.accessai.analise.dominio.AnaliseRepository;
import dev.accessai.analise.dominio.SituacaoAnalise;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A predicao de qualidade de alt dentro do fluxo real de analise.
 *
 * <p>O ML Service e um {@link HttpServer} do JDK no lugar do container Python:
 * o que precisa estar provado aqui e a FIACAO — que o backend chama, persiste e
 * devolve, e que a indisponibilidade nao derruba a analise. Subir o Python de
 * verdade tornaria o teste lento sem provar mais nada; o contrato entre os dois
 * ja e coberto por `ClienteMlServiceTest` e pela suite Python.
 *
 * <p>O servidor responde de forma controlada por {@link #resposta}, o que
 * permite ligar e desligar o ML no meio da suite.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@EnabledIfDockerAvailable
@DisplayName("Slice 5: predicao de alt no fluxo da analise")
class PredicaoNoFluxoIT {

    private static final Duration LIMITE = Duration.ofSeconds(60);
    private static final Duration SONDAGEM = Duration.ofMillis(250);

    @org.testcontainers.junit.jupiter.Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15");

    @org.testcontainers.junit.jupiter.Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    /** Corpo que o ML falso devolve; null derruba a conexao. */
    static final AtomicReference<String> resposta = new AtomicReference<>();
    static final AtomicInteger chamadas = new AtomicInteger();
    static HttpServer mlFalso;

    static {
        try {
            mlFalso = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            // Dois contextos, um comportamento: o fluxo usa o LOTE desde a
            // Slice 5, e o endpoint de item unico segue servido porque o
            // contrato dele continua valendo para quem chamar direto.
            com.sun.net.httpserver.HttpHandler manipulador = troca -> {
                chamadas.incrementAndGet();
                troca.getRequestBody().readAllBytes();
                String corpo = resposta.get();
                if (corpo == null) {
                    troca.sendResponseHeaders(503, -1);
                    troca.close();
                    return;
                }
                if (troca.getRequestURI().getPath().endsWith(":batch")) {
                    corpo = "{\"resultados\":[" + corpo + "]}";
                }
                byte[] bytes = corpo.getBytes(StandardCharsets.UTF_8);
                troca.getResponseHeaders().add("Content-Type", "application/json");
                troca.sendResponseHeaders(200, bytes.length);
                try (OutputStream saida = troca.getResponseBody()) {
                    saida.write(bytes);
                }
            };
            mlFalso.createContext("/v1/predict", manipulador);
            mlFalso.createContext("/v1/predict:batch", manipulador);
            mlFalso.start();
        } catch (IOException e) {
            throw new IllegalStateException("nao subiu o ml falso", e);
        }
    }

    @AfterAll
    static void derrubarMlFalso() {
        mlFalso.stop(0);
    }

    @DynamicPropertySource
    static void configurar(DynamicPropertyRegistry registro) {
        registro.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registro.add("accessai.ml-service.url",
                () -> "http://127.0.0.1:" + mlFalso.getAddress().getPort());
    }

    @LocalServerPort
    private int porta;

    @Autowired
    private AnaliseRepository analiseRepository;

    private RestClient http;

    @BeforeEach
    void preparar() {
        http = RestClient.builder()
                .baseUrl("http://localhost:" + porta)
                .defaultStatusHandler(status -> true, (requisicao, res) -> { })
                .build();
        chamadas.set(0);
        resposta.set("""
                {"categoria":"WEAK","confianca":null,"modeloVersao":null,
                "usouHeuristica":true}""");
    }

    @Test
    @DisplayName("imagem COM alt vira predicao no resultado, e o score nao muda")
    void imagemComAltViraPredicao() {
        UUID analiseId = enviar(documentoComAltPreenchido(), "com-alt.docx");

        AnaliseDto.RespostaDeAnalise resultado = aguardarConclusao(analiseId);

        assertThat(resultado.predicoesDeAlt()).singleElement().satisfies(predicao -> {
            assertThat(predicao.alt()).isEqualTo("Logo");
            assertThat(predicao.categoria()).isEqualTo("WEAK");
            assertThat(predicao.usouHeuristica())
                    .as("regra apresentada como ML seria falsidade")
                    .isTrue();
            assertThat(predicao.confianca())
                    .as("heuristica nao tem probabilidade")
                    .isNull();
        });
        // O ponto do desenho: a predicao NAO entra na conta (CONTRIBUTING.md §6).
        assertThat(resultado.problemas())
                .as("predicao nao pode virar problema")
                .noneMatch(p -> p.regraId().contains("ALT_"));
        assertThat(resultado.score().categorias())
                .filteredOn(c -> c.principio().equals("PERCEPTIVEL"))
                .singleElement()
                .satisfies(c -> assertThat(c.score()).isEqualTo(100));
    }

    @Test
    @DisplayName("imagem SEM alt nao vira predicao: alt ausente e regra, nao ML")
    void imagemSemAltNaoViraPredicao() {
        UUID analiseId = enviar(documentoSemAlt(), "sem-alt.docx");

        AnaliseDto.RespostaDeAnalise resultado = aguardarConclusao(analiseId);

        assertThat(chamadas.get())
                .as("usar ML onde uma regra resolve viola o CONTRIBUTING.md §2")
                .isZero();
        assertThat(resultado.predicoesDeAlt()).isEmpty();
        assertThat(resultado.problemas())
                .anyMatch(p -> p.regraId().equals("IMAGEM_SEM_TEXTO_ALTERNATIVO"));
    }

    @Test
    @DisplayName("ml-service fora do ar: a analise conclui com a heuristica LOCAL")
    void mlForaDoArNaoDerrubaAAnalise() {
        resposta.set(null);   // o servidor passa a responder 503

        UUID analiseId = enviar(documentoComAltPreenchido(), "ml-fora.docx");

        AnaliseDto.RespostaDeAnalise resultado = aguardarConclusao(analiseId);

        // Ate a Slice 5 isto era uma lista vazia, e o usuario via um documento
        // analisado pela metade sem nenhuma explicacao. Agora a regra local
        // responde — e se declara como regra, que e o que separa degradacao
        // honesta de "ML que e if/else".
        assertThat(resultado.predicoesDeAlt()).singleElement().satisfies(predicao -> {
            assertThat(predicao.alt()).isEqualTo("Logo");
            assertThat(predicao.usouHeuristica()).isTrue();
            assertThat(predicao.confianca()).isNull();
            assertThat(predicao.modeloVersao()).isNull();
        });
        // Analise sem predicao, nao analise a menos: o score sai completo.
        // O documento e acessivel (titulo, idioma e alt preenchido), entao o
        // esperado e 100 e nenhum problema — a ausencia do ML nao muda nada
        // disso, que e exatamente o ponto.
        assertThat(resultado.score().global()).isEqualTo(100);
        assertThat(resultado.problemas()).isEmpty();
        assertThat(resultado.situacao()).isEqualTo("CONCLUIDA");
    }

    // ------------------------------------------------------------------

    private static byte[] documentoComAltPreenchido() {
        return pacote()
                .comCorpo(tituloPorEstilo(1, "Documento"), imagemInline("logo.png", "Logo"))
                .comTitulo("Documento")
                .comIdiomaPadrao("pt-BR")
                .bytes();
    }

    private static byte[] documentoSemAlt() {
        return pacote()
                .comCorpo(tituloPorEstilo(1, "Documento"), imagemInline("foto.png", null))
                .comTitulo("Documento")
                .comIdiomaPadrao("pt-BR")
                .bytes();
    }

    private UUID enviar(byte[] docx, String nomeArquivo) {
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
                .body(corpo)
                .retrieve()
                .toEntity(AnaliseDto.RespostaDeRecebimento.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resposta.getBody()).isNotNull();
        return resposta.getBody().analiseId();
    }

    private AnaliseDto.RespostaDeAnalise aguardarConclusao(UUID analiseId) {
        await().atMost(LIMITE).pollInterval(SONDAGEM).untilAsserted(() ->
                assertThat(analiseRepository.findById(analiseId).orElseThrow().getSituacao())
                        .isEqualTo(SituacaoAnalise.CONCLUIDA));

        return http.get()
                .uri("/analyses/{id}", analiseId)
                .retrieve()
                .toEntity(AnaliseDto.RespostaDeAnalise.class)
                .getBody();
    }
}
