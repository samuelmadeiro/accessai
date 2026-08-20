package dev.accessai.analise.outbox;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventoDeOutboxRepository extends JpaRepository<EventoDeOutbox, UUID> {

    /**
     * Pega o proximo lote de pendentes travando as linhas.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} nao e enfeite de performance: com duas
     * instancias do backend rodando, as duas leriam o mesmo lote e publicariam
     * o mesmo evento duas vezes. Com SKIP LOCKED a segunda instancia pula as
     * linhas que a primeira travou e trabalha no resto — o mesmo mecanismo de
     * uma fila em Postgres, usado aqui para o outbox.
     *
     * <p>O filtro por {@code tentativas} e o que impede bloqueio de cabeca de
     * fila. Um evento impublicavel em definitivo — payload acima do limite do
     * broker, topico removido — falharia a cada ciclo para sempre; somadas
     * {@code tamanhoDoLote} linhas nesse estado, elas ocupariam o lote inteiro
     * e nenhum evento novo sairia. Passado o teto, a linha continua na tabela
     * para diagnostico, mas fora do caminho de quem ainda pode ser publicado.
     *
     * <p>Consulta nativa porque {@code SKIP LOCKED} nao existe em JPQL.
     */
    @Query(value = "SELECT * FROM outbox_evento "
            + "WHERE publicado_em IS NULL AND tentativas < :maxTentativas "
            + "ORDER BY criado_em "
            + "LIMIT :limite "
            + "FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<EventoDeOutbox> pegarPendentes(@Param("limite") int limite,
                                        @Param("maxTentativas") int maxTentativas);
}
