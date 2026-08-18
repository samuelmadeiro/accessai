package dev.accessai.analise.regras;

import static dev.accessai.analise.regras.Documentos.comImagens;
import static org.assertj.core.api.Assertions.assertThat;

import dev.accessai.analise.dominio.Problema;
import dev.accessai.analise.extracao.DocumentoExtraido;
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
        DocumentoExtraido doc = comImagens(
                ImagemDoDocumento.de("word/header1.xml", "brasao.png", null));

        List<Achado> achados = regra.avaliar(doc);

        assertThat(achados).singleElement().satisfies(a -> {
            assertThat(a.severidade()).isEqualTo(Problema.Severidade.ALTA);
            assertThat(a.partePacote()).isEqualTo("word/header1.xml");
            assertThat(a.evidencia()).contains("brasao.png").contains("descr");
        });
    }

    @Test
    @DisplayName("alt vazio nao e achado: e declaracao de imagem decorativa")
    void altVazioNaoEhAchado() {
        DocumentoExtraido doc = comImagens(
                ImagemDoDocumento.de("word/document.xml", "linha.png", ""),
                ImagemDoDocumento.de("word/document.xml", "espaco.png", "   "));

        assertThat(regra.avaliar(doc)).isEmpty();
    }

    @Test
    @DisplayName("alt preenchido nao e achado")
    void altPreenchidoNaoEhAchado() {
        DocumentoExtraido doc = comImagens(
                ImagemDoDocumento.de("word/document.xml", "mapa.png", "Mapa da regiao sul"));

        assertThat(regra.avaliar(doc)).isEmpty();
    }

    @Test
    @DisplayName("imagem sem nome ainda produz evidencia legivel")
    void imagemSemNome() {
        DocumentoExtraido doc = comImagens(
                ImagemDoDocumento.de("word/document.xml", null, null));

        assertThat(regra.avaliar(doc)).singleElement()
                .extracting(Achado::evidencia).asString().contains("(sem nome)");
    }

    @Test
    @DisplayName("documento sem imagem nenhuma nao produz achado")
    void semImagens() {
        assertThat(regra.avaliar(DocumentoExtraido.vazio())).isEmpty();
    }

    @Test
    @DisplayName("declara 1.1.1 e um id estavel")
    void identificacao() {
        assertThat(regra.id()).isEqualTo("IMAGEM_SEM_TEXTO_ALTERNATIVO");
        assertThat(regra.criterioWcag()).isEqualTo("1.1.1");
    }
}
