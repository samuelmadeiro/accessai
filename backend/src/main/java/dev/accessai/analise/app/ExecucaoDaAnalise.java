package dev.accessai.analise.app;

import dev.accessai.analise.dominio.Analise;
import dev.accessai.analise.dominio.AnaliseRepository;
import dev.accessai.analise.dominio.DocumentoBinario;
import dev.accessai.analise.dominio.DocumentoBinarioRepository;
import dev.accessai.analise.dominio.EventoProcessado;
import dev.accessai.analise.dominio.EventoProcessadoRepository;
import dev.accessai.analise.dominio.PredicaoDeAlt;
import dev.accessai.analise.dominio.PredicaoDeAltRepository;
import dev.accessai.analise.dominio.Problema;
import dev.accessai.analise.dominio.ProblemaRepository;
import dev.accessai.analise.evento.AnaliseSolicitadaV1;
import dev.accessai.analise.extracao.DocumentoExtraido;
import dev.accessai.analise.extracao.ExtratorDeDocumento;
import dev.accessai.analise.extracao.ImagemDoDocumento;
import dev.accessai.analise.regras.MotorDeRegras;
import dev.accessai.integracao.ml.ClienteMlService;
import dev.accessai.integracao.ml.RequisicaoMlDTO;
import dev.accessai.integracao.ml.RespostaMlDTO;
import java.util.ArrayList;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NonNull;
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
 * <p>Esta classe so conhece o caminho feliz, e lanca de proposito. Quem decide
 * retry, backoff e desvio e o {@code DefaultErrorHandler} configurado em
 * {@code KafkaConfig}; esgotadas as tentativas, a mensagem chega na DLT e
 * {@link RegistroDeFalha} marca a analise como FALHOU. O registro da falha
 * precisa mesmo de OUTRA transacao, porque esta aqui ja esta condenada ao
 * rollback — e {@code @Transactional} em metodo chamado de dentro da propria
 * classe nao passa pelo proxy do Spring.
 */
@Component
public class ExecucaoDaAnalise {

    private static final Logger log = LoggerFactory.getLogger(ExecucaoDaAnalise.class);

    static final String NOME_DO_CONSUMIDOR = "analise-rule-engine";

    private final AnaliseRepository analiseRepository;
    private final DocumentoBinarioRepository documentoRepository;
    private final ProblemaRepository problemaRepository;
    private final EventoProcessadoRepository eventoRepository;
    private final PredicaoDeAltRepository predicaoRepository;
    private final ExtratorDeDocumento extrator;
    private final MotorDeRegras motor;
    private final ClienteMlService ml;
    private final Clock clock;

    public ExecucaoDaAnalise(AnaliseRepository analiseRepository,
                             DocumentoBinarioRepository documentoRepository,
                             ProblemaRepository problemaRepository,
                             EventoProcessadoRepository eventoRepository,
                             PredicaoDeAltRepository predicaoRepository,
                             ExtratorDeDocumento extrator,
                             MotorDeRegras motor,
                             ClienteMlService ml,
                             Clock clock) {
        this.analiseRepository = analiseRepository;
        this.documentoRepository = documentoRepository;
        this.problemaRepository = problemaRepository;
        this.eventoRepository = eventoRepository;
        this.predicaoRepository = predicaoRepository;
        this.extrator = extrator;
        this.motor = motor;
        this.ml = ml;
        this.clock = clock;
    }

    @Transactional
    public void executar(@NonNull AnaliseSolicitadaV1 evento) {
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

        // O ML entra DEPOIS das regras, e o que ele produz nao volta para elas.
        // Se a predicao pudesse virar problema, o score deixaria de ser
        // deterministico (CONTRIBUTING.md secao 6).
        List<PredicaoDeAlt> predicoes = classificarAlts(analise.getId(), extraido, agora);
        predicaoRepository.saveAll(predicoes);

        analise.marcarConcluida(clock.instant());
        analiseRepository.save(analise);
        registrarProcessado(evento);

        log.info("analise concluida analiseId={} correlationId={} imagens={} tabelas={} "
                        + "titulos={} links={} problemas={} predicoes={}",
                analise.getId(), evento.correlationId(), extraido.imagens().size(),
                extraido.tabelas().size(), extraido.cabecalhos().size(),
                extraido.links().size(), problemas.size(), predicoes.size());
    }

    /**
     * Classifica a qualidade dos alt texts que EXISTEM.
     *
     * <p>Alt ausente nao entra: e deteccao deterministica, ja coberta pela regra
     * {@code IMAGEM_SEM_TEXTO_ALTERNATIVO}, e usar ML nisso violaria a ordem de
     * precedencia da secao 2 do CONTRIBUTING.md. Alt vazio tambem nao: e
     * declaracao deliberada de imagem decorativa, que o WCAG 1.1.1 permite.
     *
     * <p>Lista vazia quando o ML Service esta indisponivel. O
     * {@link ClienteMlService} nunca lanca, entao a analise conclui do mesmo
     * jeito — com menos informacao, nao com menos analise.
     */
    private List<PredicaoDeAlt> classificarAlts(java.util.UUID analiseId,
                                                DocumentoExtraido extraido, Instant agora) {
        List<PredicaoDeAlt> predicoes = new ArrayList<>();
        int indice = 0;
        for (ImagemDoDocumento imagem : extraido.imagens()) {
            if (imagem.situacaoAlt() != ImagemDoDocumento.SituacaoDoAlt.PRESENTE) {
                continue;
            }
            RespostaMlDTO resposta = ml.predizer(RequisicaoMlDTO.de(imagem.texto()));
            if (!resposta.temPredicao()) {
                // Uma indisponibilidade derruba a predicao de TODAS as imagens
                // deste documento: nao adianta seguir pedindo para um servico
                // que acabou de nao responder, com 1,5 s de timeout cada.
                log.info("sem predicao para analiseId={}: ml-service indisponivel",
                        analiseId);
                return predicoes;
            }
            predicoes.add(PredicaoDeAlt.de(analiseId, indice++, imagem.partePacote(),
                    imagem.nome(), imagem.texto(), resposta.categoria(),
                    resposta.confianca(), resposta.usouHeuristica(),
                    resposta.modeloVersao(), agora));
        }
        return predicoes;
    }

    private void registrarProcessado(@NonNull AnaliseSolicitadaV1 evento) {
        eventoRepository.save(EventoProcessado.de(
                evento.eventId(), NOME_DO_CONSUMIDOR, clock.instant()));
    }
}
