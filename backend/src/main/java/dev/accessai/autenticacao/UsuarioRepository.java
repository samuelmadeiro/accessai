package dev.accessai.autenticacao;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repositorio nao toma decisao: so persiste e consulta (CONTRIBUTING.md secao 5). */
public interface UsuarioRepository extends JpaRepository<Usuario, UUID> {

    /**
     * O email ja chega normalizado por {@link Usuario#normalizarEmail(String)}.
     *
     * <p>Derivar a consulta por {@code IgnoreCase} pareceria mais seguro e seria
     * pior: o indice unico do banco e sobre {@code LOWER(email)}, e uma consulta
     * com {@code UPPER}/{@code LOWER} diferente do indice deixa de usa-lo.
     */
    Optional<Usuario> findByEmail(String email);

    boolean existsByEmail(String email);
}
