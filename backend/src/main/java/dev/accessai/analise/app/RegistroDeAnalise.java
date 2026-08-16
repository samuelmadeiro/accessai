package dev.accessai.analise.app;

import dev.accessai.analise.dominio.Analise;
import dev.accessai.analise.dominio.AnaliseRepository;
import dev.accessai.analise.dominio.DocumentoBinario;
import dev.accessai.analise.dominio.DocumentoBinarioRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Grava analise e binario numa unica transacao.
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

    public RegistroDeAnalise(AnaliseRepository analiseRepository,
                             DocumentoBinarioRepository documentoRepository) {
        this.analiseRepository = analiseRepository;
        this.documentoRepository = documentoRepository;
    }

    @Transactional
    public Analise registrar(byte[] conteudo, String nomeArquivo, String tipoDetectado,
                             String sha256, UUID correlationId, Instant agora) {
        Analise analise = analiseRepository.save(Analise.receber(
                correlationId, nomeArquivo, tipoDetectado, conteudo.length, sha256, agora));
        documentoRepository.save(DocumentoBinario.de(analise.getId(), conteudo));
        return analise;
    }
}
