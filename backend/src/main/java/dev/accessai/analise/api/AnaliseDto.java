package dev.accessai.analise.api;

import dev.accessai.analise.app.ServicoDeAnalise;
import dev.accessai.analise.app.VisaoDaAnalise;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Representacoes de saida da API.
 *
 * <p>Entidade JPA nunca cruza a fronteira da API (CLAUDE.md secao 5). A
 * conversao mora aqui, na borda, e nao dentro do dominio.
 */
public final class AnaliseDto {

    private AnaliseDto() {
    }

    /** Resposta do POST /analyses. */
    public record RespostaDeRecebimento(UUID analiseId, UUID correlationId, String situacao) {

        public static RespostaDeRecebimento de(ServicoDeAnalise.ResultadoDoRecebimento resultado) {
            return new RespostaDeRecebimento(resultado.analiseId(), resultado.correlationId(),
                    resultado.situacao().name());
        }
    }

    /** Resposta do GET /analyses/{id}. */
    public record RespostaDeAnalise(
            UUID analiseId,
            UUID correlationId,
            String nomeArquivo,
            String tipoMimeDetectado,
            long tamanhoBytes,
            String sha256,
            String situacao,
            Instant criadaEm,
            Instant atualizadaEm,
            int totalDeProblemas,
            List<ProblemaEncontrado> problemas) {

        public static RespostaDeAnalise de(VisaoDaAnalise visao) {
            List<ProblemaEncontrado> problemas = visao.problemas().stream()
                    .map(ProblemaEncontrado::de)
                    .toList();
            return new RespostaDeAnalise(visao.analiseId(), visao.correlationId(),
                    visao.nomeArquivo(), visao.tipoMimeDetectado(), visao.tamanhoBytes(),
                    visao.sha256(), visao.situacao().name(), visao.criadaEm(),
                    visao.atualizadaEm(), problemas.size(), problemas);
        }
    }

    public record ProblemaEncontrado(
            String regraId,
            String criterioWcag,
            String nivelWcag,
            String severidade,
            String partePacote,
            String evidencia) {

        public static ProblemaEncontrado de(VisaoDaAnalise.ProblemaVisto p) {
            return new ProblemaEncontrado(p.regraId(), p.criterioWcag(),
                    p.nivelWcag().name(), p.severidade().name(),
                    p.partePacote(), p.evidencia());
        }
    }

    /** Corpo de erro uniforme. */
    public record Erro(String codigo, String mensagem) {
    }
}
