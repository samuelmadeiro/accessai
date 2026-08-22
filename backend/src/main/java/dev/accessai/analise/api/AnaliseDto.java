package dev.accessai.analise.api;

import dev.accessai.analise.app.ServicoDeAnalise;
import dev.accessai.analise.app.VisaoDaAnalise;
import dev.accessai.analise.score.ScoreDaAnalise;
import dev.accessai.analise.score.ScorePorPrincipio;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Representacoes de saida da API.
 *
 * <p>Entidade JPA nunca cruza a fronteira da API (CONTRIBUTING.md secao 5). A
 * conversao mora aqui, na borda, e nao dentro do dominio.
 */
public final class AnaliseDto {

    private AnaliseDto() {
    }

    /** Resposta do POST /analyses. */
    public record RespostaDeRecebimento(UUID analiseId, UUID correlationId, String situacao) {

        public static @NonNull RespostaDeRecebimento de(ServicoDeAnalise.@NonNull ResultadoDoRecebimento resultado) {
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
            List<ProblemaEncontrado> problemas,
            List<PredicaoDeAltText> predicoesDeAlt) {

        public static @NonNull RespostaDeAnalise de(@NonNull VisaoDaAnalise visao) {
            List<ProblemaEncontrado> problemas = visao.problemas().stream()
                    .map(ProblemaEncontrado::de)
                    .toList();
            List<PredicaoDeAltText> predicoes = visao.predicoes().stream()
                    .map(PredicaoDeAltText::de)
                    .toList();
            return new RespostaDeAnalise(visao.analiseId(), visao.correlationId(),
                    visao.nomeArquivo(), visao.tipoMimeDetectado(), visao.tamanhoBytes(),
                    visao.sha256(), visao.situacao().name(), visao.criadaEm(),
                    visao.atualizadaEm(), problemas.size(),
                    ScoreDoDocumento.de(visao.score()), problemas, predicoes);
        }
    }

    /**
     * A qualidade de um texto alternativo, inferida pelo ML Service.
     *
     * <p>Sai FORA do score de proposito: o score e soma ponderada de penalidades
     * deterministicas, e cada ponto perdido rastreia ate uma regra com evidencia
     * (CONTRIBUTING.md secao 6). Predicao que somasse penalidade quebraria isso.
     *
     * <p>{@code usouHeuristica} nao e detalhe interno: e a diferenca entre "um
     * modelo classificou isto" e "um punhado de regras classificou isto". Hoje
     * ele e SEMPRE true — nao ha modelo treinado. {@code confianca} e nula
     * quando ele e true, porque regra nao tem probabilidade.
     *
     * <p>Lista vazia significa uma de duas coisas: o documento nao tem imagem
     * com alt, ou o ML Service estava indisponivel quando a analise rodou.
     */
    public record PredicaoDeAltText(String partePacote, String nomeImagem, String alt,
                                    String categoria, Double confianca,
                                    boolean usouHeuristica, String modeloVersao) {

        public static @NonNull PredicaoDeAltText de(VisaoDaAnalise.@NonNull PredicaoVista p) {
            return new PredicaoDeAltText(p.partePacote(), p.nomeImagem(), p.alt(),
                    p.categoria(), p.confianca(), p.usouHeuristica(), p.modeloVersao());
        }
    }

    public record ProblemaEncontrado(
            String regraId,
            String criterioWcag,
            String nivelWcag,
            String severidade,
            String partePacote,
            String evidencia) {

        public static @NonNull ProblemaEncontrado de(VisaoDaAnalise.@NonNull ProblemaVisto p) {
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

        public static @NonNull ScoreDoDocumento de(@NonNull ScoreDaAnalise score) {
            return new ScoreDoDocumento(
                    score.global(),
                    score.categorias().stream().map(CategoriaDoScore::de).toList(),
                    score.naoAvaliados().stream().map(Enum::name).toList());
        }
    }

    /** Uma categoria do score, com o caminho ate a nota. */
    public record CategoriaDoScore(String principio, String titulo, int score, int peso,
                                   int problemas, int penalidade) {

        public static @NonNull CategoriaDoScore de(@NonNull ScorePorPrincipio categoria) {
            return new CategoriaDoScore(categoria.principio().name(), categoria.titulo(),
                    categoria.score(), categoria.peso(), categoria.problemas(),
                    categoria.penalidade());
        }
    }

    /** Corpo de erro uniforme. */
    public record Erro(String codigo, String mensagem) {
    }
}
