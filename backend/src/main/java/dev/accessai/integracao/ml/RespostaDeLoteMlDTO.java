package dev.accessai.integracao.ml;

import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * Um resultado por item, NA MESMA ORDEM do pedido.
 *
 * <p>A ordem e o contrato, porque o pedido nao carrega identificador. Dar um
 * {@code id} a cada item seria a alternativa, e custaria inventar chave para
 * algo que o chamador ja tem ordenado em memoria.
 *
 * @param resultados mesma cardinalidade do pedido; conferida por quem chama
 */
public record RespostaDeLoteMlDTO(List<RespostaMlDTO> resultados) {

    /** O servico nao respondeu. Quem chama decide o que fazer com isso. */
    public static @NonNull RespostaDeLoteMlDTO indisponivel() {
        return new RespostaDeLoteMlDTO(null);
    }

    /**
     * Verdadeiro so quando ha exatamente um resultado por item pedido.
     *
     * <p>Cardinalidade diferente e resposta corrompida, nao resposta parcial:
     * sem identificador nos itens, um resultado a menos torna impossivel saber
     * QUAL imagem ficou sem predicao — e associar pela posicao produziria
     * predicao trocada, que e pior que predicao nenhuma.
     */
    public boolean completoPara(int pedidos) {
        return resultados != null && resultados.size() == pedidos;
    }
}
