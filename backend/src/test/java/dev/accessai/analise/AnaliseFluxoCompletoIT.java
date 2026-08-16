package dev.accessai.analise;

import static org.assertj.core.api.Assertions.assertThat;

import dev.accessai.analise.api.AnaliseDto;
import dev.accessai.analise.extracao.DocxDeTeste;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Teste ponta a ponta da Slice 1: upload HTTP, evento no Kafka, regra
 * executada, problema no Postgres, resultado no GET.
 *
 * <p>Nao ha mock de infraestrutura: Postgres e Kafka sao containers reais. Um
 * teste que substitui o broker por um mock nao prova que o contrato do topico
 * funciona, que e justamente o que esta slice precisa demonstrar.
 *
 * <p>{@code @EnabledIfDockerAvailable} faz a suite ser PULADA, e nao quebrada,
 * quando nao ha Docker na maquina. A diferenca importa: sem a anotacao o build
 * falha com um stack trace de "Could not find a valid Docker environment", que
 * parece defeito de codigo e nao ausencia de pre-requisito.
 *
 * <p>Contrapartida assumida: teste pulado nao protege ninguem. O pipeline de CI
 * precisa garantir Docker disponivel e tratar suite pulada como falha — senao
 * esta anotacao vira um jeito silencioso de nunca rodar o E2E.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@EnabledIfDockerAvailable
@DisplayName("Slice 1: upload ate GET")
class AnaliseFluxoCompletoIT {

    private static final Duration LIMITE_DE_ESPERA = Duration.ofSeconds(60);
    private static final Duration INTERVALO_DE_SONDAGEM = Duration.ofMillis(250);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    /**
     * O Postgres entra por @ServiceConnection, o Kafka nao.
     *
     * <p>No Boot 4 os starters foram modularizados e a fabrica de
     * ConnectionDetails do Kafka nao esta em spring-boot-testcontainers — com
     * @ServiceConnection o contexto falha com ConnectionDetailsNotFound.
     * Apontar a propriedade na mao e explicito e nao depende de descobrir qual
     * modulo publica a fabrica.
     */
    @DynamicPropertySource
    static void apontarKafka(DynamicPropertyRegistry registro) {
        registro.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @LocalServerPort
    private int porta;

    private RestClient http;

    @BeforeEach
    void prepararCliente() {
        // O tratador de status e neutralizado de proposito: este teste ASSERTA
        // codigos 4xx, entao um cliente que lanca excecao em erro esconderia
        // justamente o que precisa ser verificado.
        http = RestClient.builder()
                .baseUrl("http://localhost:" + porta)
                .defaultStatusHandler(status -> true, (requisicao, resposta) -> { })
                .build();
    }

    @Test
    @DisplayName("documento com imagem sem alt vira problema 1.1.1 consultavel")
    void fluxoCompletoComProblema() throws IOException {
        UUID analiseId = enviar("fixtures/imagem-sem-alt.docx");

        AnaliseDto.RespostaDeAnalise resultado = aguardarConclusao(analiseId);

        assertThat(resultado.situacao()).isEqualTo("CONCLUIDA");
        assertThat(resultado.tipoMimeDetectado())
                .isEqualTo("application/vnd.openxmlformats-officedocument."
                        + "wordprocessingml.document");
        assertThat(resultado.sha256()).hasSize(64);
        assertThat(resultado.correlationId()).isNotNull();
        assertThat(resultado.totalDeProblemas()).isEqualTo(1);

        AnaliseDto.ProblemaEncontrado problema = resultado.problemas().getFirst();
        assertThat(problema.regraId()).isEqualTo("IMAGEM_SEM_TEXTO_ALTERNATIVO");
        assertThat(problema.criterioWcag()).isEqualTo("1.1.1");
        assertThat(problema.nivelWcag()).isEqualTo("A");
        assertThat(problema.partePacote()).isEqualTo("word/document.xml");
        assertThat(problema.evidencia()).contains("foto.png");
    }

    @Test
    @DisplayName("imagem com alt preenchido nao gera problema")
    void documentoAcessivelNaoGeraProblema() throws IOException {
        UUID analiseId = enviar("fixtures/imagem-com-alt.docx");

        AnaliseDto.RespostaDeAnalise resultado = aguardarConclusao(analiseId);

        assertThat(resultado.situacao()).isEqualTo("CONCLUIDA");
        assertThat(resultado.totalDeProblemas())
                .as("alt preenchido nao pode virar problema; isso seria falso positivo")
                .isZero();
    }

    @Test
    @DisplayName("imagem so no cabecalho tambem e analisada")
    void imagemNoCabecalhoEhEncontrada() throws IOException {
        UUID analiseId = enviar("fixtures/imagem-no-cabecalho-sem-alt.docx");

        AnaliseDto.RespostaDeAnalise resultado = aguardarConclusao(analiseId);

        assertThat(resultado.totalDeProblemas())
                .as("quem le apenas word/document.xml devolve zero aqui")
                .isEqualTo(1);
        assertThat(resultado.problemas().getFirst().partePacote())
                .isEqualTo("word/header2.xml");
    }

    @Test
    @DisplayName("documento que quebra no parsing termina em FALHOU, nao preso em RECEBIDA")
    void documentoQueQuebraNoParsingTerminaEmFalhou() {
        // Pacote que passa na validacao (tem PK, [Content_Types].xml e
        // word/document.xml) e so quebra quando o extrator le o XML. Antes da
        // politica de falha isso deixava a analise em RECEBIDA para sempre.
        byte[] docx = DocxDeTeste.pacote()
                .com("word/document.xml", "<w:document><w:body>")
                .bytes();

        ResponseEntity<AnaliseDto.RespostaDeRecebimento> resposta = postar(
                multipart(docx, "quebrado.docx"), AnaliseDto.RespostaDeRecebimento.class);
        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resposta.getBody()).isNotNull();

        AnaliseDto.RespostaDeAnalise resultado = aguardarConclusao(resposta.getBody().analiseId());

        assertThat(resultado.situacao()).isEqualTo("FALHOU");
        assertThat(resultado.totalDeProblemas()).isZero();
    }

    @Test
    @DisplayName("caixa de texto sem alt nao vira problema")
    void caixaDeTextoNaoViraProblema() {
        // wp:docPr existe em qualquer desenho. Contar caixa de texto como
        // imagem gerava falso positivo em documento real.
        byte[] docx = DocxDeTeste.pacote()
                .comCorpo("<w:p><w:r><w:drawing><wp:inline>"
                        + "<wp:docPr id=\"1\" name=\"Caixa de Texto 2\"/>"
                        + "<a:graphic><a:graphicData uri=\"http://schemas.microsoft.com/office/"
                        + "word/2010/wordprocessingShape\"><wps:wsp><wps:txbx><w:txbxContent>"
                        + "<w:p><w:r><w:t>Prazo ate sexta</w:t></w:r></w:p>"
                        + "</w:txbxContent></wps:txbx></wps:wsp></a:graphicData></a:graphic>"
                        + "</wp:inline></w:drawing></w:r></w:p>")
                .bytes();

        ResponseEntity<AnaliseDto.RespostaDeRecebimento> resposta = postar(
                multipart(docx, "com-caixa-de-texto.docx"), AnaliseDto.RespostaDeRecebimento.class);
        assertThat(resposta.getBody()).isNotNull();

        AnaliseDto.RespostaDeAnalise resultado = aguardarConclusao(resposta.getBody().analiseId());

        assertThat(resultado.situacao()).isEqualTo("CONCLUIDA");
        assertThat(resultado.totalDeProblemas()).isZero();
    }

    @Test
    @DisplayName("conteudo que nao e DOCX e recusado com 422")
    void conteudoQueNaoEhDocxEhRecusado() {
        // Exatamente o caso encontrado na coleta do corpus real: HTTP 200
        // servindo HTML com nome terminado em .docx.
        byte[] html = "<!DOCTYPE html><html><body>nao sou um docx</body></html>"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        ResponseEntity<AnaliseDto.Erro> resposta = postar(
                multipart(html, "armadilha.docx"), AnaliseDto.Erro.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(resposta.getBody()).isNotNull();
        assertThat(resposta.getBody().codigo()).isEqualTo("DOCUMENTO_INVALIDO");
    }

    @Test
    @DisplayName("analise inexistente devolve 404")
    void analiseInexistenteDevolve404() {
        ResponseEntity<AnaliseDto.Erro> resposta = http.get()
                .uri("/analyses/{id}", UUID.randomUUID())
                .retrieve()
                .toEntity(AnaliseDto.Erro.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private UUID enviar(String recurso) throws IOException {
        byte[] conteudo;
        try (InputStream in = new ClassPathResource(recurso).getInputStream()) {
            conteudo = in.readAllBytes();
        }
        ResponseEntity<AnaliseDto.RespostaDeRecebimento> resposta = postar(
                multipart(conteudo, "documento.docx"), AnaliseDto.RespostaDeRecebimento.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resposta.getBody()).isNotNull();
        assertThat(resposta.getBody().situacao()).isEqualTo("RECEBIDA");
        return resposta.getBody().analiseId();
    }

    private <T> ResponseEntity<T> postar(MultiValueMap<String, Object> corpo, Class<T> tipo) {
        return http.post()
                .uri("/analyses")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(corpo)
                .retrieve()
                .toEntity(tipo);
    }

    private static MultiValueMap<String, Object> multipart(byte[] conteudo, String nomeArquivo) {
        MultiValueMap<String, Object> corpo = new LinkedMultiValueMap<>();
        corpo.add("file", new ByteArrayResource(conteudo) {
            @Override
            public String getFilename() {
                return nomeArquivo;
            }
        });
        return corpo;
    }

    /**
     * Sonda o GET ate a analise sair de RECEBIDA.
     *
     * <p>Sondagem em vez de {@code Thread.sleep} fixo: o tempo do consumidor
     * varia com a carga da maquina, e sleep fixo produz teste que passa aqui e
     * falha no CI.
     */
    private AnaliseDto.RespostaDeAnalise aguardarConclusao(UUID analiseId) {
        Instant limite = Instant.now().plus(LIMITE_DE_ESPERA);
        AnaliseDto.RespostaDeAnalise ultima = null;

        while (Instant.now().isBefore(limite)) {
            ResponseEntity<AnaliseDto.RespostaDeAnalise> resposta = http.get()
                    .uri("/analyses/{id}", analiseId)
                    .retrieve()
                    .toEntity(AnaliseDto.RespostaDeAnalise.class);
            assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
            ultima = resposta.getBody();

            if (ultima != null && ehTerminal(ultima.situacao())) {
                return ultima;
            }
            dormir();
        }
        throw new AssertionError("analise " + analiseId + " nao concluiu em " + LIMITE_DE_ESPERA
                + "; ultimo estado observado: " + ultima);
    }

    private static boolean ehTerminal(String situacao) {
        // PROCESSANDO nunca e commitado hoje, mas parar nele deixaria o teste
        // refem de um detalhe de transacao que a Slice 3 vai mexer.
        return "CONCLUIDA".equals(situacao) || "FALHOU".equals(situacao);
    }

    private static void dormir() {
        try {
            Thread.sleep(INTERVALO_DE_SONDAGEM);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("espera interrompida", e);
        }
    }
}
