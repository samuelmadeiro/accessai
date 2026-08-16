package dev.accessai.analise.dominio;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentoBinarioRepository extends JpaRepository<DocumentoBinario, UUID> {
}
