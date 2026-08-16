package spike;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHeaderFooter;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.drawingml.x2006.main.CTNonVisualDrawingProps;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.CTAnchor;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.CTInline;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTDrawing;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPicture;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;

/**
 * Caminho 1: Apache POI (XWPF) descendo ate os beans XmlBeans.
 *
 * <p>A API de alto nivel do POI nao expoe alt text: {@code getAllPictures()}
 * devolve os bytes da imagem, nao os metadados de acessibilidade. E preciso
 * descer a CTP / CTR / CTDrawing, que sao classes geradas do schema OOXML.
 */
public final class ExtratorPoi implements ExtratorAltText {

    @Override
    public String nome() {
        return "Apache POI (XWPF)";
    }

    @Override
    public List<AltText.Imagem> extrair(Path docx) throws IOException {
        List<AltText.Imagem> achados = new ArrayList<>();
        try (InputStream in = Files.newInputStream(docx);
             XWPFDocument doc = new XWPFDocument(in)) {

            varrerParagrafos(doc.getParagraphs(), "word/document.xml", achados);
            varrerTabelas(doc.getTables(), "word/document.xml", achados);

            for (XWPFHeaderFooter hf : doc.getHeaderList()) {
                varrerCabecalhoOuRodape(hf, achados);
            }
            for (XWPFHeaderFooter hf : doc.getFooterList()) {
                varrerCabecalhoOuRodape(hf, achados);
            }
        }
        return achados;
    }

    private void varrerCabecalhoOuRodape(XWPFHeaderFooter hf, List<AltText.Imagem> saida) {
        String parte = nomeDaParte(hf);
        varrerParagrafos(hf.getParagraphs(), parte, saida);
        varrerTabelas(hf.getTables(), parte, saida);
    }

    private static String nomeDaParte(XWPFHeaderFooter hf) {
        String nome = hf.getPackagePart().getPartName().getName();
        return nome.startsWith("/") ? nome.substring(1) : nome;
    }

    private void varrerTabelas(List<XWPFTable> tabelas, String parte, List<AltText.Imagem> saida) {
        for (XWPFTable tabela : tabelas) {
            for (XWPFTableRow linha : tabela.getRows()) {
                for (XWPFTableCell celula : linha.getTableCells()) {
                    varrerParagrafos(celula.getParagraphs(), parte, saida);
                    varrerTabelas(celula.getTables(), parte, saida);
                }
            }
        }
    }

    private void varrerParagrafos(
            List<XWPFParagraph> paragrafos, String parte, List<AltText.Imagem> saida) {
        for (XWPFParagraph p : paragrafos) {
            CTP ctp = p.getCTP();
            for (CTR run : ctp.getRList()) {
                varrerRun(run, parte, saida);
            }
        }
    }

    private void varrerRun(CTR run, String parte, List<AltText.Imagem> saida) {
        for (CTDrawing desenho : run.getDrawingList()) {
            for (CTInline inline : desenho.getInlineList()) {
                adicionar(inline.getDocPr(), parte, saida);
            }
            for (CTAnchor anchor : desenho.getAnchorList()) {
                adicionar(anchor.getDocPr(), parte, saida);
            }
        }
        // VML legado: o POI nao modela v:shape/@alt. Unica saida e reler o XML
        // cru do proprio bean. Isso e uma limitacao do caminho POI, nao um atalho.
        for (CTPicture pict : run.getPictList()) {
            String alt = altDeVml(pict.xmlText());
            if (alt != null) {
                saida.add(AltText.Imagem.de(parte, "vml-shape", alt));
            }
        }
    }

    private static void adicionar(
            CTNonVisualDrawingProps docPr, String parte, List<AltText.Imagem> saida) {
        if (docPr == null) {
            return;
        }
        String descr = docPr.isSetDescr() ? docPr.getDescr() : null;
        saida.add(AltText.Imagem.de(parte, docPr.getName(), descr));
    }

    /**
     * Extrai o atributo alt de um {@code <v:shape>} serializado.
     *
     * <p>Regex sobre XML e fragil por natureza. Esta aqui exatamente para
     * documentar o custo do caminho POI nesse caso: nao existe acessor.
     */
    private static String altDeVml(String xml) {
        int shape = xml.indexOf("<v:shape");
        if (shape < 0) {
            return null;
        }
        int fim = xml.indexOf('>', shape);
        if (fim < 0) {
            return null;
        }
        String tag = xml.substring(shape, fim);
        int alt = tag.indexOf("alt=\"");
        if (alt < 0) {
            return null;
        }
        int inicio = alt + "alt=\"".length();
        int aspas = tag.indexOf('"', inicio);
        return aspas < 0 ? null : tag.substring(inicio, aspas);
    }
}
