package dev.accessai.ia;

import java.time.Duration;
import java.time.YearMonth;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Teto mensal de gasto com LLM (D5).
 *
 * <p>"Teto: US$ 10/mes. Contador incremental no Redis alimentado pelo campo
 * `usage` de cada resposta da API." Ele existe agora, com o provider de fixture
 * respondendo custo zero, porque o dia de escrever o contador NAO e o dia em que
 * a primeira fatura chega.
 *
 * <p><b>O que acontece ao bater o teto:</b> nada quebra. A analise continua
 * inteira — Rule Engine e ML sao locais e gratuitos. So a secao de recomendacoes
 * responde `AI_BUDGET_EXHAUSTED`. E a arquitetura do §2: IA e enriquecimento
 * opcional, nunca caminho critico.
 *
 * <p><b>Falha ABERTA?</b> Nao. Aqui, ao contrario do rate limit, Redis fora do ar
 * BLOQUEIA a chamada paga. A assimetria e deliberada: liberar upload sem contar
 * custa CPU; liberar chamada de LLM sem contar custa dinheiro que ninguem
 * autorizou, e o D5 existe justamente para isso nao acontecer.
 */
@Component
public class ContadorDeGastoDeIa {

    private static final Logger log = LoggerFactory.getLogger(ContadorDeGastoDeIa.class);

    private static final String PREFIXO = "accessai:ia:gasto:";

    /** Guarda o contador por 40 dias: cobre o mes inteiro mais folga de virada. */
    private static final Duration RETENCAO = Duration.ofDays(40);

    private final StringRedisTemplate redis;
    private final long tetoEmCentavos;

    public ContadorDeGastoDeIa(StringRedisTemplate redis,
                               @Value("${accessai.ia.teto-mensal-em-centavos}") long teto) {
        this.redis = redis;
        this.tetoEmCentavos = teto;
    }

    /**
     * @throws OrcamentoEsgotadoException quando o mes ja alcancou o teto
     * @throws ContadorIndisponivelException quando nao da para SABER o gasto
     */
    public void conferir() {
        long gasto;
        try {
            gasto = gastoDoMes();
        } catch (RuntimeException e) {
            // Falha fechada, mas com a razao CERTA. Dizer "orcamento esgotado"
            // aqui seria mentir: o orcamento pode estar intacto, o que falta e
            // como conferir. 503 e temporario e diz isso; 402 mandaria a pessoa
            // procurar dinheiro que talvez ela ja tenha.
            log.warn("contador de gasto INDISPONIVEL, chamada paga bloqueada: {}: {}",
                    e.getClass().getSimpleName(), e.getMessage());
            throw new ContadorIndisponivelException(e);
        }
        if (gasto >= tetoEmCentavos) {
            throw new OrcamentoEsgotadoException(gasto, tetoEmCentavos);
        }
    }

    /** Soma o custo de uma chamada que ja aconteceu. Zero nao mexe no contador. */
    public void registrar(long centavos) {
        if (centavos <= 0) {
            return;
        }
        String chave = chaveDoMes();
        Long total = redis.opsForValue().increment(chave, centavos);
        if (total != null && total == centavos) {
            redis.expire(chave, RETENCAO);
        }
        if (total != null && total >= tetoEmCentavos) {
            log.warn("teto mensal de IA atingido: {} de {} centavos", total, tetoEmCentavos);
        }
    }

    public long gastoDoMes() {
        String valor = redis.opsForValue().get(chaveDoMes());
        return valor == null ? 0L : Long.parseLong(valor);
    }

    private static @NonNull String chaveDoMes() {
        return PREFIXO + YearMonth.now();
    }

    /**
     * Nao da para saber o gasto do mes. A chamada paga NAO acontece.
     *
     * <p>Assimetria deliberada com o rate limit de upload, que falha ABERTO:
     * liberar upload sem contar custa CPU; liberar chamada de LLM sem contar
     * custa dinheiro que ninguem autorizou.
     */
    public static class ContadorIndisponivelException extends RuntimeException {
        public ContadorIndisponivelException(Throwable causa) {
            super("nao foi possivel conferir o gasto do mes; a chamada de IA foi "
                    + "bloqueada por precaucao. A analise segue completa.", causa);
        }
    }

    public static class OrcamentoEsgotadoException extends RuntimeException {
        public OrcamentoEsgotadoException(long gasto, long teto) {
            super("orcamento de IA do mes esgotado: " + gasto + " de " + teto
                    + " centavos. A analise segue completa; so a recomendacao para.");
        }
    }
}
