package dev.accessai.analise.regras;

import static dev.accessai.analise.regras.Documentos.comCabecalhos;
import static dev.accessai.analise.regras.Documentos.titulo;
import static org.assertj.core.api.Assertions.assertThat;

import dev.accessai.analise.dominio.Problema;
import dev.accessai.analise.extracao.DocumentoExtraido;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RegraOrdemHierarquicaCabecalhos")
class RegraOrdemHierarquicaCabecalhosTest {

    private final RegraOrdemHierarquicaCabecalhos regra = new RegraOrdemHierarquicaCabecalhos();

    @Test
    @DisplayName("H1 seguido de H3 e salto")
    void saltoDeUmParaTres() {
        assertThat(regra.avaliar(comCabecalhos(titulo(1, "Edital"), titulo(3, "Anexo I"))))
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.severidade()).isEqualTo(Problema.Severidade.MEDIA);
                    assertThat(a.evidencia()).contains("H3").contains("H1").contains("Anexo I");
                });
    }

    @Test
    @DisplayName("hierarquia sem buraco nao vira achado")
    void hierarquiaCorreta() {
        assertThat(regra.avaliar(comCabecalhos(
                titulo(1, "Edital"), titulo(2, "Objeto"), titulo(3, "Detalhe"),
                titulo(2, "Prazos"))))
                .isEmpty();
    }

    @Test
    @DisplayName("voltar de H3 para H1 nao e salto: e fim de secao")
    void subirNivelNaoEhSalto() {
        assertThat(regra.avaliar(comCabecalhos(
                titulo(1, "A"), titulo(2, "B"), titulo(3, "C"), titulo(1, "D"))))
                .as("tratar subida como erro encheria de falso positivo qualquer documento")
                .isEmpty();
    }

    @Test
    @DisplayName("documento que comeca em H2 tem o mesmo degrau faltando")
    void primeiroTituloNaoEhH1() {
        assertThat(regra.avaliar(comCabecalhos(titulo(2, "Objeto"))))
                .singleElement()
                .extracting(Achado::evidencia).asString()
                .contains("primeiro titulo").contains("H2");
    }

    @Test
    @DisplayName("documento que comeca em H1 esta certo")
    void primeiroTituloEhH1() {
        assertThat(regra.avaliar(comCabecalhos(titulo(1, "Edital")))).isEmpty();
    }

    @Test
    @DisplayName("cada salto vira um achado")
    void variosSaltos() {
        assertThat(regra.avaliar(comCabecalhos(
                titulo(1, "A"), titulo(3, "B"), titulo(2, "C"), titulo(5, "D"))))
                .hasSize(2);
    }

    @Test
    @DisplayName("titulo sem texto ainda produz evidencia legivel")
    void tituloSemTexto() {
        assertThat(regra.avaliar(comCabecalhos(titulo(3, "   "))))
                .singleElement()
                .extracting(Achado::evidencia).asString()
                .contains("(sem texto)");
    }

    @Test
    @DisplayName("documento sem titulo nenhum nao produz achado")
    void semCabecalhos() {
        assertThat(regra.avaliar(DocumentoExtraido.vazio()))
                .as("documento sem estrutura de titulos nao tem hierarquia para violar")
                .isEmpty();
    }

    @Test
    @DisplayName("declara 1.3.1")
    void identificacao() {
        assertThat(regra.id()).isEqualTo("ORDEM_HIERARQUICA_CABECALHOS");
        assertThat(regra.criterioWcag()).isEqualTo("1.3.1");
    }
}
