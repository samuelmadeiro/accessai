package dev.accessai.analise.evento;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento publicado quando um documento e aceito para analise.
 *
 * <p>Record imutavel e versionado, com {@code eventId}, {@code correlationId} e
 * {@code occurredAt} obrigatorios (CLAUDE.md secao 5). O sufixo V1 esta no nome
 * do tipo e no nome do topico: quebra de contrato vira V2 e topico novo, nunca
 * mudanca silenciosa de formato.
 *
 * <p>Nao carrega os bytes do documento de proposito. Payload grande em topico
 * Kafka e problema de operacao, e o consumidor desta slice roda no mesmo
 * processo que gravou o binario. Quando o ML Service entrar (Slice 5), o campo
 * que muda e a referencia ao documento, nao o resto do contrato.
 *
 * @param eventId       identidade da mensagem; e a chave de deduplicacao
 * @param correlationId atravessa o pipeline inteiro ate os logs
 * @param occurredAt    quando o fato aconteceu, nao quando foi publicado
 * @param schemaVersion versao do formato, redundante com o nome por seguranca
 * @param analiseId     agregado a processar
 * @param nomeArquivo   so para diagnostico; nao e usado para decidir nada
 * @param sha256        permite ao consumidor confirmar que leu o binario certo
 */
public record AnaliseSolicitadaV1(
        UUID eventId,
        UUID correlationId,
        Instant occurredAt,
        int schemaVersion,
        UUID analiseId,
        String nomeArquivo,
        String sha256) {

    public static final int VERSAO_ATUAL = 1;

    public AnaliseSolicitadaV1 {
        if (eventId == null || correlationId == null || occurredAt == null || analiseId == null) {
            throw new IllegalArgumentException(
                    "eventId, correlationId, occurredAt e analiseId sao obrigatorios");
        }
    }

    public static AnaliseSolicitadaV1 de(UUID correlationId, UUID analiseId, String nomeArquivo,
                                         String sha256, Instant agora) {
        return new AnaliseSolicitadaV1(UUID.randomUUID(), correlationId, agora, VERSAO_ATUAL,
                analiseId, nomeArquivo, sha256);
    }
}
