package dev.accessai.analise.extracao;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Monta pacotes DOCX em memoria para teste.
 *
 * <p>Nada de fixture binaria: um {@code .docx} commitado e um zip opaco que
 * ninguem consegue revisar em pull request, e a armadilha que ele exercita fica
 * so no nome do arquivo. Aqui o XML esta a vista, ao lado da assercao.
 *
 * <p>O pacote base e deliberadamente pobre: so {@code [Content_Types].xml} e
 * {@code word/document.xml}. Sem {@code docProps/core.xml} e sem
 * {@code word/styles.xml} — ou seja, sem titulo e sem idioma. Teste que quer um
 * documento conforme precisa dizer isso explicitamente, o que deixa visivel o
 * que cada caso esta afirmando.
 *
 * <p>As estruturas imitam o que Word, Google Docs e LibreOffice produzem de
 * verdade. Continuam sendo sinteticas: a validacao contra exports reais e outra
 * coisa, e esta pendente.
 */
public final class DocxDeTeste {

    public static final String NS =
            "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
            + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
            + "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" "
            + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
            + "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\" "
            + "xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\" "
            + "xmlns:wps=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\" "
            + "xmlns:v=\"urn:schemas-microsoft-com:vml\" "
            + "xmlns:o=\"urn:schemas-microsoft-com:office:office\"";

    private static final String TIPO_HYPERLINK =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink";

    private final Map<String, String> partes = new LinkedHashMap<>();

    private DocxDeTeste() {
    }

    /** Pacote minimo valido: sem titulo, sem idioma, corpo vazio. */
    public static DocxDeTeste pacote() {
        DocxDeTeste d = new DocxDeTeste();
        d.partes.put("[Content_Types].xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "</Types>");
        d.partes.put("word/document.xml", documento(""));
        return d;
    }

    public DocxDeTeste com(String parte, String conteudo) {
        partes.put(parte, conteudo);
        return this;
    }

    /** Substitui o corpo de word/document.xml pelos fragmentos dados. */
    public DocxDeTeste comCorpo(String... fragmentos) {
        return com("word/document.xml", documento(String.join("", fragmentos)));
    }

    /** {@code docProps/core.xml} com dc:title. {@code null} escreve o elemento vazio. */
    public DocxDeTeste comTitulo(String titulo) {
        return com("docProps/core.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<cp:coreProperties "
                + "xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/"
                + "core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                + "<dc:title>" + (titulo == null ? "" : titulo) + "</dc:title>"
                + "<dc:creator>teste</dc:creator>"
                + "</cp:coreProperties>");
    }

    /** {@code docProps/core.xml} SEM dc:title — ausente, e nao em branco. */
    public DocxDeTeste comPropriedadesSemTitulo() {
        return com("docProps/core.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<cp:coreProperties "
                + "xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/"
                + "core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                + "<dc:creator>teste</dc:creator>"
                + "</cp:coreProperties>");
    }

    /** {@code word/styles.xml} com o idioma padrao do documento em docDefaults. */
    public DocxDeTeste comIdiomaPadrao(String idioma) {
        return com("word/styles.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<w:styles " + NS + "><w:docDefaults><w:rPrDefault><w:rPr>"
                + "<w:lang w:val=\"" + idioma + "\" w:eastAsia=\"en-US\"/>"
                + "</w:rPr></w:rPrDefault></w:docDefaults></w:styles>");
    }

    /** {@code word/styles.xml} sem nenhum w:lang. */
    public DocxDeTeste comEstilosSemIdioma() {
        return com("word/styles.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<w:styles " + NS + "><w:docDefaults><w:rPrDefault><w:rPr>"
                + "<w:lang w:val=\"x-none\"/>"
                + "</w:rPr></w:rPrDefault></w:docDefaults></w:styles>");
    }

    /** Relacionamentos de hyperlink de word/document.xml. */
    public DocxDeTeste comLinksExternos(Map<String, String> destinosPorId) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/"
                + "relationships\">");
        destinosPorId.forEach((id, destino) -> xml
                .append("<Relationship Id=\"").append(id)
                .append("\" Type=\"").append(TIPO_HYPERLINK)
                .append("\" Target=\"").append(destino)
                .append("\" TargetMode=\"External\"/>"));
        xml.append("</Relationships>");
        return com("word/_rels/document.xml.rels", xml.toString());
    }

    public byte[] bytes() {
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(saida)) {
            for (Map.Entry<String, String> parte : partes.entrySet()) {
                zip.putNextEntry(new ZipEntry(parte.getKey()));
                zip.write(parte.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return saida.toByteArray();
    }

    // ------------------------------------------------------------------
    // Fragmentos
    // ------------------------------------------------------------------

    public static String documento(String corpo) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<w:document " + NS + "><w:body>" + corpo + "</w:body></w:document>";
    }

    public static String cabecalhoDePagina(String corpo) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<w:hdr " + NS + ">" + corpo + "</w:hdr>";
    }

    public static String paragrafo(String texto) {
        return "<w:p><w:r><w:t>" + texto + "</w:t></w:r></w:p>";
    }

    /** Titulo pelo identificador do estilo, como Word e Google Docs gravam. */
    public static String tituloPorEstilo(int nivel, String texto) {
        return "<w:p><w:pPr><w:pStyle w:val=\"Heading" + nivel + "\"/></w:pPr>"
                + "<w:r><w:t>" + texto + "</w:t></w:r></w:p>";
    }

    /** Titulo com nome de estilo em portugues, como Word pt-BR grava. */
    public static String tituloPorEstiloEmPortugues(int nivel, String texto) {
        return "<w:p><w:pPr><w:pStyle w:val=\"Ttulo" + nivel + "\"/></w:pPr>"
                + "<w:r><w:t>" + texto + "</w:t></w:r></w:p>";
    }

    /** Titulo por outlineLvl, que e 0-based: 0 e H1. */
    public static String tituloPorOutline(int nivel, String texto) {
        return "<w:p><w:pPr><w:pStyle w:val=\"MeuEstiloProprio\"/>"
                + "<w:outlineLvl w:val=\"" + (nivel - 1) + "\"/></w:pPr>"
                + "<w:r><w:t>" + texto + "</w:t></w:r></w:p>";
    }

    /** Paragrafo de corpo com outlineLvl 9, que significa "nao e titulo". */
    public static String corpoComOutlineDeTexto(String texto) {
        return "<w:p><w:pPr><w:outlineLvl w:val=\"9\"/></w:pPr>"
                + "<w:r><w:t>" + texto + "</w:t></w:r></w:p>";
    }

    public static String tabela(int linhas, boolean primeiraLinhaEhCabecalho) {
        return tabela(linhas, primeiraLinhaEhCabecalho ? "<w:tblHeader/>" : "");
    }

    /** Permite montar o w:trPr da primeira linha na mao, para o caso val="false". */
    public static String tabela(int linhas, String trPrDaPrimeiraLinha) {
        StringBuilder xml = new StringBuilder("<w:tbl><w:tblPr/>");
        for (int i = 1; i <= linhas; i++) {
            xml.append("<w:tr>");
            if (i == 1 && !trPrDaPrimeiraLinha.isEmpty()) {
                xml.append("<w:trPr>").append(trPrDaPrimeiraLinha).append("</w:trPr>");
            }
            xml.append("<w:tc><w:p><w:r><w:t>celula ").append(i)
               .append("</w:t></w:r></w:p></w:tc></w:tr>");
        }
        return xml.append("</w:tbl>").toString();
    }

    /** Tabela sem nenhuma linha: recurso de diagramacao, nao tabela de dados. */
    public static String tabelaVazia() {
        return "<w:tbl><w:tblPr/></w:tbl>";
    }

    public static String link(String idDoRelacionamento, String texto) {
        return "<w:p><w:hyperlink r:id=\"" + idDoRelacionamento + "\">"
                + "<w:r><w:t>" + texto + "</w:t></w:r></w:hyperlink></w:p>";
    }

    /** Link para ancora interna: sem r:id, portanto sem destino externo. */
    public static String linkInterno(String ancora, String texto) {
        return "<w:p><w:hyperlink w:anchor=\"" + ancora + "\">"
                + "<w:r><w:t>" + texto + "</w:t></w:r></w:hyperlink></w:p>";
    }

    /** {@code descr = null} significa atributo AUSENTE, diferente de vazio. */
    private static String docPr(String nome, String descr) {
        String atributos = "id=\"1\" name=\"" + nome + "\"";
        if (descr != null) {
            atributos += " descr=\"" + descr + "\"";
        }
        return "<wp:docPr " + atributos + "/>";
    }

    /** O bloco que prova que ha bitmap: pic:pic com a:blip. */
    private static String graficoDeImagem() {
        return "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/"
                + "drawingml/2006/picture\"><pic:pic><pic:nvPicPr>"
                + "<pic:cNvPr id=\"0\" name=\"image1.png\"/><pic:cNvPicPr/></pic:nvPicPr>"
                + "<pic:blipFill><a:blip r:embed=\"rId5\"/></pic:blipFill>"
                + "</pic:pic></a:graphicData></a:graphic>";
    }

    public static String imagemInline(String nome, String descr) {
        return "<w:p><w:r><w:drawing><wp:inline>" + docPr(nome, descr)
                + graficoDeImagem() + "</wp:inline></w:drawing></w:r></w:p>";
    }

    public static String imagemAncorada(String nome, String descr) {
        return "<w:p><w:r><w:drawing><wp:anchor>" + docPr(nome, descr)
                + graficoDeImagem() + "</wp:anchor></w:drawing></w:r></w:p>";
    }

    /**
     * Caixa de texto: {@code wp:docPr} igual ao de uma imagem, mas o conteudo e
     * {@code wps:wsp} — forma com texto dentro, sem bitmap nenhum.
     */
    public static String caixaDeTexto(String nome, String texto) {
        return "<w:p><w:r><w:drawing><wp:inline>" + docPr(nome, null)
                + "<a:graphic><a:graphicData uri=\"http://schemas.microsoft.com/office/"
                + "word/2010/wordprocessingShape\"><wps:wsp><wps:txbx><w:txbxContent>"
                + "<w:p><w:r><w:t>" + texto + "</w:t></w:r></w:p>"
                + "</w:txbxContent></wps:txbx></wps:wsp></a:graphicData></a:graphic>"
                + "</wp:inline></w:drawing></w:r></w:p>";
    }

    /** Caixa de texto com uma imagem dentro: desenho aninhado em desenho. */
    public static String caixaDeTextoComImagem(String nomeDaCaixa, String nomeDaImagem,
                                               String descr) {
        return "<w:p><w:r><w:drawing><wp:inline>" + docPr(nomeDaCaixa, null)
                + "<a:graphic><a:graphicData uri=\"http://schemas.microsoft.com/office/"
                + "word/2010/wordprocessingShape\"><wps:wsp><wps:txbx><w:txbxContent>"
                + imagemInline(nomeDaImagem, descr)
                + "</w:txbxContent></wps:txbx></wps:wsp></a:graphicData></a:graphic>"
                + "</wp:inline></w:drawing></w:r></w:p>";
    }

    /** VML legado com bitmap: v:shape que carrega v:imagedata. */
    public static String imagemVml(String id, String alt) {
        String atributoAlt = alt == null ? "" : " alt=\"" + alt + "\"";
        return "<w:p><w:r><w:pict><v:shape id=\"" + id + "\"" + atributoAlt + ">"
                + "<v:imagedata r:id=\"rId5\"/></v:shape></w:pict></w:r></w:p>";
    }

    /** VML sem bitmap: autoforma, linha decorativa. Nao e imagem. */
    public static String formaVml(String id) {
        return "<w:p><w:r><w:pict><v:shape id=\"" + id + "\" style=\"width:72pt\">"
                + "<v:textbox><w:txbxContent><w:p/></w:txbxContent></v:textbox>"
                + "</v:shape></w:pict></w:r></w:p>";
    }

    /**
     * O mesmo desenho declarado duas vezes: moderno em {@code mc:Choice},
     * legado em {@code mc:Fallback}. Contar dois seria inflar o denominador.
     */
    public static String alternateContent(String nome, String descr) {
        return "<w:p><w:r><mc:AlternateContent><mc:Choice Requires=\"wps\">"
                + "<w:drawing><wp:inline>" + docPr(nome, descr) + graficoDeImagem()
                + "</wp:inline></w:drawing></mc:Choice>"
                + "<mc:Fallback><w:pict><v:shape id=\"legado\" alt=\""
                + (descr == null ? "" : descr)
                + "\"><v:imagedata r:id=\"rId5\"/></v:shape></w:pict></mc:Fallback>"
                + "</mc:AlternateContent></w:r></w:p>";
    }
}
