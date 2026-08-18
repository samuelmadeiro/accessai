package dev.accessai.analise.extracao;

import java.util.Set;

/**
 * Namespaces e nomes de parte do pacote OOXML.
 *
 * <p>Uma classe so para isto porque namespace errado nao quebra o build: o
 * elemento simplesmente nunca casa, o coletor devolve lista vazia e o documento
 * pontua como limpo. Falso negativo silencioso e o defeito mais caro deste
 * projeto (ADR 0008) — entao a string vive num lugar so.
 */
final class Ooxml {

    private Ooxml() {
    }

    static final String NS_W =
            "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    static final String NS_R =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    static final String NS_WP =
            "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing";
    static final String NS_MC =
            "http://schemas.openxmlformats.org/markup-compatibility/2006";
    static final String NS_VML = "urn:schemas-microsoft-com:vml";
    static final String NS_PIC =
            "http://schemas.openxmlformats.org/drawingml/2006/picture";
    static final String NS_A =
            "http://schemas.openxmlformats.org/drawingml/2006/main";
    static final String NS_DC = "http://purl.org/dc/elements/1.1/";
    static final String NS_RELS =
            "http://schemas.openxmlformats.org/package/2006/relationships";

    static final String PARTE_DOCUMENTO = "word/document.xml";
    static final String PARTE_ESTILOS = "word/styles.xml";
    static final String PARTE_PROPRIEDADES = "docProps/core.xml";

    static final String TIPO_REL_HYPERLINK =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink";

    /**
     * Partes de configuracao, que nunca tem conteudo visivel.
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

    /** Parte com conteudo visivel: corpo, cabecalho, rodape, nota, comentario. */
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

    static boolean ehRelacionamento(String nome) {
        return nome.startsWith("word/_rels/") && nome.endsWith(".rels");
    }

    /**
     * {@code word/_rels/document.xml.rels} descreve {@code word/document.xml}.
     * O link mora numa parte e o destino dele na outra; sem essa conversao nao
     * da para dizer para onde o link aponta.
     */
    static String parteDonaDoRelacionamento(String nomeDoRels) {
        String arquivo = nomeDoRels.substring(nomeDoRels.lastIndexOf('/') + 1);
        return "word/" + arquivo.substring(0, arquivo.length() - ".rels".length());
    }
}
