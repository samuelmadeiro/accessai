package dev.accessai.analise.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Registro de uma mensagem que esgotou o retry e caiu na Dead Letter Topic.
 *
 * <p>Existe porque "foi para a DLT" precisa ser uma consulta, nao uma linha de
 * log que ninguem le. Guarda a excecao e a mensagem originais: sem elas, para
 * saber por que a analise falhou seria preciso reprocessar a mensagem.
 */
@Entity
@Table(name = "evento_em_dlt")
public class EventoEmDlt {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "evento_id", nullable = false, updatable = false)
    private UUID eventoId;

    @Column(name = "analise_id", updatable = false)
    private UUID analiseId;

    @Column(name = "topico_origem", nullable = false, updatable = false)
    private String topicoOrigem;

    @Column(name = "excecao", updatable = false)
    private String excecao;

    @Column(name = "mensagem_erro", updatable = false)
    private String mensagemErro;

    @Column(name = "recebido_em", nullable = false, updatable = false)
    private Instant recebidoEm;

    protected EventoEmDlt() {
        // exigido pelo JPA
    }

    private EventoEmDlt(UUID id, UUID eventoId, UUID analiseId, String topicoOrigem,
                        String excecao, String mensagemErro, Instant recebidoEm) {
        this.id = id;
        this.eventoId = eventoId;
        this.analiseId = analiseId;
        this.topicoOrigem = topicoOrigem;
        this.excecao = excecao;
        this.mensagemErro = mensagemErro;
        this.recebidoEm = recebidoEm;
    }

    public static EventoEmDlt de(UUID eventoId, UUID analiseId, String topicoOrigem,
                                 String excecao, String mensagemErro, Instant agora) {
        return new EventoEmDlt(UUID.randomUUID(), eventoId, analiseId, topicoOrigem,
                truncar(excecao), truncar(mensagemErro), agora);
    }

    private static String truncar(String valor) {
        if (valor == null) {
            return null;
        }
        return valor.substring(0, Math.min(valor.length(), 1000));
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventoId() {
        return eventoId;
    }

    public UUID getAnaliseId() {
        return analiseId;
    }

    public String getTopicoOrigem() {
        return topicoOrigem;
    }

    public String getExcecao() {
        return excecao;
    }

    public String getMensagemErro() {
        return mensagemErro;
    }

    public Instant getRecebidoEm() {
        return recebidoEm;
    }
}
