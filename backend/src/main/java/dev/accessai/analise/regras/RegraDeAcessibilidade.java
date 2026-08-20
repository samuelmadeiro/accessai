package dev.accessai.analise.regras;

import dev.accessai.analise.extracao.DocumentoExtraido;
import java.util.List;

/**
 * Uma regra deterministica do Rule Engine.
 *
 * <p>A regra declara apenas o identificador do criterio WCAG. Nivel e titulo
 * sao resolvidos pelo {@link CatalogoWcag} — assim uma regra nao consegue
 * inventar nivel (CONTRIBUTING.md secao 6).
 *
 * <p>Recebe o {@link DocumentoExtraido} inteiro, e nao a lista do que lhe
 * interessa. Com uma regra so, passar {@code List&lt;ImagemDoDocumento&gt;} era
 * mais honesto; com seis, cada regra pediria um parametro diferente e a
 * assinatura mudaria a cada regra nova. Uma regra olha o que precisa e ignora o
 * resto.
 *
 * <p>ML e IA nao entram aqui. Alt ausente, tabela sem cabecalho e idioma nao
 * declarado sao deteccao deterministica: usar modelo para isso violaria a ordem
 * de precedencia da secao 2.
 */
public interface RegraDeAcessibilidade {

    /** Identificador estavel da regra, usado na evidencia e nos relatorios. */
    String id();

    /** Identificador do criterio WCAG que esta regra verifica. */
    String criterioWcag();

    List<Achado> avaliar(DocumentoExtraido documento);
}
