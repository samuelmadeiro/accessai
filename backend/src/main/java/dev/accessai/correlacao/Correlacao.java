package dev.accessai.correlacao;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * O correlationId da jornada atual, guardado no MDC do SLF4J.
 *
 * <p>Uma jornada atravessa tres contextos — requisicao HTTP, linha do outbox e
 * consumidor Kafka — e cada um roda numa thread diferente. O MDC e o que faz o
 * mesmo identificador aparecer no log dos tres sem que nenhum metodo precise
 * receber o id como parametro.
 */
public final class Correlacao {

    public static final String CABECALHO = "X-Correlation-ID";
    public static final String CHAVE_MDC = "correlationId";

    /** Marca de "ja registrei a ponte literal-UUID nesta jornada". */
    static final String CHAVE_MDC_DERIVACAO_REGISTRADA = "correlationDerivada";

    private static final Logger log = LoggerFactory.getLogger(Correlacao.class);

    /**
     * Cabecalho vindo do cliente e entrada hostil como qualquer outra
     * (CONTRIBUTING.md secao 5). Sem esta validacao, um valor com quebra de
     * linha injeta linhas falsas no log — e log falsificado e pior que log
     * ausente, porque parece confiavel.
     */
    private static final Pattern ACEITAVEL = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private Correlacao() {
    }

    /** Aceita o id do cliente quando ele e utilizavel; senao gera um novo. */
    public static String normalizar(String recebido) {
        if (recebido != null && ACEITAVEL.matcher(recebido).matches()) {
            return recebido;
        }
        return UUID.randomUUID().toString();
    }

    public static void definir(String correlationId) {
        MDC.put(CHAVE_MDC, correlationId);
    }

    public static void limpar() {
        MDC.remove(CHAVE_MDC);
        MDC.remove(CHAVE_MDC_DERIVACAO_REGISTRADA);
    }

    /**
     * O id da jornada atual. Gera um novo quando nao ha nenhum: caminho que nao
     * passou por HTTP (um teste, um job) continua rastreavel.
     *
     * <p>O id gerado e GRAVADO no MDC. Devolve-lo sem gravar fazia duas chamadas
     * seguidas responderem coisas diferentes, e o valor que ia para o banco nao
     * aparecia em nenhuma linha de log — justamente a rastreabilidade que esta
     * classe existe para dar, ausente no unico caso em que ela e gerada aqui.
     */
    public static String atual() {
        String atual = MDC.get(CHAVE_MDC);
        if (atual != null) {
            return atual;
        }
        String novo = UUID.randomUUID().toString();
        definir(novo);
        return novo;
    }

    /**
     * O id da jornada como UUID, para as colunas e o payload do evento.
     *
     * <p>Um cliente pode mandar {@code X-Correlation-ID: abc123}, que passa na
     * validacao mas nao e UUID. Nesse caso o log continua com o valor do
     * cliente e o banco recebe um UUID derivado dele, estavel: o mesmo texto
     * sempre vira o mesmo UUID.
     */
    public static UUID atualComoUuid() {
        String atual = atual();
        try {
            return UUID.fromString(atual);
        } catch (IllegalArgumentException e) {
            UUID derivado = UUID.nameUUIDFromBytes(
                    atual.getBytes(StandardCharsets.UTF_8));
            registrarDerivacao(atual, derivado);
            return derivado;
        }
    }

    /**
     * Publica a ponte entre o id literal do cliente e o UUID que foi para o banco.
     *
     * <p>Sem esta linha a jornada fica partida em duas metades que ninguem
     * consegue juntar: o log inteiro carrega {@code abc123}, o banco e o payload
     * do evento carregam um UUID, e quem chega pelo banco — que e como comeca
     * toda investigacao de um caso especifico — nao acha nenhuma linha de log.
     * A derivacao e deterministica, mas ninguem calcula {@code nameUUIDFromBytes}
     * de cabeca na hora do incidente.
     *
     * <p>Uma vez por jornada, nao por chamada: {@code atualComoUuid()} e chamado
     * ao gravar a analise, ao publicar o evento e ao consumir, e repetir a mesma
     * linha tres vezes so aumentaria o ruido. A marca vive no MDC e some junto
     * com o resto em {@link #limpar()}.
     */
    private static void registrarDerivacao(String literal, UUID derivado) {
        if (MDC.get(CHAVE_MDC_DERIVACAO_REGISTRADA) != null) {
            return;
        }
        MDC.put(CHAVE_MDC_DERIVACAO_REGISTRADA, "sim");
        log.info("correlationId do cliente '{}' nao e UUID; gravado como {} no "
                + "banco e no evento. Grep pelos dois liga a jornada inteira.",
                literal, derivado);
    }
}
