package dev.accessai.analise.app;

import dev.accessai.analise.dominio.Analise;
import dev.accessai.analise.dominio.PredicaoDeAlt;
import dev.accessai.analise.dominio.Problema;
import dev.accessai.analise.dominio.SituacaoAnalise;
import dev.accessai.analise.score.ScoreDaAnalise;
import org.jspecify.annotations.NonNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Uma analise pronta para ser lida por quem esta fora do dominio.
 *
 * <p>Record imutavel, sem nenhuma referencia a entidade JPA. A versao anterior
 * carregava a propria {@code Analise} e a lista de {@code Problema} ate a
 * camada de API, o que quebrava a invariante de CONTRIBUTING.md secao 5 mesmo sem
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
        ScoreDaAnalise score,
        List<PredicaoVista> predicoes) {

    public VisaoDaAnalise {
        problemas = List.copyOf(problemas);
        predicoes = List.copyOf(predicoes);
    }

    /**
     * Qualidade de um alt text, inferida.
     *
     * <p>Fora do score de proposito (CONTRIBUTING.md secao 6). {@code confianca}
     * e nula quando {@code usouHeuristica} e true: regra nao tem probabilidade.
     */
    public record PredicaoVista(String partePacote, String nomeImagem, String alt,
                                String categoria, Double confianca,
                                boolean usouHeuristica, String modeloVersao) {

        static @NonNull PredicaoVista de(@NonNull PredicaoDeAlt p) {
            return new PredicaoVista(p.getPartePacote(), p.getNomeImagem(), p.getAlt(),
                    p.getCategoria(), p.getConfianca(), p.isUsouHeuristica(),
                    p.getModeloVersao());
        }
    }

    static @NonNull VisaoDaAnalise de(@NonNull Analise analise, @NonNull List<Problema> problemas, @NonNull ScoreDaAnalise score,
                                      @NonNull List<PredicaoDeAlt> predicoes) {
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
                score,
                predicoes.stream().map(PredicaoVista::de).toList());
    }

    /** Um problema encontrado, ja fora do dominio persistente. */
    public record ProblemaVisto(
            String regraId,
            String criterioWcag,
            Problema.Nivel nivelWcag,
            Problema.Severidade severidade,
            String partePacote,
            String evidencia) {

        static @NonNull ProblemaVisto de(@NonNull Problema p) {
            return new ProblemaVisto(p.getRegraId(), p.getCriterioWcag(), p.getNivelWcag(),
                    p.getSeveridade(), p.getPartePacote(), p.getEvidencia());
        }
    }
}
