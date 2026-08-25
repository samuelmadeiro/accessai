package dev.accessai.autenticacao;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Uma conta. Multiusuario, single-tenant por usuario (D4).
 *
 * <p>Sem organizacao, sem papel, sem convite: cortados por over-engineering na
 * Fase 0. O que existe e o suficiente para provar isolamento por linha, que e o
 * entregavel da decisao.
 *
 * <p>Entidade JPA nunca cruza a fronteira da API (CONTRIBUTING.md secao 5) — e
 * aqui isso importa mais que no resto, porque o campo que ela carrega e um hash
 * de senha.
 */
@Entity
@Table(name = "usuario")
public class Usuario {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "senha_hash", nullable = false)
    private String senhaHash;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm;

    protected Usuario() {
        // exigido pelo JPA
    }

    private Usuario(UUID id, String email, String senhaHash, Instant agora) {
        this.id = id;
        this.email = email;
        this.senhaHash = senhaHash;
        this.criadoEm = agora;
    }

    /**
     * Cria a conta com o hash JA calculado.
     *
     * <p>A senha em claro nao entra aqui de proposito: se ela chegasse, esta
     * classe teria que conhecer o algoritmo de hash, e um construtor que aceita
     * texto claro e um construtor que alguem vai chamar com texto claro e
     * esquecer de hashear.
     */
    public static @NonNull Usuario criar(String email, String senhaHash, Instant agora) {
        return new Usuario(UUID.randomUUID(), normalizarEmail(email), senhaHash, agora);
    }

    /**
     * Minusculas e sem espaco nas pontas.
     *
     * <p>O indice unico do banco e sobre {@code LOWER(email)}; normalizar aqui
     * tambem mantem a consulta de login casando com o que foi gravado, sem
     * depender de o chamador lembrar.
     */
    public static @NonNull String normalizarEmail(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getSenhaHash() {
        return senhaHash;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }
}
