package dev.accessai.analise.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Um evento gravado na mesma transacao do agregado, para ser publicado depois.
 *
 * <p>Este e o coracao do padrao outbox: na Slice 1 o {@code POST /analyses}
 * gravava a analise, commitava e SO ENTAO publicava no Kafka. Morrer entre as
 * duas coisas deixava a analise em RECEBIDA para sempre. Agora as duas
 * gravacoes sao a mesma transacao, e a publicacao virou um trabalho separado
 * que pode falhar e repetir.
 *
 * <p>O preco: a entrega passa a ser explicitamente <b>at-least-once</b>. Se o
 * processo morrer entre publicar no broker e marcar {@code publicadoEm}, o
 * evento sai de novo — com o MESMO {@code id}, que e a chave de deduplicacao do
 * consumidor. Duplicata detectavel e melhor que evento perdido.
 */
@Entity
@Table(name = "outbox_evento")
public class EventoDeOutbox {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "agregado_id", nullable = false, updatable = false)
    private UUID agregadoId;

    @Column(name = "tipo", nullable = false, updatable = false)
    private String tipo;

    @Column(name = "topico", nullable = false, updatable = false)
    private String topico;

    @Column(name = "chave", nullable = false, updatable = false)
    private String chave;

    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Column(name = "correlation_id", nullable = false, updatable = false)
    private UUID correlationId;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm;

    @Column(name = "publicado_em")
    private Instant publicadoEm;

    @Column(name = "tentativas", nullable = false)
    private int tentativas;

    @Column(name = "ultimo_erro")
    private String ultimoErro;

    protected EventoDeOutbox() {
        // exigido pelo JPA
    }

    private EventoDeOutbox(UUID id, UUID agregadoId, String tipo, String topico, String chave,
                           String payload, UUID correlationId, Instant criadoEm) {
        this.id = id;
        this.agregadoId = agregadoId;
        this.tipo = tipo;
        this.topico = topico;
        this.chave = chave;
        this.payload = payload;
        this.correlationId = correlationId;
        this.criadoEm = criadoEm;
    }

    public static EventoDeOutbox pendente(UUID eventoId, UUID agregadoId, String tipo,
                                          String topico, String chave, String payload,
                                          UUID correlationId, Instant agora) {
        return new EventoDeOutbox(eventoId, agregadoId, tipo, topico, chave, payload,
                correlationId, agora);
    }

    public void marcarPublicado(Instant agora) {
        this.publicadoEm = agora;
        this.ultimoErro = null;
    }

    /**
     * Registra uma tentativa que falhou. A mensagem do erro e truncada: stack
     * trace inteiro numa coluna de diagnostico incha a tabela e ninguem le.
     */
    public void registrarFalha(String erro) {
        this.tentativas++;
        this.ultimoErro = erro == null ? null
                : erro.substring(0, Math.min(erro.length(), 500));
    }

    public boolean foiPublicado() {
        return publicadoEm != null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAgregadoId() {
        return agregadoId;
    }

    public String getTipo() {
        return tipo;
    }

    public String getTopico() {
        return topico;
    }

    public String getChave() {
        return chave;
    }

    public String getPayload() {
        return payload;
    }

    public UUID getCorrelationId() {
        return correlationId;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }

    public Instant getPublicadoEm() {
        return publicadoEm;
    }

    public int getTentativas() {
        return tentativas;
    }

    public String getUltimoErro() {
        return ultimoErro;
    }
}
