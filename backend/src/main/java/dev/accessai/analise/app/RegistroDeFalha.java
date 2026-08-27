package dev.accessai.analise.app;

import dev.accessai.analise.dominio.Analise;
import dev.accessai.analise.dominio.AnaliseRepository;
import dev.accessai.analise.dominio.EventoProcessado;
import dev.accessai.analise.dominio.EventoProcessadoRepository;
import dev.accessai.analise.evento.AnaliseSolicitadaV1;
import dev.accessai.analise.outbox.EventoEmDlt;
import dev.accessai.analise.outbox.EventoEmDltRepository;
import java.time.Clock;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fecha o ciclo de uma analise que falhou: FALHOU no banco e registro do que
 * chegou na DLT.
 *
 * <p>Quem chama e o consumidor da DLT, depois de o retry ter se esgotado. A
 * transicao de estado acontece num lugar so, e nao espalhada por cada ponto que
 * pode lancar excecao.
 *
 * <h2>Por que nao ha corrida com o consumidor principal</h2>
 *
 * <p>Este consumidor esta num grupo diferente do consumidor do topico principal
 * ({@code -dlt}), entao os dois rodam ao mesmo tempo e escrevem a MESMA linha de
 * {@code analise}. {@code Analise} nao tem {@code @Version}, e as verificacoes
 * de {@code existsById} e {@code ehTerminal()} aqui e em {@link ExecucaoDaAnalise}
 * sao check-then-act: sozinhas, nao impedem nada.
 *
 * <p>Quem garante a exclusao mutua e a PRIMARY KEY de {@code evento_processado}.
 * Os dois caminhos gravam o mesmo {@code eventId} nessa tabela antes de commitar;
 * o Postgres bloqueia o segundo no indice unico e ele termina em violacao de
 * chave, com rollback de tudo que tinha feito — inclusive da transicao de estado.
 *
 * <p>Isto e o que sustenta a idempotencia do pipeline inteiro. Trocar essa PK
 * por um id sintetico, ou transformar a gravacao num upsert, remove a garantia
 * sem quebrar nenhum teste de caminho feliz.
 */
@Component
public class RegistroDeFalha {

    private static final Logger log = LoggerFactory.getLogger(RegistroDeFalha.class);

    private final AnaliseRepository analiseRepository;
    private final EventoProcessadoRepository eventoRepository;
    private final EventoEmDltRepository dltRepository;
    private final Clock clock;

    public RegistroDeFalha(AnaliseRepository analiseRepository,
                           EventoProcessadoRepository eventoRepository,
                           EventoEmDltRepository dltRepository,
                           Clock clock) {
        this.analiseRepository = analiseRepository;
        this.eventoRepository = eventoRepository;
        this.dltRepository = dltRepository;
        this.clock = clock;
    }

    /**
     * Idempotente: a mesma mensagem pode chegar duas vezes na DLT, e registrar
     * duas falhas para a mesma analise so poluiria o diagnostico.
     */
    @Transactional
    public void registrar(@NonNull AnaliseSolicitadaV1 evento,
                          String topicoOriginal,
                          String excecao,
                          String mensagemDeErro) {
        if (dltRepository.existsByEventoId(evento.eventId())) {
            log.info("evento {} ja registrado na DLT, ignorando", evento.eventId());
            return;
        }

        dltRepository.save(EventoEmDlt.de(evento.eventId(), evento.analiseId(),
                topicoOriginal == null ? "desconhecido" : topicoOriginal,
                excecao, mensagemDeErro, clock.instant()));

        marcarAnaliseComoFalha(evento.analiseId());

        // O evento tambem entra em evento_processado: se a mesma mensagem
        // voltar ao topico principal, o consumidor a ignora em vez de tentar
        // processar uma analise ja encerrada.
        if (!eventoRepository.existsById(evento.eventId())) {
            eventoRepository.save(EventoProcessado.de(
                    evento.eventId(), ExecucaoDaAnalise.NOME_DO_CONSUMIDOR, clock.instant()));
        }
    }

    private void marcarAnaliseComoFalha(UUID analiseId) {
        Analise analise = analiseRepository.findById(analiseId).orElse(null);
        if (analise == null) {
            // A propria ausencia da linha pode ter sido a causa da falha.
            log.warn("analise {} nao existe; nada a marcar como FALHOU", analiseId);
            return;
        }
        if (analise.getSituacao().ehTerminal()) {
            log.warn("analise {} ja esta em {}; mantida como esta",
                    analiseId, analise.getSituacao());
            return;
        }
        analise.marcarFalhou(clock.instant());
        analiseRepository.save(analise);
    }
}
