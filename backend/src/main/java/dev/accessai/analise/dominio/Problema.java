package dev.accessai.analise.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Um problema de acessibilidade encontrado por uma regra.
 *
 * <p>{@code criterioWcag} e sempre um identificador vindo da tabela versionada
 * {@code docs/wcag/criteria.json}, com aplicabilidade a documento nao-web
 * resolvida via WCAG2ICT. Nenhuma regra inventa criterio, nivel ou numeracao
 * (CLAUDE.md secao 6).
 */
@Entity
@Table(name = "problema")
public class Problema {

    public enum Nivel { A, AA, AAA }

    public enum Severidade { BAIXA, MEDIA, ALTA, CRITICA }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "analise_id", nullable = false, updatable = false)
    private UUID analiseId;

    @Column(name = "regra_id", nullable = false)
    private String regraId;

    @Column(name = "criterio_wcag", nullable = false)
    private String criterioWcag;

    @Enumerated(EnumType.STRING)
    @Column(name = "nivel_wcag", nullable = false)
    private Nivel nivelWcag;

    @Enumerated(EnumType.STRING)
    @Column(name = "severidade", nullable = false)
    private Severidade severidade;

    /** Parte do pacote OOXML onde o problema esta: word/document.xml, word/header1.xml, ... */
    @Column(name = "parte_pacote", nullable = false)
    private String partePacote;

    @Column(name = "evidencia", nullable = false)
    private String evidencia;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm;

    protected Problema() {
        // exigido pelo JPA
    }

    private Problema(UUID id, UUID analiseId, String regraId, String criterioWcag, Nivel nivelWcag,
                     Severidade severidade, String partePacote, String evidencia, Instant criadoEm) {
        this.id = id;
        this.analiseId = analiseId;
        this.regraId = regraId;
        this.criterioWcag = criterioWcag;
        this.nivelWcag = nivelWcag;
        this.severidade = severidade;
        this.partePacote = partePacote;
        this.evidencia = evidencia;
        this.criadoEm = criadoEm;
    }

    public static Problema registrar(UUID analiseId, String regraId, String criterioWcag,
                                     Nivel nivelWcag, Severidade severidade, String partePacote,
                                     String evidencia, Instant agora) {
        return new Problema(UUID.randomUUID(), analiseId, regraId, criterioWcag, nivelWcag,
                severidade, partePacote, evidencia, agora);
    }

    public UUID getId() {
        return id;
    }

    public UUID getAnaliseId() {
        return analiseId;
    }

    public String getRegraId() {
        return regraId;
    }

    public String getCriterioWcag() {
        return criterioWcag;
    }

    public Nivel getNivelWcag() {
        return nivelWcag;
    }

    public Severidade getSeveridade() {
        return severidade;
    }

    public String getPartePacote() {
        return partePacote;
    }

    public String getEvidencia() {
        return evidencia;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }
}
