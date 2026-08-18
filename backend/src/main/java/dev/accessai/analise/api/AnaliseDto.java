package dev.accessai.analise.api;

import dev.accessai.analise.app.ServicoDeAnalise;
import dev.accessai.analise.app.VisaoDaAnalise;
import dev.accessai.analise.score.ScoreDaAnalise;
import dev.accessai.analise.score.ScorePorPrincipio;
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
            ScoreDoDocumento score,
            List<ProblemaEncontrado> problemas) {

        public static RespostaDeAnalise de(VisaoDaAnalise visao) {
            List<ProblemaEncontrado> problemas = visao.problemas().stream()
                    .map(ProblemaEncontrado::de)
                    .toList();
            return new RespostaDeAnalise(visao.analiseId(), visao.correlationId(),
                    visao.nomeArquivo(), visao.tipoMimeDetectado(), visao.tamanhoBytes(),
                    visao.sha256(), visao.situacao().name(), visao.criadaEm(),
                    visao.atualizadaEm(), problemas.size(),
                    ScoreDoDocumento.de(visao.score()), problemas);
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

    /**
     * Score do documento.
     *
     * <p>{@code global} vem nulo quando a analise nao concluiu: nao ha nota para
     * documento que ninguem processou, e zero significaria "inacessivel".
     *
     * <p>{@code naoAvaliados} e obrigatorio na resposta, e nao um detalhe: ele
     * diz quais principios WCAG o sistema NAO verificou. Sem esse campo o leitor
     * assume que 100 quer dizer "acessivel", quando quer dizer "acessivel no que
     * foi medido".
     */
    public record ScoreDoDocumento(Integer global, List<CategoriaDoScore> categorias,
                                   List<String> naoAvaliados) {

        public static ScoreDoDocumento de(ScoreDaAnalise score) {
            return new ScoreDoDocumento(
                    score.global(),
                    score.categorias().stream().map(CategoriaDoScore::de).toList(),
                    score.naoAvaliados().stream().map(Enum::name).toList());
        }
    }

    /** Uma categoria do score, com o caminho ate a nota. */
    public record CategoriaDoScore(String principio, String titulo, int score, int peso,
                                   int problemas, int penalidade) {

        public static CategoriaDoScore de(ScorePorPrincipio categoria) {
            return new CategoriaDoScore(categoria.principio().name(), categoria.titulo(),
                    categoria.score(), categoria.peso(), categoria.problemas(),
                    categoria.penalidade());
        }
    }

    /** Corpo de erro uniforme. */
    public record Erro(String codigo, String mensagem) {
    }
}
