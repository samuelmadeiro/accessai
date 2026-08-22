package dev.accessai.analise.dominio;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PredicaoDeAltRepository extends JpaRepository<PredicaoDeAlt, UUID> {

    List<PredicaoDeAlt> findByAnaliseIdOrderByIndiceAsc(UUID analiseId);
}
