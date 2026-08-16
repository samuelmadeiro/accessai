package dev.accessai.analise.dominio;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProblemaRepository extends JpaRepository<Problema, UUID> {

    List<Problema> findByAnaliseIdOrderByCriadoEmAsc(UUID analiseId);
}
