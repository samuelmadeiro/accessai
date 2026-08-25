package dev.accessai.copiloto;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositorio nao toma decisao: so persiste e consulta (CONTRIBUTING.md secao 5).
 *
 * <p><b>Toda consulta aqui e por {@code analiseId}, e nunca pelo id do turno.</b>
 * E a primeira das duas condicoes do ADR 0012 para a heranca de isolamento nao
 * vazar: quem carrega um turno pelo id dele nao tem como saber de quem ele e, e
 * um {@code findById} distraido seria uma leitura cruzada silenciosa — o defeito
 * que o teste de 404 da Slice 5A existe para impedir.
 *
 * <p>Quem chama e responsavel por ter carregado a analise com
 * {@code findByIdAndOwnerId} antes. O metodo abaixo assume que isso ja aconteceu,
 * e {@code ServicoDeConversa} e o unico lugar que o chama.
 */
public interface TurnoDeConversaRepository extends JpaRepository<TurnoDeConversa, UUID> {

    List<TurnoDeConversa> findByAnaliseIdOrderByCriadoEmAsc(UUID analiseId);
}
