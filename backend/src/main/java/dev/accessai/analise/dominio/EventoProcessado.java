package dev.accessai.analise.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Marca de idempotencia do consumidor (CONTRIBUTING.md secao 5).
 *
 * <p>A chave e o {@code eventId} do evento Kafka. Reprocessar a mesma mensagem
 * colide na primary key, e o consumidor trata a colisao como "ja processado"
 * em vez de duplicar problemas.
 *
 * <p>Escopo desta slice: so a garantia minima. Retry, DLT e o teste que mata o
 * consumidor no meio sao da Slice 3.
 */
@Entity
@Table(name = "evento_processado")
public class EventoProcessado {

    @Id
    @Column(name = "evento_id", nullable = false, updatable = false)
    private UUID eventoId;

    @Column(name = "consumidor", nullable = false, updatable = false)
    private String consumidor;

    @Column(name = "processado_em", nullable = false, updatable = false)
    private Instant processadoEm;

    protected EventoProcessado() {
        // exigido pelo JPA
    }

    private EventoProcessado(UUID eventoId, String consumidor, Instant processadoEm) {
        this.eventoId = eventoId;
        this.consumidor = consumidor;
        this.processadoEm = processadoEm;
    }

    public static EventoProcessado de(UUID eventoId, String consumidor, Instant agora) {
        return new EventoProcessado(eventoId, consumidor, agora);
    }

    public UUID getEventoId() {
        return eventoId;
    }

    public String getConsumidor() {
        return consumidor;
    }

    public Instant getProcessadoEm() {
        return processadoEm;
    }
}
