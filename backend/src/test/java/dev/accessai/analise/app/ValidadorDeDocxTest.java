package dev.accessai.analise.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Testes do validador de upload.
 *
 * <p>Arquivo enviado por usuario e hostil (CLAUDE.md secao 5). Os limites contra
 * zip bomb existiam sem nenhum teste — ou seja, existiam no papel.
 */
@DisplayName("ValidadorDeDocx")
class ValidadorDeDocxTest {

    private final ValidadorDeDocx validador = new ValidadorDeDocx();

    @Test
    @DisplayName("pacote OOXML completo devolve o MIME de DOCX")
    void pacoteValido() {
        byte[] docx = zip(entrada("[Content_Types].xml", "<Types/>"),
                entrada("word/document.xml", "<w:document/>"));

        assertThat(validador.detectarTipo(docx)).isEqualTo(ValidadorDeDocx.TIPO_MIME_DOCX);
    }

    @Test
    @DisplayName("HTML servido com nome .docx e recusado: extensao nao e prova")
    void htmlDisfarcadoDeDocx() {
        // O caso real da coleta do corpus: HTTP 200, URL terminada em .docx,
        // corpo <!DOCTYPE html>.
        byte[] html = "<!DOCTYPE html><html><body>nao sou um docx</body></html>"
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> validador.detectarTipo(html))
                .isInstanceOf(DocumentoInvalidoException.class)
                .hasMessageContaining("assinatura PK");
    }

    @Test
    @DisplayName("conteudo vazio e recusado sem estourar indice")
    void conteudoVazio() {
        assertThatThrownBy(() -> validador.detectarTipo(new byte[0]))
                .isInstanceOf(DocumentoInvalidoException.class);
    }

    @Test
    @DisplayName("zip sem [Content_Types].xml nao e OOXML")
    void semContentTypes() {
        byte[] zip = zip(entrada("word/document.xml", "<w:document/>"));

        assertThatThrownBy(() -> validador.detectarTipo(zip))
                .isInstanceOf(DocumentoInvalidoException.class)
                .hasMessageContaining("[Content_Types].xml");
    }

    @Test
    @DisplayName("OOXML sem word/document.xml nao e DOCX (pode ser xlsx ou pptx)")
    void semDocumentoPrincipal() {
        byte[] zip = zip(entrada("[Content_Types].xml", "<Types/>"),
                entrada("xl/workbook.xml", "<workbook/>"));

        assertThatThrownBy(() -> validador.detectarTipo(zip))
                .isInstanceOf(DocumentoInvalidoException.class)
                .hasMessageContaining("word/document.xml");
    }

    @Test
    @DisplayName("assinatura PK com corpo corrompido nao explode como IOException crua")
    void zipCorrompido() {
        byte[] falso = new byte[] {'P', 'K', 3, 4, 0, 0, 0, 0, 9, 9, 9, 9};

        assertThatThrownBy(() -> validador.detectarTipo(falso))
                .isInstanceOf(DocumentoInvalidoException.class);
    }

    @Test
    @DisplayName("pacote com entradas demais e recusado")
    void entradasDemais() {
        Entrada[] entradas = new Entrada[2_100];
        entradas[0] = entrada("[Content_Types].xml", "<Types/>");
        entradas[1] = entrada("word/document.xml", "<w:document/>");
        for (int i = 2; i < entradas.length; i++) {
            entradas[i] = entrada("word/lixo" + i + ".xml", "<x/>");
        }

        assertThatThrownBy(() -> validador.detectarTipo(zip(entradas)))
                .isInstanceOf(DocumentoInvalidoException.class)
                .hasMessageContaining("entradas demais");
    }

    @Test
    @DisplayName("zip bomb: 600 MB descomprimidos em poucos KB e recusado")
    void zipBomb() {
        // Zeros comprimem quase a nada: o pacote tem alguns KB e o limite de
        // 512 MB descomprimidos so aparece se o validador LER de verdade, em
        // vez de confiar no tamanho declarado no cabecalho do zip.
        byte[] bomba = zipComZeros(600L * 1024 * 1024);
        assertThat(bomba.length).isLessThan(2 * 1024 * 1024);

        assertThatThrownBy(() -> validador.detectarTipo(bomba))
                .isInstanceOf(DocumentoInvalidoException.class)
                .hasMessageContaining("descomprimido excede");
    }

    // ------------------------------------------------------------------

    private record Entrada(String nome, byte[] conteudo) {
    }

    private static Entrada entrada(String nome, String conteudo) {
        return new Entrada(nome, conteudo.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] zip(Entrada... entradas) {
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(saida)) {
            for (Entrada e : entradas) {
                zip.putNextEntry(new ZipEntry(e.nome()));
                zip.write(e.conteudo());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return saida.toByteArray();
    }

    private static byte[] zipComZeros(long bytes) {
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(saida)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("word/document.xml"));
            byte[] bloco = new byte[1024 * 1024];
            for (long escritos = 0; escritos < bytes; escritos += bloco.length) {
                zip.write(bloco);
            }
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return saida.toByteArray();
    }
}
