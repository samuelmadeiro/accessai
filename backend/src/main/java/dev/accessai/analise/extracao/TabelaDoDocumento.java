package dev.accessai.analise.extracao;

/**
 * Uma tabela encontrada no pacote.
 *
 * @param partePacote          parte onde a tabela esta
 * @param indiceNaParte        1 para a primeira tabela da parte, 2 para a segunda
 * @param linhas               quantidade de linhas
 * @param primeiraLinhaEhCabecalho se a primeira linha carrega {@code w:tblHeader}
 */
public record TabelaDoDocumento(String partePacote, int indiceNaParte, int linhas,
                                boolean primeiraLinhaEhCabecalho) {

    /**
     * Tabela sem linha nenhuma nao tem o que marcar como cabecalho. Existe em
     * documento real (tabela usada so para diagramar layout) e cobrar cabecalho
     * dela seria falso positivo.
     */
    public boolean ehVazia() {
        return linhas == 0;
    }
}
