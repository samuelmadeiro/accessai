package dev.accessai.analise.outbox;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventoEmDltRepository extends JpaRepository<EventoEmDlt, UUID> {

    List<EventoEmDlt> findByAnaliseId(UUID analiseId);

    boolean existsByEventoId(UUID eventoId);
}
