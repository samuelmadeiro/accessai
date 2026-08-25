package dev.accessai.ia;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Uma recomendacao gerada para uma analise, com a procedencia gravada.
 *
 * <p>Entidade JPA nunca cruza a fronteira da API (CONTRIBUTING.md secao 5).
 */
@Entity
@Table(name = "recomendacao")
public class Recomendacao {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "analise_id", nullable = false, updatable = false)
    private UUID analiseId;

    @Column(name = "regra_id", nullable = false)
    private String regraId;

    @Column(name = "criterio_wcag", nullable = false)
    private String criterioWcag;

    @Column(name = "texto", nullable = false)
    private String texto;

    @Column(name = "procedencia", nullable = false)
    private String procedencia;

    @Column(name = "modelo", nullable = false)
    private String modelo;

    @Column(name = "criada_em", nullable = false, updatable = false)
    private Instant criadaEm;

    protected Recomendacao() {
        // exigido pelo JPA
    }

    public static @NonNull Recomendacao de(UUID analiseId,
                                           RespostaDeIa.Recomendacao origem,
                                           AiProvider.Procedencia procedencia,
                                           String modelo, Instant agora) {
        Recomendacao r = new Recomendacao();
        r.id = UUID.randomUUID();
        r.analiseId = analiseId;
        r.regraId = origem.regraId();
        r.criterioWcag = origem.criterioWcag();
        r.texto = origem.texto();
        r.procedencia = procedencia.name();
        r.modelo = modelo;
        r.criadaEm = agora;
        return r;
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

    public String getTexto() {
        return texto;
    }

    public String getProcedencia() {
        return procedencia;
    }

    public String getModelo() {
        return modelo;
    }

    public Instant getCriadaEm() {
        return criadaEm;
    }
}
