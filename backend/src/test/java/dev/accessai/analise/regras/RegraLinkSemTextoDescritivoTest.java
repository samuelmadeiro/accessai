package dev.accessai.analise.regras;

import static dev.accessai.analise.regras.Documentos.comLinks;
import static dev.accessai.analise.regras.Documentos.link;
import static org.assertj.core.api.Assertions.assertThat;

import dev.accessai.analise.dominio.Problema;
import dev.accessai.analise.extracao.DocumentoExtraido;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("RegraLinkSemTextoDescritivo")
class RegraLinkSemTextoDescritivoTest {

    private final RegraLinkSemTextoDescritivo regra = new RegraLinkSemTextoDescritivo();

    @ParameterizedTest
    @ValueSource(strings = {"clique aqui", "Clique aqui", "CLIQUE AQUI", "Clique aqui!",
            "  saiba mais  ", "Saiba Mais", "leia mais", "aqui", "download", "click here",
            "Mais informacoes", "Mais informações"})
    @DisplayName("texto generico vira achado, independente de caixa, acento e pontuacao")
    void textoGenerico(String texto) {
        assertThat(regra.avaliar(comLinks(link(texto, "https://gov.br/edital.pdf"))))
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.severidade()).isEqualTo(Problema.Severidade.MEDIA);
                    assertThat(a.evidencia()).contains("generico");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"Edital de Fomento 3/2026", "Anexo I - modelo de declaracao",
            "Portaria 145", "Formulario de inscricao"})
    @DisplayName("texto descritivo nao vira achado, mesmo curto")
    void textoDescritivo(String texto) {
        assertThat(regra.avaliar(comLinks(link(texto, "https://gov.br/x.pdf"))))
                .as("heuristica de tamanho geraria falso positivo em 'Portaria 145'")
                .isEmpty();
    }

    @Test
    @DisplayName("URL como texto visivel vira achado")
    void urlComoTexto() {
        assertThat(regra.avaliar(comLinks(
                link("https://prefeitura.gov.br/editais/2026/fomento.pdf",
                        "https://prefeitura.gov.br/editais/2026/fomento.pdf"))))
                .singleElement()
                .extracting(Achado::evidencia).asString()
                .contains("a propria URL");
    }

    @Test
    @DisplayName("texto que comeca com www tambem e URL")
    void textoComWww() {
        assertThat(regra.avaliar(comLinks(link("www.gov.br/acessibilidade", null)))).hasSize(1);
    }

    @Test
    @DisplayName("texto igual ao destino vira achado mesmo sem parecer URL")
    void textoIgualAoDestino() {
        assertThat(regra.avaliar(comLinks(link("edital.pdf", "edital.pdf")))).hasSize(1);
    }

    @Test
    @DisplayName("link sem texto fica para a regra de texto alternativo")
    void linkSemTexto() {
        assertThat(regra.avaliar(comLinks(link("   ", "https://gov.br"))))
                .as("link vazio costuma envolver imagem; marcar aqui duplicaria o problema")
                .isEmpty();
    }

    @Test
    @DisplayName("a evidencia mostra o destino quando ele existe")
    void evidenciaComDestino() {
        assertThat(regra.avaliar(comLinks(link("clique aqui", "https://gov.br/x.pdf"))))
                .singleElement()
                .extracting(Achado::evidencia).asString()
                .contains("https://gov.br/x.pdf");
    }

    @Test
    @DisplayName("documento sem link nao produz achado")
    void semLinks() {
        assertThat(regra.avaliar(DocumentoExtraido.vazio())).isEmpty();
    }

    @Test
    @DisplayName("declara 2.4.4")
    void identificacao() {
        assertThat(regra.id()).isEqualTo("LINK_SEM_TEXTO_DESCRITIVO");
        assertThat(regra.criterioWcag()).isEqualTo("2.4.4");
    }
}
