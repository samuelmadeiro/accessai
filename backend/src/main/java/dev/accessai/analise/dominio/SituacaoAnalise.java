package dev.accessai.analise.dominio;

import java.util.Set;

/**
 * Ciclo de vida de uma analise.
 *
 * <p>As transicoes validas ficam aqui e nao no servico: consumidor idempotente
 * reprocessa mensagem, e sem uma maquina de estados explicita uma analise ja
 * CONCLUIDA voltaria para PROCESSANDO sem ninguem perceber.
 */
public enum SituacaoAnalise {

    RECEBIDA,
    PROCESSANDO,
    CONCLUIDA,
    FALHOU;

    private static final Set<SituacaoAnalise> TERMINAIS = Set.of(CONCLUIDA, FALHOU);

    public boolean podeIrPara(SituacaoAnalise destino) {
        return switch (this) {
            case RECEBIDA -> destino == PROCESSANDO || destino == FALHOU;
            case PROCESSANDO -> TERMINAIS.contains(destino);
            case CONCLUIDA, FALHOU -> false;
        };
    }

    public boolean ehTerminal() {
        return TERMINAIS.contains(this);
    }
}
