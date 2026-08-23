package dev.accessai.analise.app;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Decide se o que chegou no upload e mesmo um DOCX.
 *
 * <p>Arquivo enviado por usuario e hostil (CONTRIBUTING.md secao 5). Tres coisas NAO
 * sao prova de nada e por isso nao sao consultadas aqui: a extensao do nome, o
 * cabecalho {@code Content-Type} da requisicao e o status HTTP de onde o
 * arquivo veio. A coleta do corpus real provou o ponto: duas URLs terminadas em
 * {@code .docx} responderam HTTP 200 servindo HTML.
 *
 * <p>O que e verificado: assinatura de zip, presenca de
 * {@code [Content_Types].xml} e de {@code word/document.xml}, e limites contra
 * zip bomb (numero de entradas e tamanho descomprimido total).
 */
@Component
public class ValidadorDeDocx {

    public static final String TIPO_MIME_DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private static final byte[] ASSINATURA_ZIP = {'P', 'K'};
    private static final int MAXIMO_DE_ENTRADAS = 2_000;
    private static final long MAXIMO_DESCOMPRIMIDO_BYTES = 512L * 1024 * 1024;
    private static final int TAMANHO_DO_BUFFER = 8 * 1024;

    /**
     * @return o tipo MIME detectado a partir do conteudo
     * @throws DocumentoInvalidoException quando o conteudo nao e um DOCX legivel
     */
    public String detectarTipo(byte @NonNull [] conteudo) {
        if (conteudo.length < ASSINATURA_ZIP.length
                || conteudo[0] != ASSINATURA_ZIP[0] || conteudo[1] != ASSINATURA_ZIP[1]) {
            throw new DocumentoInvalidoException(//Outro processo de documento nao válido
                    "conteudo nao e um pacote zip (assinatura PK ausente)");
        }

        boolean temContentTypes = false;
        boolean temDocumentoPrincipal = false;
        int entradas = 0;
        long descomprimido = 0;

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(conteudo))) {
            byte[] buffer = new byte[TAMANHO_DO_BUFFER];
            ZipEntry entrada;
            while ((entrada = zip.getNextEntry()) != null) {
                if (++entradas > MAXIMO_DE_ENTRADAS) {
                    throw new DocumentoInvalidoException(
                            "pacote com entradas demais (limite " + MAXIMO_DE_ENTRADAS + ")");
                }

                String nome = entrada.getName();
                if ("[Content_Types].xml".equals(nome)) {
                    temContentTypes = true;
                } else if ("word/document.xml".equals(nome)) {
                    temDocumentoPrincipal = true;
                }

                // Ler de fato: o tamanho declarado no cabecalho do zip e do
                // proprio arquivo, ou seja, tambem e entrada do usuario.
                int lidos;
                while ((lidos = zip.read(buffer)) > 0) {
                    descomprimido += lidos;
                    if (descomprimido > MAXIMO_DESCOMPRIMIDO_BYTES) {
                        throw new DocumentoInvalidoException(
                                "conteudo descomprimido excede o limite permitido");
                    }
                }
            }
        } catch (ZipException e) {
            throw new DocumentoInvalidoException("pacote zip corrompido", e);
        } catch (IOException e) {
            throw new DocumentoInvalidoException("falha ao ler o pacote enviado", e);
        }

        if (!temContentTypes) {
            throw new DocumentoInvalidoException("pacote sem [Content_Types].xml: nao e OOXML");
        }
        if (!temDocumentoPrincipal) {
            throw new DocumentoInvalidoException(
                    "pacote sem word/document.xml: nao e WordprocessingML (DOCX)");
        }
        return TIPO_MIME_DOCX;
    }
}
