package dev.accessai.integracao.ml;

import dev.accessai.config.PropriedadesAccessAi;
import dev.accessai.correlacao.Correlacao;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Chama o ML Service para classificar a qualidade de um texto alternativo.
 *
 * <p><b>Este cliente nunca lanca.</b> Falha, timeout ou resposta malformada
 * viram {@link RespostaMlDTO#indisponivel()}, e o motor de regras segue sozinho.
 * E a ordem de precedencia do CONTRIBUTING.md secao 2: ML e camada de
 * interpretacao sobre o Rule Engine, nunca substituto dele. A chamada acontece
 * dentro do consumo de uma mensagem do Kafka; se a indisponibilidade do Python
 * pudesse lancar, ela derrubaria o processamento da mensagem e a camada
 * opcional teria virado dependencia dura.
 *
 * <p>Os timeouts sao curtos pelo mesmo motivo: esperar por um servico degradado
 * segura a particao e atrasa todas as mensagens atras dela. Analise sem
 * predicao agora vale mais que analise com predicao daqui a quinze segundos.
 *
 * <p><b>Existe heuristica local, e ela e o ultimo degrau.</b> Ate a Slice 5 nao
 * havia: o Python fora do ar significava zero classificacao. Hoje
 * {@link HeuristicaDeAltLocal} responde no lugar, com a MESMA marca de
 * procedencia que o servico usa — {@code usouHeuristica = true},
 * {@code confianca = null}. A duplicacao da regra em duas linguagens e mantida
 * honesta pelo corpus de contrato em {@code docs/ml/heuristica-alt.golden.json},
 * que os dois lados sao obrigados a reproduzir.
 *
 * <p>O correlationId sai do MDC e vai no cabecalho: sem ele, o log do lado
 * Python nao tem como ser cruzado com o desta jornada. {@code Correlacao.atual()}
 * GRAVA no MDC o id que gera quando nao ha nenhum — de proposito, para que as
 * linhas de log seguintes desta thread carreguem o mesmo id.
 */
@Component
public class ClienteMlService {

    private static final Logger log = LoggerFactory.getLogger(ClienteMlService.class);

    static final String CAMINHO_DA_PREDICAO = "/v1/predict";
    static final String CAMINHO_DO_LOTE = "/v1/predict:batch";

    private final RestClient http;
    private final HeuristicaDeAltLocal heuristicaLocal;

    public ClienteMlService(RestClient.Builder construtor,
                            @NonNull PropriedadesAccessAi propriedades,
                            @NonNull HeuristicaDeAltLocal heuristicaLocal) {
        this.heuristicaLocal = heuristicaLocal;
        PropriedadesAccessAi.MlService configuracao = propriedades.mlService();
        this.http = construtor
                .baseUrl(configuracao.url())
                .requestFactory(fabricaCom(configuracao))
                .build();
    }

    /**
     * Fabrica com os dois timeouts separados.
     *
     * <p>No Spring 7 as classes utilitarias do Boot 3 para isso
     * ({@code ClientHttpRequestFactories}, {@code ClientHttpRequestFactorySettings})
     * nao existem mais. O timeout de CONEXAO mora no {@link HttpClient} do JDK;
     * o de LEITURA, na fabrica.
     *
     * <p>Os dois separados nao e preciosismo: servico fora do ar recusa a
     * conexao rapido, enquanto servico vivo e travado so aparece na leitura. Um
     * timeout unico obrigaria a escolher entre demorar no primeiro caso ou
     * desistir cedo demais no segundo.
     */
    private static JdkClientHttpRequestFactory fabricaCom(
            PropriedadesAccessAi.MlService configuracao) {
        HttpClient clienteDoJdk = HttpClient.newBuilder()
                // HTTP/1.1 fixado. O padrao do JDK e HTTP/2, que em texto claro
                // tenta o upgrade h2c; o uvicorn so fala HTTP/1.1 e o corpo da
                // requisicao se perde na negociacao — o servico responde 422
                // "field required: body" e o cliente reporta indisponibilidade,
                // sintoma que nao aponta para a causa.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(configuracao.connectTimeoutMs()))
                .build();
        JdkClientHttpRequestFactory fabrica = new JdkClientHttpRequestFactory(clienteDoJdk);
        fabrica.setReadTimeout(Duration.ofMillis(configuracao.readTimeoutMs()));
        return fabrica;
    }

    /**
     * Classifica um texto alternativo. Devolve {@link RespostaMlDTO#indisponivel()}
     * em vez de lancar.
     *
     * <p>{@code ResourceAccessException} (timeout de leitura, conexao recusada,
     * DNS) e {@code HttpStatusCodeException} (4xx e 5xx) sao ambas
     * {@code RestClientException}: para o dominio, todas significam a mesma
     * coisa — nao ha predicao para esta analise.
     */
    public @NonNull RespostaMlDTO predizer(@NonNull RequisicaoMlDTO requisicao) {
        try {
            RespostaMlDTO resposta = http.post()
                    .uri(CAMINHO_DA_PREDICAO)
                    // Content-Type explicito: sem ele o RestClient nao escolhe o
                    // conversor de JSON, o corpo chega vazio e o servico
                    // responde 422.
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(Correlacao.CABECALHO, Correlacao.atual())
                    .body(requisicao)
                    .retrieve()
                    .body(RespostaMlDTO.class);

            if (resposta == null || resposta.categoria() == null) {
                return heuristicaPara(requisicao.altText(), "resposta sem categoria");
            }
            if (resposta.usouHeuristica()) {
                // Nao e erro, e informacao: o servico respondeu sem modelo
                // carregado. Sem este log, "o ML esta funcionando" viraria uma
                // crenca que ninguem confere.
                log.info("ml-service respondeu pela heuristica (sem modelo carregado)");
            }
            return resposta;

        } catch (RestClientException e) {
            return heuristicaPara(requisicao.altText(),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Classifica varios textos alternativos numa chamada so.
     *
     * <p>Quando o servico nao responde, ou responde com cardinalidade diferente
     * da pedida, o lote INTEIRO cai para {@link HeuristicaDeAltLocal}. Cair item
     * a item seria pior: sem identificador no pedido, um resultado a menos torna
     * impossivel saber qual imagem ficou de fora, e associar pela posicao
     * produziria predicao trocada — pior que predicao nenhuma.
     *
     * <p>Nunca devolve lista vazia por falha. Antes desta slice, indisponibilidade
     * significava zero classificacao e o usuario via um documento analisado pela
     * metade sem explicacao. Agora ele ve classificacao de regra, declarada como
     * tal em cada linha.
     */
    public @NonNull List<RespostaMlDTO> predizerLote(@NonNull List<String> alts) {
        if (alts.isEmpty()) {
            return List.of();
        }
        try {
            RespostaDeLoteMlDTO resposta = http.post()
                    .uri(CAMINHO_DO_LOTE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(Correlacao.CABECALHO, Correlacao.atual())
                    .body(new RequisicaoDeLoteMlDTO(
                            alts.stream().map(RequisicaoMlDTO::de).toList()))
                    .retrieve()
                    .body(RespostaDeLoteMlDTO.class);

            if (resposta == null || !resposta.completoPara(alts.size())) {
                return heuristicaPara(alts, "resposta incompleta para o lote de "
                        + alts.size());
            }
            if (resposta.resultados().stream().anyMatch(RespostaMlDTO::usouHeuristica)) {
                log.info("ml-service respondeu o lote pela heuristica dele "
                        + "(sem modelo carregado)");
            }
            return resposta.resultados();

        } catch (RestClientException e) {
            return heuristicaPara(alts,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * O ultimo degrau da degradacao: regra local, declarada como regra.
     *
     * <p>Nivel {@code warn} e nao {@code info} de proposito. Predicao vinda daqui
     * e sintoma de servico fora do ar, e some do radar se ficar no mesmo nivel
     * do caminho normal.
     */
    private List<RespostaMlDTO> heuristicaPara(List<String> alts, String motivo) {
        log.warn("FALLBACK: ml-service indisponivel, classificando {} alt(s) pela "
                + "heuristica LOCAL. Motivo: {}", alts.size(), motivo);
        return alts.stream().map(heuristicaLocal::predizer).toList();
    }

    private RespostaMlDTO heuristicaPara(String alt, String motivo) {
        log.warn("FALLBACK: ml-service indisponivel, classificando 1 alt pela "
                + "heuristica LOCAL. Motivo: {}", motivo);
        return heuristicaLocal.predizer(alt);
    }
}
