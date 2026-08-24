package dev.accessai.analise.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.jspecify.annotations.NonNull;

import java.util.UUID;

/**
 * Bytes do documento enviado, em tabela separada de {@link Analise}.
 *
 * <p>Listagem e consulta de analise nunca precisam do conteudo; manter o BYTEA
 * na mesma tabela faria toda consulta carregar megabytes sem necessidade.
 *
 * <p>Limite conhecido: quando o ML Service entrar (Slice 5) ele nao pode ler
 * este banco (CONTRIBUTING.md secao 5). Nesse momento isto vira armazenamento de
 * objeto e o evento passa a carregar a referencia externa.
 */
@Entity
@Table(name = "documento_binario")
public class DocumentoBinario {

    @Id
    @Column(name = "analise_id", nullable = false, updatable = false)
    private UUID analiseId;

    /**
     * Sem {@code @Lob} de proposito: com ele o Hibernate mapeia para {@code oid}
     * do Postgres, que e large object com API propria e limpeza manual. Um
     * {@code byte[]} puro mapeia para {@code bytea}, que e o que o schema
     * declara e o que este caso precisa.
     */
    @Column(name = "conteudo", nullable = false)
    private byte[] conteudo;

    protected DocumentoBinario() {
        // exigido pelo JPA
    }

    private DocumentoBinario(UUID analiseId, byte[] conteudo) {
        this.analiseId = analiseId;
        this.conteudo = conteudo;
    }

    public static @NonNull DocumentoBinario de(UUID analiseId, byte @NonNull [] conteudo) {
        return new DocumentoBinario(analiseId, conteudo.clone());
    }

      public UUID getAnaliseId() {
        return analiseId;
    }

    public byte[] getConteudo() {
        return conteudo.clone();
    }
}
