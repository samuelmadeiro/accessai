package dev.accessai.analise.regras;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.accessai.analise.dominio.Problema;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Testes da tabela WCAG.
 *
 * <p>Metade dos casos usa o arquivo REAL de {@code docs/wcag/criteria.json},
 * carregado do classpath: se o arquivo quebrar ou perder um campo, o teste
 * acusa. Testar so contra tabela sintetica deixaria justamente o contrato do
 * projeto sem cobertura.
 */
@DisplayName("CatalogoWcag")
class CatalogoWcagTest {

    private final CatalogoWcag catalogoReal = new CatalogoWcag(new ObjectMapper());

    @Test
    @DisplayName("o arquivo versionado carrega e o 1.1.1 vem completo")
    void carregaArquivoReal() {
        CatalogoWcag.Criterio criterio = catalogoReal.buscar("1.1.1");

        assertThat(criterio.titulo()).isEqualTo("Non-text Content");
        assertThat(criterio.nivel()).isEqualTo(Problema.Nivel.A);
        assertThat(criterio.aplicabilidadeIct())
                .isEqualTo(CatalogoWcag.AplicabilidadeIct.DIRETA);
        assertThat(criterio.geraViolacao()).isTrue();
    }

    @Test
    @DisplayName("a procedencia WCAG2ICT vem junto, com o status informativo")
    void fonteIct() {
        CatalogoWcag.Fonte fonte = catalogoReal.fonte();

        assertThat(fonte.wcag().versao()).isEqualTo("2.2");
        assertThat(fonte.ict().documento()).isEqualTo("WCAG2ICT");
        assertThat(fonte.ict().status())
                .as("o disclaimer de norma informativa e obrigatorio")
                .contains("informativo");
    }

    @Test
    @DisplayName("criterio fora da tabela e erro alto, com a lista do que existe")
    void criterioDesconhecido() {
        assertThatThrownBy(() -> catalogoReal.buscar("9.9.9"))
                .isInstanceOf(CatalogoWcag.CriterioDesconhecidoException.class)
                .hasMessageContaining("9.9.9")
                .hasMessageContaining("1.1.1");
    }

    @Test
    @DisplayName("criterio inaplicavel a documento nao-web nao gera violacao")
    void criterioInaplicavelNaoGeraViolacao() {
        CatalogoWcag catalogo = catalogoCom(criterio("2.4.5", CatalogoWcag.AplicabilidadeIct.INAPLICAVEL));

        assertThat(catalogo.buscar("2.4.5").geraViolacao())
                .as("WCAG2ICT marcou como inaplicavel: vira recomendacao, nunca violacao")
                .isFalse();
    }

    @Test
    @DisplayName("criterio com substituicao de termo continua gerando violacao")
    void criterioComSubstituicao() {
        assertThat(catalogoCom(criterio("2.4.2", CatalogoWcag.AplicabilidadeIct.COM_SUBSTITUICAO))
                .buscar("2.4.2").geraViolacao()).isTrue();
    }

    @Test
    @DisplayName("aplicabilidadeIct e enum fechado: valor desconhecido derruba a carga")
    void aplicabilidadeDesconhecidaFalha() {
        // Condicao C-1 de fase-0.md: "e um enum fechado (...) isso vira validacao
        // no build, nao convencao". Enquanto era String livre, um valor torto
        // carregava calado e o criterio passava a GERAR violacao — a falha ia
        // para o lado errado.
        String tabela = """
                {"leiaAntes": [], "fonte": null, "criterios": [
                  {"id": "9.9.9", "titulo": "t", "nivel": "A",
                   "aplicabilidadeIct": "talvez", "substituicoes": [],
                   "notaIct": "n", "resumo": "r"}]}""";

        assertThatThrownBy(() -> new ObjectMapper()
                .readValue(tabela, CatalogoWcag.Tabela.class))
                .hasRootCauseInstanceOf(CatalogoWcag.AplicabilidadeDesconhecidaException.class)
                .rootCause()
                .hasMessageContaining("talvez")
                .hasMessageContaining("C-1");
    }

    @Test
    @DisplayName("os tres valores do enum sao aceitos, e so eles")
    void enumFechadoTemTresValores() {
        assertThat(CatalogoWcag.AplicabilidadeIct.values())
                .containsExactly(CatalogoWcag.AplicabilidadeIct.DIRETA,
                        CatalogoWcag.AplicabilidadeIct.COM_SUBSTITUICAO,
                        CatalogoWcag.AplicabilidadeIct.INAPLICAVEL);
    }

    @Test
    @DisplayName("todo criterio do arquivo real declara uma aplicabilidade valida")
    void arquivoRealSoTemAplicabilidadeConhecida() {
        // Se o arquivo ganhar um criterio novo com valor torto, este teste cai
        // antes de a aplicacao subir com ele.
        for (String id : List.of("1.1.1", "1.3.1", "2.4.2", "2.4.4", "3.1.1")) {
            assertThat(catalogoReal.buscar(id).aplicabilidadeIct())
                    .as("criterio %s", id)
                    .isIn((Object[]) CatalogoWcag.AplicabilidadeIct.values());
        }
    }

    // ------------------------------------------------------------------

    static CatalogoWcag catalogoCom(CatalogoWcag.Criterio... criterios) {
        return CatalogoWcag.deTabela(new CatalogoWcag.Tabela(
                List.of(),
                new CatalogoWcag.Fonte(
                        new CatalogoWcag.Fonte.Wcag("2.2", "https://www.w3.org/TR/WCAG22/"),
                        new CatalogoWcag.Fonte.Ict("WCAG2ICT", "titulo",
                                "W3C Group Note — informativo, nao normativo",
                                "2025-12-11", "A e AA", "https://www.w3.org/TR/wcag2ict-22/")),
                List.of(criterios)));
    }

    static CatalogoWcag.Criterio criterio(String id,
            CatalogoWcag.AplicabilidadeIct aplicabilidade) {
        return new CatalogoWcag.Criterio(id, "titulo de teste", Problema.Nivel.AA,
                aplicabilidade, List.of(), "nota", "resumo");
    }
}
