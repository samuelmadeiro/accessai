package spike;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Caminho 2: le o .docx como zip e varre o XML com StAX.
 *
 * <p>Cobre as quatro armadilhas do corpus: wp:inline e wp:anchor, cabecalhos e
 * rodapes alem do document.xml, VML legado (v:shape/@alt) e a duplicacao de
 * mc:AlternateContent.
 */
public final class ExtratorXmlDireto implements ExtratorAltText {

    private static final String NS_WP =
            "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing";
    private static final String NS_MC =
            "http://schemas.openxmlformats.org/markup-compatibility/2006";
    private static final String NS_VML = "urn:schemas-microsoft-com:vml";

    private final XMLInputFactory factory = criarFactorySegura();

    /**
     * Arquivo enviado por usuario e hostil (CONTRIBUTING.md secao 5): DTD e entidades
     * externas desligadas para fechar XXE e billion-laughs.
     */
    private static XMLInputFactory criarFactorySegura() {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return f;
    }

    @Override
    public String nome() {
        return "XML direto (StAX)";
    }

    @Override
    public List<AltText.Imagem> extrair(Path docx) throws IOException {
        List<AltText.Imagem> achados = new ArrayList<>();
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            List<? extends ZipEntry> partes = zip.stream()
                    .filter(e -> ehParteComConteudo(e.getName()))
                    .sorted(Comparator.comparing(ZipEntry::getName))
                    .toList();
            for (ZipEntry parte : partes) {
                try (InputStream in = zip.getInputStream(parte)) {
                    varrer(in, parte.getName(), achados);
                }
            }
        }
        return achados;
    }

    /**
     * Decide quais partes do pacote sao varridas.
     *
     * <p>A regra e por EXCLUSAO, nao por lista branca. O corpus real derrubou a
     * lista branca na primeira tentativa: um documento trazia
     * {@code word/commentsDocument.xml}, e nao {@code word/comments.xml} — nome
     * que nenhuma lista escrita a mao teria previsto. Partes de conteudo novas
     * continuarao aparecendo; partes de configuracao sao poucas e conhecidas.
     *
     * <p>Excluidas de proposito:
     * <ul>
     *   <li>styles, settings, webSettings, fontTable, theme — configuracao, nao
     *       tem imagem de conteudo;</li>
     *   <li>numbering — pode conter {@code w:numPicBullet}, que e marcador de
     *       lista. Marcador e decorativo por natureza; conta-lo como imagem sem
     *       alt geraria falso positivo em todo documento com lista ilustrada;</li>
     *   <li>commentsExtended / commentsIds / commentsExtensible — metadados de
     *       comentario, sem conteudo visivel;</li>
     *   <li>word/glossary/ — Quick Parts, nao faz parte do documento visivel.</li>
     * </ul>
     */
    private static boolean ehParteComConteudo(String nome) {
        if (!nome.startsWith("word/") || !nome.endsWith(".xml")) {
            return false;
        }
        if (nome.startsWith("word/theme/") || nome.startsWith("word/glossary/")) {
            return false;
        }
        String base = nome.substring("word/".length());
        return !CONFIGURACAO.contains(base)
                && !base.startsWith("commentsExtended")
                && !base.startsWith("commentsIds")
                && !base.startsWith("commentsExtensible");
    }

    private static final java.util.Set<String> CONFIGURACAO = java.util.Set.of(
            "styles.xml", "settings.xml", "webSettings.xml",
            "fontTable.xml", "numbering.xml", "stylesWithEffects.xml");

    private void varrer(InputStream in, String parte, List<AltText.Imagem> saida)
            throws IOException {
        XMLStreamReader r = null;
        try {
            r = factory.createXMLStreamReader(in);
            int profundidade = 0;
            int profundidadeFallback = -1;

            while (r.hasNext()) {
                int evento = r.next();

                if (evento == XMLStreamConstants.START_ELEMENT) {
                    profundidade++;

                    // mc:Fallback repete o desenho que mc:Choice ja declarou.
                    // Ignorar a subarvore inteira evita contar a mesma imagem duas vezes.
                    if (profundidadeFallback < 0 && ehFallback(r)) {
                        profundidadeFallback = profundidade;
                        continue;
                    }
                    if (profundidadeFallback >= 0) {
                        continue;
                    }

                    if (ehDocPr(r)) {
                        saida.add(AltText.Imagem.de(
                                parte,
                                r.getAttributeValue(null, "name"),
                                r.getAttributeValue(null, "descr")));
                    } else if (ehVmlShape(r)) {
                        saida.add(AltText.Imagem.de(
                                parte,
                                r.getAttributeValue(null, "id"),
                                r.getAttributeValue(null, "alt")));
                    }

                } else if (evento == XMLStreamConstants.END_ELEMENT) {
                    if (profundidadeFallback == profundidade) {
                        profundidadeFallback = -1;
                    }
                    profundidade--;
                }
            }
        } catch (XMLStreamException e) {
            throw new IOException("XML invalido em " + parte, e);
        } finally {
            fechar(r, parte);
        }
    }

    private static void fechar(XMLStreamReader r, String parte) throws IOException {
        if (r == null) {
            return;
        }
        try {
            r.close();
        } catch (XMLStreamException e) {
            throw new IOException("falha ao fechar o leitor de " + parte, e);
        }
    }

    private static boolean ehFallback(XMLStreamReader r) {
        return NS_MC.equals(r.getNamespaceURI()) && "Fallback".equals(r.getLocalName());
    }

    private static boolean ehDocPr(XMLStreamReader r) {
        return NS_WP.equals(r.getNamespaceURI()) && "docPr".equals(r.getLocalName());
    }

    private static boolean ehVmlShape(XMLStreamReader r) {
        return NS_VML.equals(r.getNamespaceURI()) && "shape".equals(r.getLocalName());
    }
}
