package dev.accessai.analise.extracao;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import javax.xml.stream.XMLStreamReader;

/**
 * Coleta desenhos que carregam bitmap.
 *
 * <p><b>Nem todo desenho e imagem.</b> {@code wp:docPr} e propriedade de
 * qualquer desenho — caixa de texto, autoforma, grafico, SmartArt, agrupamento.
 * Contar todos como imagem gera falso positivo em documento real, onde caixa de
 * texto e comum e quase nunca tem {@code descr}. Um desenho so vira
 * {@link ImagemDoDocumento} quando a propria subarvore contem {@code pic:pic} ou
 * {@code a:blip}. O mesmo vale para o caminho VML legado: {@code v:shape} so
 * conta com {@code v:imagedata} dentro.
 */
final class ColetorDeImagens implements ColetorDeParte {

    private final List<ImagemDoDocumento> imagens = new ArrayList<>();
    private final Deque<Desenho> abertos = new ArrayDeque<>();
    private String parteAtual;

    @Override
    public boolean aceita(String parte) {
        return Ooxml.ehParteComConteudo(parte);
    }

    @Override
    public void aoIniciarParte(String parte) {
        this.parteAtual = parte;
        abertos.clear();
    }

    @Override
    public void aoAbrirElemento(XMLStreamReader r, int profundidade) {
        if (ehElemento(r, Ooxml.NS_WP, "inline") || ehElemento(r, Ooxml.NS_WP, "anchor")) {
            abertos.push(new Desenho(profundidade));
            return;
        }
        if (ehElemento(r, Ooxml.NS_VML, "shape")) {
            Desenho desenho = new Desenho(profundidade);
            // No caminho VML o alt e atributo do proprio v:shape.
            desenho.identificar(r.getAttributeValue(null, "id"), r.getAttributeValue(null, "alt"));
            abertos.push(desenho);
            return;
        }
        Desenho atual = abertos.peek();
        if (atual == null) {
            return;
        }
        if (ehElemento(r, Ooxml.NS_WP, "docPr")) {
            atual.identificar(r.getAttributeValue(null, "name"),
                    r.getAttributeValue(null, "descr"));
        } else if (ehElemento(r, Ooxml.NS_PIC, "pic") || ehElemento(r, Ooxml.NS_A, "blip")
                || ehElemento(r, Ooxml.NS_VML, "imagedata")) {
            // A prova de que este desenho carrega bitmap, e nao so geometria.
            atual.confirmarQueEhImagem();
        }
    }

    @Override
    public void aoFecharElemento(int profundidade) {
        Desenho atual = abertos.peek();
        if (atual == null || atual.profundidade != profundidade) {
            return;
        }
        abertos.pop();
        if (atual.temBitmap) {
            imagens.add(ImagemDoDocumento.de(parteAtual, atual.nome, atual.descr));
        }
    }

    List<ImagemDoDocumento> imagens() {
        return List.copyOf(imagens);
    }

    private static boolean ehElemento(XMLStreamReader r, String namespace, String local) {
        return namespace.equals(r.getNamespaceURI()) && local.equals(r.getLocalName());
    }

    /**
     * Desenho aberto durante a varredura.
     *
     * <p>Existe porque a decisao "isto e imagem?" so pode ser tomada no
     * fechamento: o {@code wp:docPr} com o alt text vem ANTES do {@code pic:pic}
     * que prova que ha bitmap.
     */
    private static final class Desenho {

        private final int profundidade;
        private String nome;
        private String descr;
        private boolean temBitmap;

        private Desenho(int profundidade) {
            this.profundidade = profundidade;
        }

        void identificar(String nome, String descr) {
            this.nome = nome;
            this.descr = descr;
        }

        void confirmarQueEhImagem() {
            this.temBitmap = true;
        }
    }
}
