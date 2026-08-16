package dev.accessai.analise.extracao;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.springframework.stereotype.Component;

/**
 * Extrai imagens e seus textos alternativos de um DOCX.
 *
 * <p>Parsing direto do XML, sem Apache POI. A decisao esta registrada em
 * {@code spike/RESULTADO.md}: o POI nao enxerga desenho dentro de
 * {@code mc:AlternateContent} e devolve zero imagens nesse caso — falso
 * negativo silencioso, que num produto de score significa afirmar conformidade
 * inexistente. O caso apareceu num edital real de prefeitura, nao so no
 * laboratorio.
 *
 * <p><b>Nem todo desenho e imagem.</b> {@code wp:docPr} e propriedade de
 * qualquer desenho — caixa de texto, autoforma, grafico, SmartArt, agrupamento.
 * Contar todos como imagem gera falso positivo em documento real, onde caixa de
 * texto e comum e quase nunca tem {@code descr}. Por isso um desenho so vira
 * {@link ImagemDoDocumento} quando a propria subarvore contem
 * {@code pic:pic} ou {@code a:blip} — a marca de que ha bitmap ali. O mesmo
 * vale para o caminho VML legado: {@code v:shape} so conta com
 * {@code v:imagedata} dentro.
 */
@Component
public class ExtratorDeImagens {

    private static final String NS_WP =
            "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing";
    private static final String NS_MC =
            "http://schemas.openxmlformats.org/markup-compatibility/2006";
    private static final String NS_VML = "urn:schemas-microsoft-com:vml";
    private static final String NS_PIC =
            "http://schemas.openxmlformats.org/drawingml/2006/picture";
    private static final String NS_A =
            "http://schemas.openxmlformats.org/drawingml/2006/main";

    /**
     * Partes de configuracao, que nunca tem imagem de conteudo.
     *
     * <p>A selecao e por EXCLUSAO e nao por lista branca. O corpus real derrubou
     * a lista branca na primeira execucao: um documento trazia
     * {@code word/commentsDocument.xml}, nome que nenhuma lista escrita a mao
     * teria previsto. Partes de conteudo novas continuam aparecendo; partes de
     * configuracao sao poucas e conhecidas.
     *
     * <p>{@code numbering.xml} fica de fora porque pode conter
     * {@code w:numPicBullet} — marcador de lista, decorativo por natureza.
     * Conta-lo geraria problema falso em todo documento com lista ilustrada.
     */
    private static final Set<String> PARTES_DE_CONFIGURACAO = Set.of(
            "styles.xml", "settings.xml", "webSettings.xml",
            "fontTable.xml", "numbering.xml", "stylesWithEffects.xml");

    /**
     * {@link XMLInputFactory} nao e thread-safe e este bean e singleton. Um
     * {@code ThreadLocal} da uma fabrica por thread sem recriar a fabrica a cada
     * documento — o {@code newFactory()} faz busca de servico e nao e de graca.
     * Campo de instancia compartilhado funcionaria hoje, com concorrencia 1 no
     * listener, e quebraria em silencio no dia em que ela subir.
     */
    private static final ThreadLocal<XMLInputFactory> FABRICA =
            ThreadLocal.withInitial(ExtratorDeImagens::criarFactorySegura);

    /** Documento enviado por usuario e hostil: DTD e entidade externa desligadas. */
    private static XMLInputFactory criarFactorySegura() {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return f;
    }

    public List<ImagemDoDocumento> extrair(byte[] docx) {
        List<ImagemDoDocumento> achados = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx))) {
            ZipEntry entrada;
            while ((entrada = zip.getNextEntry()) != null) {
                if (ehParteComConteudo(entrada.getName())) {
                    varrer(zip, entrada.getName(), achados);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("falha ao ler o pacote DOCX", e);
        }
        achados.sort(Comparator.comparing(ImagemDoDocumento::partePacote));
        return achados;
    }

    static boolean ehParteComConteudo(String nome) {
        if (!nome.startsWith("word/") || !nome.endsWith(".xml")) {
            return false;
        }
        if (nome.startsWith("word/theme/") || nome.startsWith("word/glossary/")) {
            return false;
        }
        String base = nome.substring("word/".length());
        if (base.contains("/")) {
            return false;
        }
        return !PARTES_DE_CONFIGURACAO.contains(base)
                && !base.startsWith("commentsExtended")
                && !base.startsWith("commentsIds")
                && !base.startsWith("commentsExtensible");
    }

    private void varrer(InputStream in, String parte, List<ImagemDoDocumento> saida) {
        XMLStreamReader r = null;
        try {
            // O stream do zip nao pode ser fechado pelo leitor: as proximas
            // entradas ainda serao lidas dele.
            r = FABRICA.get().createXMLStreamReader(new EntradaNaoFechavel(in));
            int profundidade = 0;
            int profundidadeFallback = -1;

            // Desenhos abertos e ainda nao fechados. Pilha e nao variavel unica
            // porque desenho aninha: uma imagem dentro de uma caixa de texto e
            // um wp:inline dentro de outro wp:inline.
            Deque<Desenho> abertos = new ArrayDeque<>();

            while (r.hasNext()) {
                int evento = r.next();
                if (evento == XMLStreamConstants.START_ELEMENT) {
                    profundidade++;

                    // mc:Fallback repete o desenho que mc:Choice ja declarou.
                    // Ignorar a subarvore evita contar a mesma imagem duas vezes
                    // e inflar o denominador do score.
                    if (profundidadeFallback < 0 && ehElemento(r, NS_MC, "Fallback")) {
                        profundidadeFallback = profundidade;
                        continue;
                    }
                    if (profundidadeFallback >= 0) {
                        continue;
                    }

                    aoAbrirElemento(r, profundidade, abertos);
                } else if (evento == XMLStreamConstants.END_ELEMENT) {
                    if (profundidadeFallback == profundidade) {
                        profundidadeFallback = -1;
                    }
                    if (profundidadeFallback < 0) {
                        aoFecharElemento(profundidade, parte, abertos, saida);
                    }
                    profundidade--;
                }
            }
        } catch (XMLStreamException e) {
            throw new ParteIlegivelException(parte, e);
        } finally {
            fechar(r, parte);
        }
    }

    private static void aoAbrirElemento(XMLStreamReader r, int profundidade, Deque<Desenho> abertos) {
        if (ehElemento(r, NS_WP, "inline") || ehElemento(r, NS_WP, "anchor")) {
            abertos.push(new Desenho(profundidade));
            return;
        }
        if (ehElemento(r, NS_VML, "shape")) {
            Desenho desenho = new Desenho(profundidade);
            // No caminho VML o alt e atributo do proprio v:shape.
            desenho.identificar(r.getAttributeValue(null, "id"),
                    r.getAttributeValue(null, "alt"));
            abertos.push(desenho);
            return;
        }
        if (abertos.isEmpty()) {
            return;
        }
        Desenho atual = abertos.peek();
        if (ehElemento(r, NS_WP, "docPr")) {
            atual.identificar(r.getAttributeValue(null, "name"),
                    r.getAttributeValue(null, "descr"));
        } else if (ehElemento(r, NS_PIC, "pic") || ehElemento(r, NS_A, "blip")
                || ehElemento(r, NS_VML, "imagedata")) {
            // A prova de que este desenho carrega bitmap, e nao so geometria.
            atual.confirmarQueEhImagem();
        }
    }

    private static void aoFecharElemento(int profundidade, String parte, Deque<Desenho> abertos,
                                         List<ImagemDoDocumento> saida) {
        Desenho atual = abertos.peek();
        if (atual == null || atual.profundidade() != profundidade) {
            return;
        }
        abertos.pop();
        if (atual.ehImagem()) {
            saida.add(ImagemDoDocumento.de(parte, atual.nome(), atual.descr()));
        }
    }

    private static void fechar(XMLStreamReader r, String parte) {
        if (r == null) {
            return;
        }
        try {
            r.close();
        } catch (XMLStreamException e) {
            throw new ParteIlegivelException(parte, e);
        }
    }

    private static boolean ehElemento(XMLStreamReader r, String namespace, String local) {
        return namespace.equals(r.getNamespaceURI()) && local.equals(r.getLocalName());
    }

    /**
     * Desenho aberto durante a varredura.
     *
     * <p>Existe porque a decisao "isto e imagem?" so pode ser tomada no
     * fechamento: o {@code wp:docPr} com o alt text vem ANTES do
     * {@code pic:pic} que prova que ha bitmap.
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

        boolean ehImagem() {
            return temBitmap;
        }

        int profundidade() {
            return profundidade;
        }

        String nome() {
            return nome;
        }

        String descr() {
            return descr;
        }
    }

    /** Impede que o leitor XML feche o ZipInputStream compartilhado. */
    private static final class EntradaNaoFechavel extends java.io.FilterInputStream {
        EntradaNaoFechavel(InputStream in) {
            super(in);
        }

        @Override
        public void close() {
            // intencionalmente vazio: quem fecha o zip e o metodo extrair
        }
    }

    /** Parte do pacote nao pode ser lida como XML. */
    public static class ParteIlegivelException extends RuntimeException {
        public ParteIlegivelException(String parte, Throwable causa) {
            super("XML invalido na parte " + parte, causa);
        }
    }
}
