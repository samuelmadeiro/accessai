package dev.accessai.analise.score;

import dev.accessai.analise.dominio.PrincipioWcag;

/**
 * Nota de um principio WCAG, com o caminho inteiro ate ela.
 *
 * <p>Os campos intermediarios (penalidade, problemas, peso) existem porque
 * CLAUDE.md secao 6 exige que cada ponto perdido rastreie ate um problema
 * especifico. Devolver so a nota faria o score virar numero de oraculo — o
 * mesmo defeito de um score predito por ML, que a secao 6 proibe.
 *
 * @param principio  o principio avaliado
 * @param titulo     nome legivel, para a interface nao ter que traduzir enum
 * @param score      0 a 100, ja com a penalidade descontada
 * @param peso       peso deste principio na media global
 * @param problemas  quantos problemas caem neste principio
 * @param penalidade soma das penalidades por severidade, antes do corte em 0
 */
public record ScorePorPrincipio(PrincipioWcag principio, String titulo, int score, int peso,
                                int problemas, int penalidade) {
}
