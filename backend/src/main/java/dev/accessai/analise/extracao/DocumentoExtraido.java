package dev.accessai.analise.extracao;

import java.util.List;
import java.util.Optional;

/**
 * Tudo que o Rule Engine precisa saber sobre um documento, ja fora do XML.
 *
 * <p>Este record e a fronteira entre extracao e regras. Regra nao abre zip, nao
 * conhece namespace e nao sabe o que e {@code w:tblHeader}: ela recebe fatos e
 * decide. Foi o que permitiu trocar a assinatura de
 * {@code avaliar(List&lt;ImagemDoDocumento&gt;)} por uma so ao adicionar cinco
 * regras, em vez de um parametro novo por regra.
 *
 * @param imagens           desenhos com bitmap, com a situacao do alt text
 * @param tabelas           tabelas na ordem em que aparecem
 * @param cabecalhos        paragrafos de titulo do corpo, em ordem
 * @param links             hyperlinks com destino resolvido
 * @param idiomas           ocorrencias de w:lang; vazio significa nao declarado
 * @param titulo            dc:title de docProps/core.xml; vazio quando ausente
 */
public record DocumentoExtraido(
        List<ImagemDoDocumento> imagens,
        List<TabelaDoDocumento> tabelas,
        List<CabecalhoDoDocumento> cabecalhos,
        List<HyperlinkDoDocumento> links,
        List<IdiomaDeclarado> idiomas,
        Optional<String> titulo) {

    public DocumentoExtraido {
        imagens = List.copyOf(imagens);
        tabelas = List.copyOf(tabelas);
        cabecalhos = List.copyOf(cabecalhos);
        links = List.copyOf(links);
        idiomas = List.copyOf(idiomas);
    }

    /** Documento sem nada extraido. Util em teste de regra que so olha uma coisa. */
    public static DocumentoExtraido vazio() {
        return new DocumentoExtraido(List.of(), List.of(), List.of(), List.of(), List.of(),
                Optional.empty());
    }

    /**
     * O pacote nao traz {@code docProps/core.xml} ou traz {@code dc:title} em
     * branco. Ausente e vazio recebem o mesmo tratamento aqui de proposito: nos
     * dois casos nao ha titulo para um leitor de tela anunciar. A diferenca
     * aparece na evidencia, nao na decisao.
     */
    public boolean semTitulo() {
        return titulo.map(String::isBlank).orElse(true);
    }
}
