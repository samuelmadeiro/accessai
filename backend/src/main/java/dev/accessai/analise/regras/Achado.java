package dev.accessai.analise.regras;

import dev.accessai.analise.dominio.Problema;

/**
 * O que uma regra encontrou, antes de virar {@link Problema}.
 *
 * <p>Nao carrega nivel WCAG: quem resolve nivel e o {@link CatalogoWcag}.
 *
 * @param partePacote parte do pacote OOXML onde esta o problema
 * @param evidencia   texto curto que permite a uma pessoa achar o problema no
 *                    documento; nunca uma mensagem generica
 */
public record Achado(Problema.Severidade severidade, String partePacote, String evidencia) {
}
