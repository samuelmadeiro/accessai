package dev.accessai.analise.extracao;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import javax.xml.stream.XMLStreamReader;

/**
 * Coleta tabelas e diz se a primeira linha esta marcada como cabecalho.
 *
 * <p>O sinal e {@code w:trPr/w:tblHeader}. Ele existe para repetir a linha no
 * topo de cada pagina impressa, mas e o unico marcador de "esta linha e
 * cabecalho" que o WordprocessingML tem: {@code w:tblLook firstRow} liga
 * formatacao, nao semantica, e negrito na primeira linha nao e informacao
 * programaticamente determinavel. E o mesmo sinal que o Verificador de
 * Acessibilidade do Word usa.
 *
 * <p>{@code <w:tblHeader w:val="false"/>} desliga o marcador. Ignorar o atributo
 * transformaria uma tabela explicitamente sem cabecalho em tabela conforme.
 *
 * <p>Tabela aninhada tem pilha propria: a linha de dentro nao pode marcar a
 * tabela de fora como acessivel.
 */
final class ColetorDeTabelas implements ColetorDeParte {

    private final List<TabelaDoDocumento> tabelas = new ArrayList<>();
    private final Deque<Tabela> abertas = new ArrayDeque<>();
    private String parteAtual;
    private int contadorNaParte;

    @Override
    public boolean aceita(String parte) {
        return Ooxml.ehParteComConteudo(parte);
    }

    @Override
    public void aoIniciarParte(String parte) {
        this.parteAtual = parte;
        this.contadorNaParte = 0;
        abertas.clear();
    }

    @Override
    public void aoAbrirElemento(XMLStreamReader r, int profundidade) {
        if (ehElemento(r, "tbl")) {
            abertas.push(new Tabela(profundidade, ++contadorNaParte));
            return;
        }
        Tabela atual = abertas.peek();
        if (atual == null) {
            return;
        }
        if (ehElemento(r, "tr")) {
            atual.linhas++;
            atual.profundidadeDaLinha = profundidade;
            return;
        }
        if (ehElemento(r, "tblHeader") && atual.estaNaPrimeiraLinha()) {
            atual.primeiraLinhaEhCabecalho = ligado(r.getAttributeValue(Ooxml.NS_W, "val"));
        }
    }

    @Override
    public void aoFecharElemento(int profundidade) {
        Tabela atual = abertas.peek();
        if (atual == null) {
            return;
        }
        if (atual.profundidadeDaLinha == profundidade) {
            atual.profundidadeDaLinha = -1;
            return;
        }
        if (atual.profundidade == profundidade) {
            abertas.pop();
            tabelas.add(new TabelaDoDocumento(parteAtual, atual.indice, atual.linhas,
                    atual.primeiraLinhaEhCabecalho));
        }
    }

    List<TabelaDoDocumento> tabelas() {
        return List.copyOf(tabelas);
    }

    /** Atributo booleano de OOXML: ausente significa ligado. */
    private static boolean ligado(String val) {
        return val == null || !(val.equals("0") || val.equalsIgnoreCase("false")
                || val.equalsIgnoreCase("off"));
    }

    private static boolean ehElemento(XMLStreamReader r, String local) {
        return Ooxml.NS_W.equals(r.getNamespaceURI()) && local.equals(r.getLocalName());
    }

    private static final class Tabela {

        private final int profundidade;
        private final int indice;
        private int linhas;
        private int profundidadeDaLinha = -1;
        private boolean primeiraLinhaEhCabecalho;

        private Tabela(int profundidade, int indice) {
            this.profundidade = profundidade;
            this.indice = indice;
        }

        boolean estaNaPrimeiraLinha() {
            return linhas == 1 && profundidadeDaLinha > 0;
        }
    }
}
