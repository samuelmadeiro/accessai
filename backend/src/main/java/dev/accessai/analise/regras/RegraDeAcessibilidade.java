package dev.accessai.analise.regras;

import dev.accessai.analise.extracao.ImagemDoDocumento;
import java.util.List;

/**
 * Uma regra deterministica do Rule Engine.
 *
 * <p>A regra declara apenas o identificador do criterio WCAG. Nivel e titulo
 * sao resolvidos pelo {@link CatalogoWcag} — assim uma regra nao consegue
 * inventar nivel (CLAUDE.md secao 6).
 *
 * <p>ML e IA nao entram aqui. Alt ausente e deteccao deterministica: usar
 * modelo para isso violaria a ordem de precedencia da secao 2.
 */
public interface RegraDeAcessibilidade {

    /** Identificador estavel da regra, usado na evidencia e nos relatorios. */
    String id();

    /** Identificador do criterio WCAG que esta regra verifica. */
    String criterioWcag();

    List<Achado> avaliar(List<ImagemDoDocumento> imagens);
}
