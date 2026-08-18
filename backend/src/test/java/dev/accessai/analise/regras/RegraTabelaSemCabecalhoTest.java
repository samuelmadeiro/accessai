package dev.accessai.analise.regras;

import static dev.accessai.analise.regras.Documentos.comTabelas;
import static dev.accessai.analise.regras.Documentos.tabela;
import static org.assertj.core.api.Assertions.assertThat;

import dev.accessai.analise.dominio.Problema;
import dev.accessai.analise.extracao.DocumentoExtraido;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RegraTabelaSemCabecalho")
class RegraTabelaSemCabecalhoTest {

    private final RegraTabelaSemCabecalho regra = new RegraTabelaSemCabecalho();

    @Test
    @DisplayName("tabela sem w:tblHeader vira achado ALTA citando indice e linhas")
    void tabelaSemCabecalho() {
        assertThat(regra.avaliar(comTabelas(tabela(2, 5, false))))
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.severidade()).isEqualTo(Problema.Severidade.ALTA);
                    assertThat(a.evidencia()).contains("tabela 2").contains("5 linhas")
                            .contains("w:tblHeader");
                });
    }

    @Test
    @DisplayName("tabela com cabecalho nao vira achado")
    void tabelaComCabecalho() {
        assertThat(regra.avaliar(comTabelas(tabela(1, 4, true)))).isEmpty();
    }

    @Test
    @DisplayName("tabela vazia nao e cobrada: e recurso de diagramacao")
    void tabelaVazia() {
        assertThat(regra.avaliar(comTabelas(tabela(1, 0, false))))
                .as("cobrar cabecalho de tabela sem linha e inventar problema")
                .isEmpty();
    }

    @Test
    @DisplayName("cada tabela irregular vira um achado")
    void variasTabelas() {
        assertThat(regra.avaliar(comTabelas(
                tabela(1, 3, false), tabela(2, 3, true), tabela(3, 2, false))))
                .hasSize(2);
    }

    @Test
    @DisplayName("documento sem tabela nao produz achado")
    void semTabelas() {
        assertThat(regra.avaliar(DocumentoExtraido.vazio())).isEmpty();
    }

    @Test
    @DisplayName("declara 1.3.1")
    void identificacao() {
        assertThat(regra.id()).isEqualTo("TABELA_SEM_CABECALHO");
        assertThat(regra.criterioWcag()).isEqualTo("1.3.1");
    }
}
