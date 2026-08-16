package dev.accessai.analise.dominio;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repositorio nao toma decisao: so persiste e consulta (CLAUDE.md secao 5). */
public interface AnaliseRepository extends JpaRepository<Analise, UUID> {
}
