package dev.accessai.analise.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.jspecify.annotations.NonNull;

import java.time.Instant;
import java.util.UUID;

/**
 * Documento recebido para analise.
 *
 * <p>Entidade JPA nunca cruza a fronteira da API nem do Kafka (CONTRIBUTING.md secao
 * 5). Quem sai daqui e sempre um record de DTO ou de evento.
 */
@Entity
@Table(name = "analise")
public class Analise {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "correlation_id", nullable = false, updatable = false)
    private UUID correlationId;

    @Column(name = "nome_arquivo", nullable = false)
    private String nomeArquivo;

    /** Tipo detectado do conteudo. Nunca o declarado pelo cliente. */
    @Column(name = "tipo_mime_detectado", nullable = false)
    private String tipoMimeDetectado;

    @Column(name = "tamanho_bytes", nullable = false)
    private long tamanhoBytes;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "situacao", nullable = false)
    private SituacaoAnalise situacao;

    @Column(name = "criada_em", nullable = false, updatable = false)
    private Instant criadaEm;

    @Column(name = "atualizada_em", nullable = false)
    private Instant atualizadaEm;

    protected Analise() {// exigido pelo JPA
         }

    private Analise(UUID id, UUID correlationId, String nomeArquivo, String tipoMimeDetectado,
                    long tamanhoBytes, String sha256, Instant agora) {
        this.id = id;
        this.correlationId = correlationId;
        this.nomeArquivo = nomeArquivo;
        this.tipoMimeDetectado = tipoMimeDetectado;
        this.tamanhoBytes = tamanhoBytes;
        this.sha256 = sha256;
        this.situacao = SituacaoAnalise.RECEBIDA;
        this.criadaEm = agora;
        this.atualizadaEm = agora;
    }

    public static @NonNull Analise receber(UUID correlationId, String nomeArquivo, String tipoMimeDetectado,
                                           long tamanhoBytes, String sha256, Instant agora) {
        return new Analise(UUID.randomUUID(), correlationId, nomeArquivo, tipoMimeDetectado,
                tamanhoBytes, sha256, agora);
    }

    public void marcarProcessando(Instant agora) {
        transicionarPara(SituacaoAnalise.PROCESSANDO, agora);
    }

    public void marcarConcluida(Instant agora) {
        transicionarPara(SituacaoAnalise.CONCLUIDA, agora);
    }

    public void marcarFalhou(Instant agora) {
        transicionarPara(SituacaoAnalise.FALHOU, agora);
    }

    private void transicionarPara(SituacaoAnalise nova, Instant agora) {
        if (!situacao.podeIrPara(nova)) {
            throw new IllegalStateException(
                    "transicao invalida de " + situacao + " para " + nova + " na analise " + id);
        }
        this.situacao = nova;
        this.atualizadaEm = agora;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCorrelationId() {
        return correlationId;
    }

    public String getNomeArquivo() {
        return nomeArquivo;
    }

    public String getTipoMimeDetectado() {
        return tipoMimeDetectado;
    }

    public long getTamanhoBytes() {
        return tamanhoBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public SituacaoAnalise getSituacao() {
        return situacao;
    }

    public Instant getCriadaEm() {
        return criadaEm;
    }

    public Instant getAtualizadaEm() {
        return atualizadaEm;
    }
}
