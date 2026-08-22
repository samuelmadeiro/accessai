package dev.accessai.integracao.ml;

import dev.accessai.config.PropriedadesAccessAi;
import dev.accessai.correlacao.Correlacao;
import java.net.http.HttpClient;
import java.time.Duration;
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
 * <p><b>Nao existe heuristica no lado Java.</b> A heuristica mora so no servico
 * Python; quando ele cai, nao ha classificacao nenhuma e a analise fica com o
 * que o Rule Engine ja produziu. Portar a heuristica para ca criaria a mesma
 * regra em duas linguagens, que divergem — foi o defeito da lista branca de
 * partes OOXML, corrigido na Slice 4.
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

    private final RestClient http;

    public ClienteMlService(RestClient.Builder construtor,
                            @NonNull PropriedadesAccessAi propriedades) {
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
                return indisponivel("resposta sem categoria");
            }
            if (resposta.usouHeuristica()) {
                // Nao e erro, e informacao: o servico respondeu sem modelo
                // carregado. Sem este log, "o ML esta funcionando" viraria uma
                // crenca que ninguem confere.
                log.info("ml-service respondeu pela heuristica (sem modelo carregado)");
            }
            return resposta;

        } catch (RestClientException e) {
            return indisponivel(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static RespostaMlDTO indisponivel(String motivo) {
        log.warn("FALLBACK: ml-service indisponivel, seguindo so com o motor de "
                + "regras local. Motivo: {}", motivo);
        return RespostaMlDTO.indisponivel();
    }
}
