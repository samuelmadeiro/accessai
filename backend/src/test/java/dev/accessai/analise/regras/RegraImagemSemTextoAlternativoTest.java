package dev.accessai.analise.regras;

import static org.assertj.core.api.Assertions.assertThat;

import dev.accessai.analise.dominio.Problema;
import dev.accessai.analise.extracao.ImagemDoDocumento;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RegraImagemSemTextoAlternativo")
class RegraImagemSemTextoAlternativoTest {

    private final RegraImagemSemTextoAlternativo regra = new RegraImagemSemTextoAlternativo();

    @Test
    @DisplayName("alt ausente vira achado de severidade ALTA com a parte do pacote")
    void altAusenteViraAchado() {
        List<Achado> achados = regra.avaliar(List.of(
                ImagemDoDocumento.de("word/header1.xml", "brasao.png", null)));

        assertThat(achados).singleElement().satisfies(a -> {
            assertThat(a.severidade()).isEqualTo(Problema.Severidade.ALTA);
            assertThat(a.partePacote()).isEqualTo("word/header1.xml");
            assertThat(a.evidencia()).contains("brasao.png").contains("descr");
        });
    }

    @Test
    @DisplayName("alt vazio nao e achado: e declaracao de imagem decorativa")
    void altVazioNaoEhAchado() {
        assertThat(regra.avaliar(List.of(
                ImagemDoDocumento.de("word/document.xml", "linha.png", ""),
                ImagemDoDocumento.de("word/document.xml", "espaco.png", "   "))))
                .isEmpty();
    }

    @Test
    @DisplayName("alt preenchido nao e achado")
    void altPreenchidoNaoEhAchado() {
        assertThat(regra.avaliar(List.of(
                ImagemDoDocumento.de("word/document.xml", "mapa.png", "Mapa da regiao sul"))))
                .isEmpty();
    }

    @Test
    @DisplayName("imagem sem nome ainda produz evidencia legivel")
    void imagemSemNome() {
        List<Achado> achados = regra.avaliar(List.of(
                ImagemDoDocumento.de("word/document.xml", null, null)));

        assertThat(achados).singleElement()
                .extracting(Achado::evidencia)
                .asString()
                .contains("(sem nome)");
    }

    @Test
    @DisplayName("declara 1.1.1 e um id estavel")
    void identificacao() {
        assertThat(regra.id()).isEqualTo("IMAGEM_SEM_TEXTO_ALTERNATIVO");
        assertThat(regra.criterioWcag()).isEqualTo("1.1.1");
    }
}
