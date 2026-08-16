package dev.accessai.analise.dominio;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventoProcessadoRepository extends JpaRepository<EventoProcessado, UUID> {
}
