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
 * <p>Publica porque o teste ponta a ponta tambem monta pacote hostil.
 *
 * <p>As estruturas imitam o que Word, Google Docs e LibreOffice produzem de
 * verdade — mesma base do corpus sintetico do spike. Continuam sendo sinteticas:
 * a validacao contra exports reais e outra coisa, e esta pendente.
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

    private final Map<String, String> partes = new LinkedHashMap<>();

    private DocxDeTeste() {
    }

    /** Pacote minimo valido: [Content_Types].xml e word/document.xml. */
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

    public static String cabecalho(String corpo) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<w:hdr " + NS + ">" + corpo + "</w:hdr>";
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

    static String imagemAncorada(String nome, String descr) {
        return "<w:p><w:r><w:drawing><wp:anchor>" + docPr(nome, descr)
                + graficoDeImagem() + "</wp:anchor></w:drawing></w:r></w:p>";
    }

    /**
     * Caixa de texto: {@code wp:docPr} igual ao de uma imagem, mas o conteudo e
     * {@code wps:wsp} — forma com texto dentro, sem bitmap nenhum.
     */
    static String caixaDeTexto(String nome, String texto) {
        return "<w:p><w:r><w:drawing><wp:inline>" + docPr(nome, null)
                + "<a:graphic><a:graphicData uri=\"http://schemas.microsoft.com/office/"
                + "word/2010/wordprocessingShape\"><wps:wsp><wps:txbx><w:txbxContent>"
                + "<w:p><w:r><w:t>" + texto + "</w:t></w:r></w:p>"
                + "</w:txbxContent></wps:txbx></wps:wsp></a:graphicData></a:graphic>"
                + "</wp:inline></w:drawing></w:r></w:p>";
    }

    /** Caixa de texto com uma imagem dentro: desenho aninhado em desenho. */
    static String caixaDeTextoComImagem(String nomeDaCaixa, String nomeDaImagem, String descr) {
        return "<w:p><w:r><w:drawing><wp:inline>" + docPr(nomeDaCaixa, null)
                + "<a:graphic><a:graphicData uri=\"http://schemas.microsoft.com/office/"
                + "word/2010/wordprocessingShape\"><wps:wsp><wps:txbx><w:txbxContent>"
                + imagemInline(nomeDaImagem, descr)
                + "</w:txbxContent></wps:txbx></wps:wsp></a:graphicData></a:graphic>"
                + "</wp:inline></w:drawing></w:r></w:p>";
    }

    /** VML legado com bitmap: v:shape que carrega v:imagedata. */
    static String imagemVml(String id, String alt) {
        String atributoAlt = alt == null ? "" : " alt=\"" + alt + "\"";
        return "<w:p><w:r><w:pict><v:shape id=\"" + id + "\"" + atributoAlt + ">"
                + "<v:imagedata r:id=\"rId5\"/></v:shape></w:pict></w:r></w:p>";
    }

    /** VML sem bitmap: autoforma, linha decorativa. Nao e imagem. */
    static String formaVml(String id) {
        return "<w:p><w:r><w:pict><v:shape id=\"" + id + "\" style=\"width:72pt\">"
                + "<v:textbox><w:txbxContent><w:p/></w:txbxContent></v:textbox>"
                + "</v:shape></w:pict></w:r></w:p>";
    }

    /**
     * O mesmo desenho declarado duas vezes: moderno em {@code mc:Choice},
     * legado em {@code mc:Fallback}. Contar dois seria inflar o denominador.
     */
    static String alternateContent(String nome, String descr) {
        return "<w:p><w:r><mc:AlternateContent><mc:Choice Requires=\"wps\">"
                + "<w:drawing><wp:inline>" + docPr(nome, descr) + graficoDeImagem()
                + "</wp:inline></w:drawing></mc:Choice>"
                + "<mc:Fallback><w:pict><v:shape id=\"legado\" alt=\"" + (descr == null ? "" : descr)
                + "\"><v:imagedata r:id=\"rId5\"/></v:shape></w:pict></mc:Fallback>"
                + "</mc:AlternateContent></w:r></w:p>";
    }
}
