package dev.accessai.analise.regras;

import static dev.accessai.analise.regras.Documentos.comIdiomas;
import static org.assertj.core.api.Assertions.assertThat;

import dev.accessai.analise.dominio.Problema;
import dev.accessai.analise.extracao.IdiomaDeclarado;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RegraIdiomaNaoDeclarado")
class RegraIdiomaNaoDeclaradoTest {

    private final RegraIdiomaNaoDeclarado regra = new RegraIdiomaNaoDeclarado();

    @Test
    @DisplayName("nenhum w:lang vira achado ALTA apontando word/styles.xml")
    void nenhumIdioma() {
        assertThat(regra.avaliar(comIdiomas()))
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.severidade()).isEqualTo(Problema.Severidade.ALTA);
                    assertThat(a.partePacote()).isEqualTo("word/styles.xml");
                    assertThat(a.evidencia()).contains("nao declara idioma em lugar nenhum");
                });
    }

    @Test
    @DisplayName("idioma no padrao do documento satisfaz o criterio")
    void idiomaPadrao() {
        assertThat(regra.avaliar(comIdiomas(
                new IdiomaDeclarado("word/styles.xml", "pt-BR")))).isEmpty();
    }

    @Test
    @DisplayName("idioma so em trechos nao satisfaz, e a evidencia diz isso")
    void idiomaApenasLocal() {
        assertThat(regra.avaliar(comIdiomas(
                new IdiomaDeclarado("word/document.xml", "pt-BR"),
                new IdiomaDeclarado("word/document.xml", "en-US"))))
                .singleElement()
                .extracting(Achado::evidencia).asString()
                .contains("apenas em trechos").contains("pt-BR").contains("en-US");
    }

    @Test
    @DisplayName("padrao presente junto com locais continua conforme")
    void padraoMaisLocais() {
        assertThat(regra.avaliar(comIdiomas(
                new IdiomaDeclarado("word/styles.xml", "pt-BR"),
                new IdiomaDeclarado("word/document.xml", "en-US")))).isEmpty();
    }

    @Test
    @DisplayName("declara 3.1.1")
    void identificacao() {
        assertThat(regra.id()).isEqualTo("IDIOMA_NAO_DECLARADO");
        assertThat(regra.criterioWcag()).isEqualTo("3.1.1");
    }
}
