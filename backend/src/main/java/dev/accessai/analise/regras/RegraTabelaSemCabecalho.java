package dev.accessai.analise.regras;

import dev.accessai.analise.dominio.Problema;
import dev.accessai.analise.extracao.DocumentoExtraido;
import dev.accessai.analise.extracao.TabelaDoDocumento;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Tabela cuja primeira linha nao esta marcada como cabecalho — WCAG 1.3.1,
 * nivel A.
 *
 * <p>Sem a marcacao, o leitor de tela le a tabela como uma sequencia de celulas
 * soltas: quem navega por audio perde a relacao entre o valor e a coluna a que
 * ele pertence. Negrito na primeira linha nao resolve — a informacao precisa ser
 * programaticamente determinavel, e formatacao nao e.
 *
 * <p><b>Tabela vazia nao entra.</b> Tabela sem linha nenhuma aparece em
 * documento real como recurso de diagramacao; cobrar cabecalho dela seria
 * inventar problema.
 */
@Component
public class RegraTabelaSemCabecalho implements RegraDeAcessibilidade {

    private static final String ID = "TABELA_SEM_CABECALHO";
    private static final String CRITERIO = "1.3.1";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String criterioWcag() {
        return CRITERIO;
    }

    @Override
    public List<Achado> avaliar(DocumentoExtraido documento) {
        return documento.tabelas().stream()
                .filter(t -> !t.ehVazia())
                .filter(t -> !t.primeiraLinhaEhCabecalho())
                .map(RegraTabelaSemCabecalho::paraAchado)
                .toList();
    }

    private static Achado paraAchado(TabelaDoDocumento tabela) {
        // ALTA: diferente de uma imagem decorativa, nao existe tabela de dados
        // que se beneficie de nao ter cabecalho. O dano e certo.
        return new Achado(Problema.Severidade.ALTA, tabela.partePacote(),
                "tabela " + tabela.indiceNaParte() + " (" + tabela.linhas()
                        + " linhas) nao marca a primeira linha como cabecalho (w:tblHeader)");
    }
}
