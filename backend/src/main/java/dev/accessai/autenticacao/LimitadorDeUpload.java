package dev.accessai.autenticacao;

import dev.accessai.config.PropriedadesAccessAi;
import java.time.Duration;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Teto de uploads por usuario numa janela (D4).
 *
 * <p>Upload e a operacao cara do sistema: descompacta OOXML, roda seis regras,
 * chama o ML Service e, a partir da Slice 6, gasta orcamento de LLM. Sem teto,
 * uma conta sozinha consome o de todas.
 *
 * <p><b>Janela fixa, nao deslizante.</b> Janela fixa permite um pico no limite
 * entre duas janelas — ate o dobro do teto em torno da virada. Sabendo disso e
 * aceitando: a alternativa deslizante custa um sorted set por usuario e limpeza
 * periodica, e o que este limite protege e orcamento, nao integridade.
 *
 * <p><b>Falha ABERTA.</b> Redis fora do ar libera o upload, com aviso no log.
 * Falhar fechado transformaria uma protecao opcional em ponto unico de falha:
 * o sistema inteiro pararia de aceitar documento porque um contador nao
 * respondeu. E a mesma escolha que o ADR 0011 faz para o ML Service.
 */
@Component
public class LimitadorDeUpload {

    private static final Logger log = LoggerFactory.getLogger(LimitadorDeUpload.class);

    private static final String PREFIXO = "accessai:rate:upload:";

    private final StringRedisTemplate redis;
    private final int teto;
    private final Duration janela;

    public LimitadorDeUpload(StringRedisTemplate redis,
                             @NonNull PropriedadesAccessAi propriedades) {
        this.redis = redis;
        this.teto = propriedades.rateLimit().uploadsPorJanela();
        this.janela = Duration.ofSeconds(propriedades.rateLimit().janelaSegundos());
    }

    /**
     * Registra mais um upload da conta e recusa quando ela passa do teto.
     *
     * <p>{@code INCR} e depois {@code EXPIRE} apenas na PRIMEIRA contagem: pôr
     * expiracao a cada chamada renovaria a janela a cada upload, e um usuario
     * que manda um documento por minuto nunca veria o contador zerar.
     *
     * @throws LimiteDeUploadExcedidoException quando a conta passou do teto
     */
    public void registrar(@NonNull UUID usuarioId) {
        String chave = PREFIXO + usuarioId;
        Long contagem;
        try {
            contagem = redis.opsForValue().increment(chave);
            if (contagem != null && contagem == 1L) {
                redis.expire(chave, janela);
            }
        } catch (RuntimeException e) {
            // Falha ABERTA, e o aviso e o que impede isso de virar "o rate limit
            // esta ligado" — uma crenca que ninguem confere.
            log.warn("rate limit INDISPONIVEL, upload liberado sem contagem: {}: {}",
                    e.getClass().getSimpleName(), e.getMessage());
            return;
        }
        if (contagem != null && contagem > teto) {
            throw new LimiteDeUploadExcedidoException(teto, janela);
        }
    }

    public static class LimiteDeUploadExcedidoException extends RuntimeException {
        private final long esperarSegundos;

        public LimiteDeUploadExcedidoException(int teto, Duration janela) {
            super("limite de " + teto + " uploads a cada " + janela.toMinutes()
                    + " minuto(s) atingido");
            this.esperarSegundos = janela.toSeconds();
        }

        /** Vai para o cabecalho `Retry-After`, que e o que um cliente automatizado le. */
        public long esperarSegundos() {
            return esperarSegundos;
        }
    }
}
