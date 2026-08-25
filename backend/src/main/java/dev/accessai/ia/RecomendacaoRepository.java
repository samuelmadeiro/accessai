package dev.accessai.ia;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repositorio nao toma decisao: so persiste e consulta (CONTRIBUTING.md secao 5). */
public interface RecomendacaoRepository extends JpaRepository<Recomendacao, UUID> {

    List<Recomendacao> findByAnaliseIdOrderByCriadaEmAsc(UUID analiseId);

    boolean existsByAnaliseId(UUID analiseId);
}
