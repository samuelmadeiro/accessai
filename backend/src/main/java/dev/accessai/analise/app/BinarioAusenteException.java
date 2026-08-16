package dev.accessai.analise.app;

import java.util.UUID;

/**
 * A linha da analise existe, o binario nao.
 *
 * <p>Falha permanente: reprocessar a mensagem produz exatamente o mesmo
 * resultado, porque o binario nao vai aparecer sozinho. Por isso a analise vai
 * para FALHOU em vez de voltar para a fila.
 */
public class BinarioAusenteException extends RuntimeException {

    public BinarioAusenteException(UUID analiseId) {
        super("binario ausente para a analise " + analiseId);
    }
}
