package dev.accessai.analise.dominio;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repositorio nao toma decisao: so persiste e consulta (CONTRIBUTING.md secao 5). */
public interface AnaliseRepository extends JpaRepository<Analise, UUID> {

    /**
     * A analise, SE ela for do dono informado.
     *
     * <p>Metodo explicito, e nao filtro global do Hibernate: filtro global e
     * facil de esquecer de ligar e o esquecimento e silencioso — o D4 escolhe
     * assim de proposito. Aqui o isolamento esta na assinatura, e quem escrever
     * uma consulta nova sem `OwnerId` ve a diferenca ao lado.
     *
     * <p>Devolver vazio, e nao lancar, e o que permite ao chamador responder
     * <b>404</b> em vez de 403: dizer "existe, mas nao e sua" ja vaza a
     * existencia do recurso de outra pessoa.
     */
    Optional<Analise> findByIdAndOwnerId(UUID id, UUID ownerId);
}
