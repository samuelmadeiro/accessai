package dev.accessai.analise;

import static dev.accessai.analise.extracao.DocxDeTeste.cabecalhoDePagina;
import static dev.accessai.analise.extracao.DocxDeTeste.caixaDeTexto;
import static dev.accessai.analise.extracao.DocxDeTeste.imagemAncorada;
import static dev.accessai.analise.extracao.DocxDeTeste.imagemInline;
import static dev.accessai.analise.extracao.DocxDeTeste.link;
import static dev.accessai.analise.extracao.DocxDeTeste.pacote;
import static dev.accessai.analise.extracao.DocxDeTeste.tabela;
import static dev.accessai.analise.extracao.DocxDeTeste.tituloPorEstilo;
import static org.assertj.core.api.Assertions.assertThat;

import dev.accessai.apoio.SegredoDeTeste;
import dev.accessai.apoio.TokenDeTeste;
import dev.accessai.analise.api.AnaliseDto;
import dev.accessai.analise.extracao.DocxDeTeste;
import java.time.Duration;
import java.time.Instant;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Teste ponta a ponta: upload HTTP, evento no Kafka, regras executadas,
 * problemas no Postgres, resultado e score no GET.
 *
 * <p>Nao ha mock de infraestrutura: Postgres e Kafka sao containers reais. Um
 * teste que substitui o broker por um mock nao prova que o contrato do topico
 * funciona.
 *
 * <p>Os documentos sao montados em memoria por {@link DocxDeTeste}, e nao
 * carregados de fixtures binarias. Um {@code .docx} commitado e um zip que
 * ninguem revisa: aqui o XML que produz cada problema esta a vista, ao lado da
 * assercao. A contrapartida continua registrada — sao pacotes sinteticos, e a
 * validacao com exports reais de Word, Google Docs e LibreOffice segue pendente.
 *
 * <p>{@code @EnabledIfDockerAvailable} faz a suite ser PULADA, e nao quebrada,
 * quando nao ha Docker na maquina. Contrapartida assumida: teste pulado nao
 * protege ninguem, entao o CI precisa garantir Docker e tratar suite pulada como
 * falha.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@EnabledIfDockerAvailable
@DisplayName("Slice 2: upload ate GET com score")
class AnaliseFluxoCompletoIT {

    private static final Duration LIMITE_DE_ESPERA = Duration.ofSeconds(60);
    private static final Duration INTERVALO_DE_SONDAGEM = Duration.ofMillis(250);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    /**
     * O Postgres entra por @ServiceConnection, o Kafka nao: no Boot 4 a fabrica
     * de ConnectionDetails do Kafka nao esta em spring-boot-testcontainers, e o
     * contexto falharia com ConnectionDetailsNotFound.
     */
    @DynamicPropertySource
    static void apontarKafka(DynamicPropertyRegistry registro) {
        registro.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        // Sem segredo o contexto nao sobe — e proposital: um padrao de
        // desenvolvimento aqui viraria segredo publicado no GitHub.
        registro.add("accessai.jwt.segredo", SegredoDeTeste::valor);
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
                // Toda rota de analise exige autenticacao desde a Slice 5A.
                .defaultHeader("Authorization", "Bearer " + TokenDeTeste.novaConta(porta))
                .defaultStatusHandler(status -> true, (requisicao, resposta) -> { })
                .build();
    }

    @Test
    @DisplayName("documento que viola cinco regras devolve os problemas e o score por categoria")
    void documentoComVariosProblemas() {
        byte[] docx = pacote()
                .comCorpo(
                        // 1.1.1 Perceptivel: imagem sem descr
                        imagemInline("brasao.png", null),
                        // 1.3.1 Perceptivel: H1 direto para H3
                        tituloPorEstilo(1, "Edital"),
                        tituloPorEstilo(3, "Anexo I"),
                        // 1.3.1 Perceptivel: tabela sem linha de cabecalho
                        tabela(4, false),
                        // 2.4.4 Operavel: texto de link generico
                        link("rId7", "clique aqui"))
                .comLinksExternos(Map.of("rId7", "https://prefeitura.gov.br/edital.pdf"))
                // 2.4.2 Operavel: dc:title ausente
                .comPropriedadesSemTitulo()
                // 3.1.1 Compreensivel: nenhum w:lang
                .comEstilosSemIdioma()
                .bytes();

        AnaliseDto.RespostaDeAnalise resultado = analisar(docx, "edital-com-problemas.docx");

        assertThat(resultado.situacao()).isEqualTo("CONCLUIDA");
        assertThat(resultado.problemas())
                .extracting(AnaliseDto.ProblemaEncontrado::regraId)
                .containsExactlyInAnyOrder(
                        "IMAGEM_SEM_TEXTO_ALTERNATIVO",
                        "TABELA_SEM_CABECALHO",
                        "ORDEM_HIERARQUICA_CABECALHOS",
                        "TITULO_AUSENTE",
                        "LINK_SEM_TEXTO_DESCRITIVO",
                        "IDIOMA_NAO_DECLARADO");

        // Perceptivel: ALTA 15 (imagem) + ALTA 15 (tabela) + MEDIA 8 (hierarquia) = 38 -> 62
        // Operavel:    MEDIA 8 (titulo) + MEDIA 8 (link)                          = 16 -> 84
        // Compreensivel: ALTA 15 (idioma)                                         = 15 -> 85
        // Global com pesos iguais: (62 + 84 + 85) / 3 = 77
        AnaliseDto.ScoreDoDocumento score = resultado.score();
        assertThat(score.global()).isEqualTo(77);
        assertThat(score.categorias())
                .extracting(AnaliseDto.CategoriaDoScore::principio,
                        AnaliseDto.CategoriaDoScore::score,
                        AnaliseDto.CategoriaDoScore::problemas)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("PERCEPTIVEL", 62, 3),
                        org.assertj.core.groups.Tuple.tuple("OPERAVEL", 84, 2),
                        org.assertj.core.groups.Tuple.tuple("COMPREENSIVEL", 85, 1));
        assertThat(score.naoAvaliados())
                .as("nenhuma regra verifica 4.x; dizer isso e parte da resposta")
                .containsExactly("ROBUSTO");
    }

    @Test
    @DisplayName("documento acessivel tira 100 e nao gera nenhum falso positivo")
    void documentoAcessivelTira100() {
        byte[] docx = pacote()
                .comCorpo(
                        tituloPorEstilo(1, "Edital de Fomento 3/2026"),
                        tituloPorEstilo(2, "Objeto"),
                        imagemInline("grafico.png", "Grafico da distribuicao de recursos"),
                        imagemInline("linha.png", ""),
                        caixaDeTexto("Caixa de Texto 1", "Prazo ate 30 de setembro"),
                        tabela(3, true),
                        link("rId7", "Anexo I - modelo de declaracao"))
                .comLinksExternos(Map.of("rId7", "https://prefeitura.gov.br/anexo-i.docx"))
                .comTitulo("Edital de Fomento 3/2026")
                .comIdiomaPadrao("pt-BR")
                .bytes();

        AnaliseDto.RespostaDeAnalise resultado = analisar(docx, "edital-acessivel.docx");

        assertThat(resultado.totalDeProblemas())
                .as("imagem decorativa, caixa de texto e link descritivo nao sao problema")
                .isZero();
        assertThat(resultado.score().global()).isEqualTo(100);
        assertThat(resultado.score().categorias()).allSatisfy(
                c -> assertThat(c.score()).isEqualTo(100));
    }

    @Test
    @DisplayName("imagem so no cabecalho tambem e analisada")
    void imagemNoCabecalhoEhEncontrada() {
        byte[] docx = documentoConforme()
                .com("word/header2.xml", cabecalhoDePagina(imagemAncorada("brasao.png", null)))
                .bytes();

        AnaliseDto.RespostaDeAnalise resultado = analisar(docx, "com-cabecalho.docx");

        assertThat(resultado.problemas())
                .as("quem le apenas word/document.xml devolve zero aqui")
                .singleElement()
                .satisfies(p -> {
                    assertThat(p.regraId()).isEqualTo("IMAGEM_SEM_TEXTO_ALTERNATIVO");
                    assertThat(p.criterioWcag()).isEqualTo("1.1.1");
                    assertThat(p.nivelWcag()).isEqualTo("A");
                    assertThat(p.partePacote()).isEqualTo("word/header2.xml");
                    assertThat(p.evidencia()).contains("brasao.png");
                });
        assertThat(resultado.score().global()).isEqualTo(95);
    }

    @Test
    @DisplayName("documento que quebra no parsing termina em FALHOU e sem score")
    void documentoQueQuebraNoParsingTerminaEmFalhou() {
        // Pacote que passa na validacao (tem PK, [Content_Types].xml e
        // word/document.xml) e so quebra quando o extrator le o XML.
        byte[] docx = pacote().com("word/document.xml", "<w:document><w:body>").bytes();

        AnaliseDto.RespostaDeAnalise resultado = analisar(docx, "quebrado.docx");

        assertThat(resultado.situacao()).isEqualTo("FALHOU");
        assertThat(resultado.totalDeProblemas()).isZero();
        assertThat(resultado.score().global())
                .as("documento que ninguem conseguiu processar nao recebe nota")
                .isNull();
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

    // ------------------------------------------------------------------

    /** Pacote sem nenhum problema, para o teste isolar a violacao que quer. */
    private static DocxDeTeste documentoConforme() {
        return pacote()
                .comCorpo(tituloPorEstilo(1, "Documento"))
                .comTitulo("Documento")
                .comIdiomaPadrao("pt-BR");
    }

    private AnaliseDto.RespostaDeAnalise analisar(byte[] docx, String nomeArquivo) {
        ResponseEntity<AnaliseDto.RespostaDeRecebimento> resposta = postar(
                multipart(docx, nomeArquivo), AnaliseDto.RespostaDeRecebimento.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resposta.getBody()).isNotNull();
        assertThat(resposta.getBody().situacao()).isEqualTo("RECEBIDA");
        return aguardarConclusao(resposta.getBody().analiseId());
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
     * Sonda o GET ate a analise chegar a um estado terminal.
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
