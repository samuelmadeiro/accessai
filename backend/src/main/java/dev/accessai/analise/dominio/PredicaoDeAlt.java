package dev.accessai.analise.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.jspecify.annotations.NonNull;

import java.time.Instant;
import java.util.UUID;

/**
 * A qualidade de um texto alternativo, inferida pelo ML Service.
 *
 * <p>Vive fora de {@link Problema} de proposito. O score e soma ponderada de
 * penalidades deterministicas (CONTRIBUTING.md secao 6); predicao que virasse
 * problema entraria na conta pela porta dos fundos. Hoje isso pesa mais que o
 * normal: sem modelo treinado, TODA predicao vem da heuristica.
 *
 * <p>{@code usouHeuristica} e a coluna que impede o produto de apresentar regra
 * como predicao de ML. {@code confianca} e nula quando ela e true — regra nao
 * tem probabilidade.
 */
@Entity
@Table(name = "predicao_de_alt")
public class PredicaoDeAlt {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "analise_id", nullable = false, updatable = false)
    private UUID analiseId;

    @Column(name = "indice", nullable = false, updatable = false)
    private int indice;

    @Column(name = "parte_pacote", nullable = false, updatable = false)
    private String partePacote;

    @Column(name = "nome_imagem", nullable = false, updatable = false)
    private String nomeImagem;

    @Column(name = "alt", nullable = false, updatable = false)
    private String alt;

    @Column(name = "categoria", nullable = false, updatable = false)
    private String categoria;

    @Column(name = "confianca", updatable = false)
    private Double confianca;

    @Column(name = "usou_heuristica", nullable = false, updatable = false)
    private boolean usouHeuristica;

    @Column(name = "modelo_versao", updatable = false)
    private String modeloVersao;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm;

    protected PredicaoDeAlt() {
        // exigido pelo JPA
    }

    private PredicaoDeAlt(UUID id, UUID analiseId, int indice, String partePacote,
                          String nomeImagem, String alt, String categoria,
                          Double confianca, boolean usouHeuristica, String modeloVersao,
                          Instant criadoEm) {
        this.id = id;
        this.analiseId = analiseId;
        this.indice = indice;
        this.partePacote = partePacote;
        this.nomeImagem = nomeImagem;
        this.alt = alt;
        this.categoria = categoria;
        this.confianca = confianca;
        this.usouHeuristica = usouHeuristica;
        this.modeloVersao = modeloVersao;
        this.criadoEm = criadoEm;
    }

    public static @NonNull PredicaoDeAlt de(UUID analiseId, int indice, String partePacote,
                                            String nomeImagem, String alt, String categoria,
                                            Double confianca, boolean usouHeuristica,
                                            String modeloVersao, Instant agora) {
        return new PredicaoDeAlt(UUID.randomUUID(), analiseId, indice, partePacote,
                nomeImagem, alt, categoria,
                // A restricao do banco recusa confianca vinda de heuristica; o
                // dominio nao deixa a incoerencia chegar la para ser recusada.
                usouHeuristica ? null : confianca,
                usouHeuristica,
                usouHeuristica ? null : modeloVersao,
                agora);
    }

    public UUID getId() {
        return id;
    }

    public UUID getAnaliseId() {
        return analiseId;
    }

    public int getIndice() {
        return indice;
    }

    public String getPartePacote() {
        return partePacote;
    }

    public String getNomeImagem() {
        return nomeImagem;
    }

    public String getAlt() {
        return alt;
    }

    public String getCategoria() {
        return categoria;
    }

    public Double getConfianca() {
        return confianca;
    }

    public boolean isUsouHeuristica() {
        return usouHeuristica;
    }

    public String getModeloVersao() {
        return modeloVersao;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }
}
