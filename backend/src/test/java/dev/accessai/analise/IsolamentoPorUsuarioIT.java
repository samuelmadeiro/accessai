package dev.accessai.analise;

import static dev.accessai.analise.extracao.DocxDeTeste.pacote;
import static dev.accessai.analise.extracao.DocxDeTeste.tituloPorEstilo;
import static org.assertj.core.api.Assertions.assertThat;

import dev.accessai.analise.api.AnaliseDto;
import dev.accessai.apoio.SegredoDeTeste;
import dev.accessai.apoio.TokenDeTeste;
import java.util.UUID;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * O entregavel do D4, em forma de teste.
 *
 * <p>O ADR 0004 nao diz "o isolamento sera aplicado": diz que <b>a prova de
 * isolamento e um teste, nao uma afirmacao</b>, e nomeia exatamente este
 * cenario — o usuario A recebe 404 ao pedir a analise do usuario B. Enquanto
 * este arquivo nao existia, a decisao estava aceita e nao verificada.
 *
 * <p><b>404 e nao 403.</b> Responder "proibido" confirmaria que o recurso
 * existe, e um atacante que quer saber se um id e valido nao precisa de mais que
 * isso. O caminho de "nao e seu" e o mesmo de "nao existe", desde o repositorio:
 * `findByIdAndOwnerId` devolve vazio nos dois casos.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@EnabledIfDockerAvailable
@DisplayName("Slice 5A: isolamento por usuario")
class IsolamentoPorUsuarioIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    @DynamicPropertySource
    static void apontar(DynamicPropertyRegistry registro) {
        registro.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registro.add("accessai.jwt.segredo", SegredoDeTeste::valor);
    }

    @LocalServerPort
    private int porta;

    @Test
    @DisplayName("o usuario B recebe 404 na analise do usuario A")
    void analiseDeOutroUsuarioNaoExiste() {
        RestClient a = clienteDe(TokenDeTeste.novaConta(porta));
        RestClient b = clienteDe(TokenDeTeste.novaConta(porta));

        UUID analiseDeA = enviar(a);

        assertThat(buscar(a, analiseDeA).getStatusCode())
                .as("o dono le a propria analise")
                .isEqualTo(HttpStatus.OK);
        assertThat(buscar(b, analiseDeA).getStatusCode())
                .as("404, nao 403: 403 confirmaria que o id existe")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("sem token, a rota de analise responde 401")
    void semTokenNaoPassa() {
        RestClient anonimo = RestClient.builder()
                .baseUrl("http://localhost:" + porta)
                .defaultStatusHandler(status -> true, (requisicao, resposta) -> { })
                .build();

        ResponseEntity<String> resposta = anonimo.get()
                .uri("/analyses/" + UUID.randomUUID())
                .retrieve()
                .toEntity(String.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("token assinado com outro segredo e recusado")
    void tokenForjadoNaoPassa() {
        // Um JWT bem formado mas assinado com outra chave. Se este teste falhar,
        // a validacao de assinatura parou de acontecer — e o `sub` passa a ser
        // um campo que qualquer pessoa escreve.
        String forjado = "eyJhbGciOiJIUzI1NiJ9."
                + "eyJzdWIiOiIwMDAwMDAwMC0wMDAwLTAwMDAtMDAwMC0wMDAwMDAwMDAwMDEifQ."
                + "assinatura-invalida";
        ResponseEntity<String> resposta = clienteDe(forjado).get()
                .uri("/analyses/" + UUID.randomUUID())
                .retrieve()
                .toEntity(String.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ------------------------------------------------------------------

    private RestClient clienteDe(String token) {
        return RestClient.builder()
                .baseUrl("http://localhost:" + porta)
                .defaultHeader("Authorization", "Bearer " + token)
                // Neutralizado de proposito: este teste ASSERTA 401 e 404, e um
                // cliente que lanca em erro esconderia o que precisa ser visto.
                .defaultStatusHandler(status -> true, (requisicao, resposta) -> { })
                .build();
    }

    private UUID enviar(RestClient cliente) {
        byte[] docx = pacote()
                .comCorpo(tituloPorEstilo(1, "Documento"))
                .comTitulo("Documento")
                .comIdiomaPadrao("pt-BR")
                .bytes();

        MultiValueMap<String, Object> corpo = new LinkedMultiValueMap<>();
        corpo.add("file", new ByteArrayResource(docx) {
            @Override
            public String getFilename() {
                return "isolamento.docx";
            }
        });

        ResponseEntity<AnaliseDto.RespostaDeRecebimento> resposta = cliente.post()
                .uri("/analyses")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(corpo)
                .retrieve()
                .toEntity(AnaliseDto.RespostaDeRecebimento.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resposta.getBody()).isNotNull();
        return resposta.getBody().analiseId();
    }

    private ResponseEntity<String> buscar(RestClient cliente, UUID analiseId) {
        return cliente.get()
                .uri("/analyses/" + analiseId)
                .retrieve()
                .toEntity(String.class);
    }
}
