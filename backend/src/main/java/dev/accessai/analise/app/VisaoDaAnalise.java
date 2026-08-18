package dev.accessai.analise.app;

import dev.accessai.analise.dominio.Analise;
import dev.accessai.analise.dominio.Problema;
import dev.accessai.analise.dominio.SituacaoAnalise;
import dev.accessai.analise.score.ScoreDaAnalise;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Uma analise pronta para ser lida por quem esta fora do dominio.
 *
 * <p>Record imutavel, sem nenhuma referencia a entidade JPA. A versao anterior
 * carregava a propria {@code Analise} e a lista de {@code Problema} ate a
 * camada de API, o que quebrava a invariante de CLAUDE.md secao 5 mesmo sem
 * serializar a entidade: bastava alguem chamar um getter fora da transacao para
 * transformar um detalhe de mapeamento em bug de API.
 *
 * <p>A conversao acontece aqui, no app, com a transacao ainda aberta.
 */
public record VisaoDaAnalise(
        UUID analiseId,
        UUID correlationId,
        String nomeArquivo,
        String tipoMimeDetectado,
        long tamanhoBytes,
        String sha256,
        SituacaoAnalise situacao,
        Instant criadaEm,
        Instant atualizadaEm,
        List<ProblemaVisto> problemas,
        ScoreDaAnalise score) {

    public VisaoDaAnalise {
        problemas = List.copyOf(problemas);
    }

    static VisaoDaAnalise de(Analise analise, List<Problema> problemas, ScoreDaAnalise score) {
        return new VisaoDaAnalise(
                analise.getId(),
                analise.getCorrelationId(),
                analise.getNomeArquivo(),
                analise.getTipoMimeDetectado(),
                analise.getTamanhoBytes(),
                analise.getSha256(),
                analise.getSituacao(),
                analise.getCriadaEm(),
                analise.getAtualizadaEm(),
                problemas.stream().map(ProblemaVisto::de).toList(),
                score);
    }

    /** Um problema encontrado, ja fora do dominio persistente. */
    public record ProblemaVisto(
            String regraId,
            String criterioWcag,
            Problema.Nivel nivelWcag,
            Problema.Severidade severidade,
            String partePacote,
            String evidencia) {

        static ProblemaVisto de(Problema p) {
            return new ProblemaVisto(p.getRegraId(), p.getCriterioWcag(), p.getNivelWcag(),
                    p.getSeveridade(), p.getPartePacote(), p.getEvidencia());
        }
    }
}
