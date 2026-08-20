package dev.accessai.analise.app;

import dev.accessai.analise.dominio.Analise;
import dev.accessai.analise.dominio.AnaliseRepository;
import dev.accessai.analise.dominio.DocumentoBinario;
import dev.accessai.analise.dominio.DocumentoBinarioRepository;
import dev.accessai.analise.evento.AnaliseSolicitadaV1;
import dev.accessai.analise.outbox.EventoDeOutbox;
import dev.accessai.analise.outbox.EventoDeOutboxRepository;
import dev.accessai.config.PropriedadesAccessAi;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Grava analise, binario e o evento do outbox numa unica transacao.
 *
 * <p>As tres gravacoes juntas sao o padrao outbox. Na Slice 1 o evento era
 * publicado no Kafka DEPOIS do commit, e morrer entre uma coisa e outra deixava
 * a analise em RECEBIDA para sempre — gravada e invisivel. Agora ou tudo
 * acontece, ou nada: o commit e a unica coisa que precisa dar certo.
 *
 * <p>Existe como colaborador separado de {@link ServicoDeAnalise} por um motivo
 * mecanico, nao estetico: {@code @Transactional} em metodo chamado de dentro da
 * propria classe nao passa pelo proxy do Spring e a transacao simplesmente nao
 * acontece. A falha e silenciosa — grava, funciona no teste feliz, e so aparece
 * quando algo precisa de rollback.
 */
@Component
public class RegistroDeAnalise {

    private final AnaliseRepository analiseRepository;
    private final DocumentoBinarioRepository documentoRepository;
    private final EventoDeOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final String topico;

    public RegistroDeAnalise(AnaliseRepository analiseRepository,
                             DocumentoBinarioRepository documentoRepository,
                             EventoDeOutboxRepository outboxRepository,
                             ObjectMapper objectMapper,
                             PropriedadesAccessAi propriedades) {
        this.analiseRepository = analiseRepository;
        this.documentoRepository = documentoRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.topico = propriedades.kafka().topicoAnaliseSolicitada();
    }

    @Transactional
    public Analise registrar(byte[] conteudo, String nomeArquivo, String tipoDetectado,
                             String sha256, UUID correlationId, Instant agora) {
        Analise analise = analiseRepository.save(Analise.receber(
                correlationId, nomeArquivo, tipoDetectado, conteudo.length, sha256, agora));
        documentoRepository.save(DocumentoBinario.de(analise.getId(), conteudo));

        AnaliseSolicitadaV1 evento = AnaliseSolicitadaV1.de(
                correlationId, analise.getId(), nomeArquivo, sha256, agora);
        outboxRepository.save(EventoDeOutbox.pendente(
                evento.eventId(),
                analise.getId(),
                AnaliseSolicitadaV1.class.getSimpleName(),
                topico,
                // A chave e o analiseId: tudo de uma mesma analise cai na mesma
                // particao e portanto e processado em ordem.
                analise.getId().toString(),
                objectMapper.writeValueAsString(evento),
                correlationId,
                agora));

        return analise;
    }
}
