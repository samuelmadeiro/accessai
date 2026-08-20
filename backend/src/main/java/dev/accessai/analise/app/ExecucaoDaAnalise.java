package dev.accessai.analise.app;

import dev.accessai.analise.dominio.Analise;
import dev.accessai.analise.dominio.AnaliseRepository;
import dev.accessai.analise.dominio.DocumentoBinario;
import dev.accessai.analise.dominio.DocumentoBinarioRepository;
import dev.accessai.analise.dominio.EventoProcessado;
import dev.accessai.analise.dominio.EventoProcessadoRepository;
import dev.accessai.analise.dominio.Problema;
import dev.accessai.analise.dominio.ProblemaRepository;
import dev.accessai.analise.evento.AnaliseSolicitadaV1;
import dev.accessai.analise.extracao.DocumentoExtraido;
import dev.accessai.analise.extracao.ExtratorDeDocumento;
import dev.accessai.analise.regras.MotorDeRegras;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * O trabalho de uma analise, dentro de uma unica transacao.
 *
 * <p>Idempotente por construcao (CONTRIBUTING.md secao 5): o {@code eventId} e gravado
 * em {@code evento_processado}, cuja primary key rejeita a segunda tentativa. A
 * verificacao e a gravacao acontecem na MESMA transacao do processamento, entao
 * nao existe janela entre "marquei como processado" e "processei".
 *
 * <p>Esta classe so conhece o caminho feliz. Quem decide o que fazer quando ela
 * lanca e {@link ProcessadorDeAnalise} — e ele precisa de OUTRA transacao para
 * gravar a falha, porque esta aqui ja esta condenada ao rollback. Por isso o
 * tratamento de erro nao mora neste arquivo: {@code @Transactional} em metodo
 * chamado de dentro da propria classe nao passa pelo proxy do Spring.
 */
@Component
public class ExecucaoDaAnalise {

    private static final Logger log = LoggerFactory.getLogger(ExecucaoDaAnalise.class);

    static final String NOME_DO_CONSUMIDOR = "analise-rule-engine";

    private final AnaliseRepository analiseRepository;
    private final DocumentoBinarioRepository documentoRepository;
    private final ProblemaRepository problemaRepository;
    private final EventoProcessadoRepository eventoRepository;
    private final ExtratorDeDocumento extrator;
    private final MotorDeRegras motor;
    private final Clock clock;

    public ExecucaoDaAnalise(AnaliseRepository analiseRepository,
                             DocumentoBinarioRepository documentoRepository,
                             ProblemaRepository problemaRepository,
                             EventoProcessadoRepository eventoRepository,
                             ExtratorDeDocumento extrator,
                             MotorDeRegras motor,
                             Clock clock) {
        this.analiseRepository = analiseRepository;
        this.documentoRepository = documentoRepository;
        this.problemaRepository = problemaRepository;
        this.eventoRepository = eventoRepository;
        this.extrator = extrator;
        this.motor = motor;
        this.clock = clock;
    }

    @Transactional
    public void executar(AnaliseSolicitadaV1 evento) {
        if (eventoRepository.existsById(evento.eventId())) {
            log.info("evento ja processado, ignorando eventId={} analiseId={} correlationId={}",
                    evento.eventId(), evento.analiseId(), evento.correlationId());
            return;
        }

        Analise analise = analiseRepository.findById(evento.analiseId())
                .orElseThrow(() -> new AnaliseNaoEncontradaException(evento.analiseId()));

        if (analise.getSituacao().ehTerminal()) {
            log.info("analise ja em estado terminal ({}), ignorando analiseId={}",
                    analise.getSituacao(), analise.getId());
            registrarProcessado(evento);
            return;
        }

        DocumentoBinario documento = documentoRepository.findById(evento.analiseId())
                .orElseThrow(() -> new BinarioAusenteException(evento.analiseId()));

        Instant agora = clock.instant();
        analise.marcarProcessando(agora);

        DocumentoExtraido extraido = extrator.extrair(documento.getConteudo());
        List<Problema> problemas = motor.executar(analise.getId(), extraido, agora);
        problemaRepository.saveAll(problemas);

        analise.marcarConcluida(clock.instant());
        analiseRepository.save(analise);
        registrarProcessado(evento);

        log.info("analise concluida analiseId={} correlationId={} imagens={} tabelas={} "
                        + "titulos={} links={} problemas={}",
                analise.getId(), evento.correlationId(), extraido.imagens().size(),
                extraido.tabelas().size(), extraido.cabecalhos().size(),
                extraido.links().size(), problemas.size());
    }

    private void registrarProcessado(AnaliseSolicitadaV1 evento) {
        eventoRepository.save(EventoProcessado.de(
                evento.eventId(), NOME_DO_CONSUMIDOR, clock.instant()));
    }
}
