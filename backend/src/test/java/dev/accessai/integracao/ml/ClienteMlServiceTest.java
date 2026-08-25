package dev.accessai.integracao.ml;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.accessai.config.PropriedadesAccessAi;
import dev.accessai.correlacao.Correlacao;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;

/**
 * Testes do cliente do ML Service.
 *
 * <p>O servidor e um {@link HttpServer} do proprio JDK, e nao
 * {@code MockRestServiceServer} nem WireMock. O que precisa estar provado aqui e
 * comportamento de REDE — timeout de leitura, conexao recusada, corpo enviado
 * de verdade — e {@code MockRestServiceServer} intercepta ANTES do socket:
 * ele nao teria pego nenhum dos dois defeitos que esta integracao ja teve
 * (falta de {@code Content-Type} e a negociacao h2c do HTTP/2). WireMock traria
 * uma dependencia nova para fazer o que o JDK ja faz.
 */
@DisplayName("ClienteMlService")
class ClienteMlServiceTest {

    private static final String CORPO_OK = """
            {"categoria":"INSUFFICIENT","confianca":null,"modeloVersao":null,
            "usouHeuristica":true}""";

    private static final String CORPO_COM_MODELO = """
            {"categoria":"GOOD","confianca":0.87,"modeloVersao":"0.1.0",
            "usouHeuristica":false}""";

    private HttpServer servidor;

    @AfterEach
    void derrubar() {
        Correlacao.limpar();
        if (servidor != null) {
            servidor.stop(0);
        }
    }

    private String subir(int status, String corpo, long atrasoMs) throws IOException {
        return subirComEspiao(status, corpo, atrasoMs, null, null);
    }

    private String subirComEspiao(int status, String corpo, long atrasoMs,
                                  AtomicReference<String> corpoRecebido,
                                  AtomicReference<String> cabecalhos) throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        com.sun.net.httpserver.HttpHandler manipulador = troca -> {
            if (corpoRecebido != null) {
                corpoRecebido.set(new String(troca.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8));
            }
            if (cabecalhos != null) {
                cabecalhos.set(troca.getRequestHeaders().getFirst(Correlacao.CABECALHO)
                        + "|" + troca.getRequestHeaders().getFirst("Content-Type"));
            }
            if (atrasoMs > 0) {
                try {
                    Thread.sleep(atrasoMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            responder(troca, status, corpo);
        };
        servidor.createContext(ClienteMlService.CAMINHO_DA_PREDICAO, manipulador);
        servidor.createContext(ClienteMlService.CAMINHO_DO_LOTE, manipulador);
        servidor.start();
        return "http://127.0.0.1:" + servidor.getAddress().getPort();
    }

    private static void responder(HttpExchange troca, int status, String corpo)
            throws IOException {
        byte[] bytes = corpo.getBytes(StandardCharsets.UTF_8);
        troca.getResponseHeaders().add("Content-Type", "application/json");
        troca.sendResponseHeaders(status, bytes.length);
        try (OutputStream saida = troca.getResponseBody()) {
            saida.write(bytes);
        }
    }

    private static ClienteMlService clientePara(String url, long readTimeoutMs) {
        PropriedadesAccessAi propriedades = new PropriedadesAccessAi(
                new PropriedadesAccessAi.Upload(DataSize.ofMegabytes(25)),
                new PropriedadesAccessAi.Kafka("t", 3, (short) 1,
                        new PropriedadesAccessAi.Kafka.Retry(4, 500, 2.0, 10_000)),
                new PropriedadesAccessAi.Score(
                        new PropriedadesAccessAi.Score.Pesos(25, 25, 25, 25),
                        new PropriedadesAccessAi.Score.Penalidades(25, 15, 8, 3)),
                new PropriedadesAccessAi.Outbox(500, 50, 2_000, 10_000, 10),
                new PropriedadesAccessAi.MlService(url, 500, readTimeoutMs),
                new PropriedadesAccessAi.RateLimit(30, 3600));
        return new ClienteMlService(RestClient.builder(), propriedades,
                new HeuristicaDeAltLocal());
    }

    private static RequisicaoMlDTO umaRequisicao() {
        return RequisicaoMlDTO.de("IMG_0421.jpg");
    }

    // ------------------------------------------------------------- 200 OK

    @Test
    @DisplayName("200 com heuristica: a resposta declara que veio de regra")
    void respostaComHeuristica() throws IOException {
        ClienteMlService cliente = clientePara(subir(200, CORPO_OK, 0), 1500);

        RespostaMlDTO resposta = cliente.predizer(umaRequisicao());

        assertThat(resposta.temPredicao()).isTrue();
        assertThat(resposta.categoria()).isEqualTo("INSUFFICIENT");
        assertThat(resposta.usouHeuristica())
                .as("regra apresentada como predicao de ML seria falsidade")
                .isTrue();
        assertThat(resposta.confianca())
                .as("heuristica nao tem probabilidade")
                .isNull();
    }

    @Test
    @DisplayName("200 com modelo: traz confianca e versao")
    void respostaComModelo() throws IOException {
        ClienteMlService cliente = clientePara(subir(200, CORPO_COM_MODELO, 0), 1500);

        RespostaMlDTO resposta = cliente.predizer(umaRequisicao());

        assertThat(resposta.categoria()).isEqualTo("GOOD");
        assertThat(resposta.confianca()).isEqualTo(0.87);
        assertThat(resposta.modeloVersao()).isEqualTo("0.1.0");
        assertThat(resposta.usouHeuristica()).isFalse();
    }

    // --------------------------------------------------------- fallback

    @Test
    @DisplayName("500 do servico aciona o fallback sem lancar")
    void erroDoServidorAcionaFallback() throws IOException {
        ClienteMlService cliente = clientePara(
                subir(500, "{\"erro\":\"quebrou\"}", 0), 1500);

        RespostaMlDTO resposta = cliente.predizer(umaRequisicao());

        assertThat(resposta.temPredicao())
                .as("o fallback agora e a heuristica local, nao ausencia de predicao")
                .isTrue();
        assertThat(resposta.usouHeuristica()).isTrue();
        assertThat(resposta.categoria()).isEqualTo("INSUFFICIENT");
    }

    @Test
    @DisplayName("timeout de leitura aciona o fallback sem lancar")
    void timeoutAcionaFallback() throws IOException {
        // Servidor vivo e lento — o caso que o timeout de conexao nao pega.
        ClienteMlService cliente = clientePara(subir(200, CORPO_OK, 1_500), 200);

        RespostaMlDTO resposta = cliente.predizer(umaRequisicao());

        assertThat(resposta.temPredicao())
                .as("o fallback agora e a heuristica local, nao ausencia de predicao")
                .isTrue();
        assertThat(resposta.usouHeuristica()).isTrue();
        assertThat(resposta.categoria()).isEqualTo("INSUFFICIENT");
    }

    @Test
    @DisplayName("servico fora do ar aciona o fallback sem lancar")
    void servicoForaDoArAcionaFallback() {
        ClienteMlService cliente = clientePara("http://127.0.0.1:1", 1500);

        RespostaMlDTO resposta = cliente.predizer(umaRequisicao());

        assertThat(resposta.temPredicao())
                .as("o fallback agora e a heuristica local, nao ausencia de predicao")
                .isTrue();
        assertThat(resposta.usouHeuristica()).isTrue();
        assertThat(resposta.categoria()).isEqualTo("INSUFFICIENT");
    }

    @Test
    @DisplayName("corpo malformado aciona o fallback sem lancar")
    void corpoMalformadoAcionaFallback() throws IOException {
        ClienteMlService cliente = clientePara(subir(200, "isto nao e json", 0), 1500);

        RespostaMlDTO caida = cliente.predizer(umaRequisicao());
        assertThat(caida.usouHeuristica()).isTrue();
        assertThat(caida.categoria()).isEqualTo("INSUFFICIENT");
    }

    @Test
    @DisplayName("200 sem categoria aciona o fallback")
    void respostaSemCategoriaAcionaFallback() throws IOException {
        ClienteMlService cliente = clientePara(
                subir(200, "{\"usouHeuristica\":true}", 0), 1500);

        RespostaMlDTO caida = cliente.predizer(umaRequisicao());
        assertThat(caida.usouHeuristica()).isTrue();
        assertThat(caida.categoria()).isEqualTo("INSUFFICIENT");
    }

    // ------------------------------------------------------ contrato do fio

    @Test
    @DisplayName("o corpo enviado usa os nomes que o Python exige")
    void corpoEnviadoEstaNoContrato() throws IOException {
        // O schema do lado Python recusa campo desconhecido: renomear qualquer
        // um destes vira 422 na primeira chamada.
        AtomicReference<String> corpo = new AtomicReference<>();
        String url = subirComEspiao(200, CORPO_OK, 0, corpo, null);

        clientePara(url, 1500).predizer(umaRequisicao());

        assertThat(corpo.get())
                .isNotBlank()
                .contains("\"altText\"")
                .contains("\"contextoAntes\"")
                .contains("\"contextoDepois\"")
                .contains("IMG_0421.jpg");
    }

    @Test
    @DisplayName("campo novo vindo do Python nao quebra o cliente")
    void campoDesconhecidoNaRespostaEhTolerado() throws IOException {
        // Assimetria deliberada: o schema do Python recusa campo desconhecido
        // (servidor rigido), o cliente Java tolera (consumidor tolerante). Sem
        // este teste, ligar `fail-on-unknown-properties` quebraria o backend na
        // primeira versao nova do servico, sem nada avisar.
        String comCampoNovo = """
                {"categoria":"GOOD","confianca":0.5,"modeloVersao":"0.1.0",
                "usouHeuristica":false,"campoQueAindaNaoExiste":"futuro"}""";
        ClienteMlService cliente = clientePara(subir(200, comCampoNovo, 0), 1500);

        RespostaMlDTO resposta = cliente.predizer(umaRequisicao());

        assertThat(resposta.temPredicao()).isTrue();
        assertThat(resposta.categoria()).isEqualTo("GOOD");
    }

    @Test
    @DisplayName("o correlationId do MDC vai no cabecalho, e o tipo e JSON")
    void correlationIdDoMdcViaja() throws IOException {
        Correlacao.definir("jornada-4711");
        AtomicReference<String> cabecalhos = new AtomicReference<>();
        String url = subirComEspiao(200, CORPO_OK, 0, null, cabecalhos);

        clientePara(url, 1500).predizer(umaRequisicao());

        // Sem o id, o log do lado Python nao cruza com o desta jornada.
        assertThat(cabecalhos.get()).startsWith("jornada-4711|");
        assertThat(cabecalhos.get()).contains("application/json");
    }

    // ------------------------------------------------------- lote e fallback

    @Test
    @DisplayName("lote devolve um resultado por item, na ordem")
    void loteNaOrdem() throws IOException {
        String url = subir(200, """
                {"resultados":[
                  {"categoria":"GOOD","confianca":0.9,"modeloVersao":"0.1.0","usouHeuristica":false},
                  {"categoria":"WEAK","confianca":0.5,"modeloVersao":"0.1.0","usouHeuristica":false}]}""",
                0);

        List<RespostaMlDTO> respostas = clientePara(url, 1_500)
                .predizerLote(List.of("um alt qualquer", "outro alt"));

        assertThat(respostas).extracting(RespostaMlDTO::categoria)
                .containsExactly("GOOD", "WEAK");
    }

    @Test
    @DisplayName("servico fora do ar cai para a heuristica LOCAL, nao para lista vazia")
    void loteForaDoArUsaHeuristicaLocal() {
        // Antes da Slice 5 isto devolvia zero predicoes e o usuario via um
        // documento analisado pela metade, sem explicacao.
        List<RespostaMlDTO> respostas = clientePara("http://127.0.0.1:1", 1_500)
                .predizerLote(List.of("IMG_0421.jpg",
                        "Grafico de barras com a evolucao do orcamento entre 2020 e 2025"));

        assertThat(respostas).hasSize(2);
        assertThat(respostas).allSatisfy(r -> {
            assertThat(r.usouHeuristica())
                    .as("predicao de regra tem que se declarar como regra").isTrue();
            assertThat(r.confianca())
                    .as("regra nao tem probabilidade").isNull();
            assertThat(r.temPredicao()).isTrue();
        });
        assertThat(respostas).extracting(RespostaMlDTO::categoria)
                .containsExactly("INSUFFICIENT", "GOOD");
    }

    @Test
    @DisplayName("cardinalidade diferente da pedida cai para a heuristica local")
    void loteIncompletoUsaHeuristicaLocal() throws IOException {
        // Sem identificador no pedido, um resultado a menos torna impossivel
        // saber QUAL item ficou de fora. Associar por posicao daria predicao
        // trocada, que e pior que predicao nenhuma.
        String url = subir(200, """
                {"resultados":[
                  {"categoria":"GOOD","confianca":0.9,"modeloVersao":"0.1.0","usouHeuristica":false}]}""",
                0);

        List<RespostaMlDTO> respostas = clientePara(url, 1_500)
                .predizerLote(List.of("Selo", "Brasao"));

        assertThat(respostas).hasSize(2);
        assertThat(respostas).allMatch(RespostaMlDTO::usouHeuristica);
    }

    @Test
    @DisplayName("lote vazio nao chama o servico")
    void loteVazio() {
        assertThat(clientePara("http://127.0.0.1:1", 1_500).predizerLote(List.of()))
                .isEmpty();
    }

    @Test
    @DisplayName("item unico fora do ar tambem cai para a heuristica local")
    void itemUnicoForaDoArUsaHeuristicaLocal() {
        RespostaMlDTO resposta = clientePara("http://127.0.0.1:1", 1_500)
                .predizer(RequisicaoMlDTO.de("IMG_0421.jpg"));

        assertThat(resposta.categoria()).isEqualTo("INSUFFICIENT");
        assertThat(resposta.usouHeuristica()).isTrue();
        assertThat(resposta.confianca()).isNull();
    }
}
