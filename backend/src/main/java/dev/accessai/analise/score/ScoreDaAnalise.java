package dev.accessai.analise.score;

import dev.accessai.analise.dominio.PrincipioWcag;
import java.util.List;

/**
 * O score de uma analise: nota global e a decomposicao por principio WCAG.
 *
 * <p>{@code global} e {@code Integer} e nao {@code int} de proposito:
 * {@code null} significa "nao calculado", e e diferente de zero. Zero seria a
 * afirmacao de que o documento e inacessivel; null e a afirmacao de que ninguem
 * mediu. Um documento ainda em processamento cai no segundo caso.
 *
 * @param global          0 a 100, media ponderada dos principios avaliados
 * @param categorias      um item por principio avaliado
 * @param naoAvaliados    principios sem nenhuma regra implementada — eles ficam
 *                        FORA da media, com os pesos renormalizados, em vez de
 *                        entrarem valendo 100. Dar nota cheia a um principio que
 *                        o sistema nem verifica e afirmar conformidade
 *                        inexistente, que e o defeito que derrubou o Apache POI
 *                        na Slice 1 (ADR 0008).
 */
public record ScoreDaAnalise(Integer global, List<ScorePorPrincipio> categorias,
                             List<PrincipioWcag> naoAvaliados) {

    public ScoreDaAnalise {
        categorias = List.copyOf(categorias);
        naoAvaliados = List.copyOf(naoAvaliados);
    }

    /** Analise que ainda nao foi processada, ou que falhou: nao ha o que pontuar. */
    public static ScoreDaAnalise naoCalculado() {
        return new ScoreDaAnalise(null, List.of(), List.of());
    }

    public boolean foiCalculado() {
        return global != null;
    }
}
