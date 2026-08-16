package dev.accessai.analise.app;

import java.util.UUID;

/** Vira HTTP 404 na borda. */
public class AnaliseNaoEncontradaException extends RuntimeException {

    public AnaliseNaoEncontradaException(UUID analiseId) {
        super("analise nao encontrada: " + analiseId);
    }
}
