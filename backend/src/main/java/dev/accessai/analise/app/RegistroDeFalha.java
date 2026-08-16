package dev.accessai.analise.app;

import dev.accessai.analise.dominio.Analise;
import dev.accessai.analise.dominio.AnaliseRepository;
import dev.accessai.analise.dominio.EventoProcessado;
import dev.accessai.analise.dominio.EventoProcessadoRepository;
import dev.accessai.analise.evento.AnaliseSolicitadaV1;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Marca uma analise como FALHOU numa transacao propria.
 *
 * <p>{@code REQUIRES_NEW} nao e enfeite: a transacao que processava a analise
 * ja foi marcada para rollback quando a excecao subiu. Gravar FALHOU dentro
 * dela seria gravar nada — e a analise ficaria em RECEBIDA para sempre, que e
 * exatamente o defeito que este componente existe para fechar.
 */
@Component
public class RegistroDeFalha {

    private static final Logger log = LoggerFactory.getLogger(RegistroDeFalha.class);

    private final AnaliseRepository analiseRepository;
    private final EventoProcessadoRepository eventoRepository;
    private final Clock clock;

    public RegistroDeFalha(AnaliseRepository analiseRepository,
                           EventoProcessadoRepository eventoRepository,
                           Clock clock) {
        this.analiseRepository = analiseRepository;
        this.eventoRepository = eventoRepository;
        this.clock = clock;
    }

    /**
     * Grava o desfecho de uma falha permanente: analise em FALHOU e evento
     * marcado como processado, para que a mesma mensagem nao volte a rodar.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registrar(AnaliseSolicitadaV1 evento) {
        marcarAnaliseComoFalha(evento.analiseId());

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
