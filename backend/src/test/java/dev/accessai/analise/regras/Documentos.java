package dev.accessai.analise.regras;

import dev.accessai.analise.extracao.CabecalhoDoDocumento;
import dev.accessai.analise.extracao.DocumentoExtraido;
import dev.accessai.analise.extracao.HyperlinkDoDocumento;
import dev.accessai.analise.extracao.IdiomaDeclarado;
import dev.accessai.analise.extracao.ImagemDoDocumento;
import dev.accessai.analise.extracao.TabelaDoDocumento;
import java.util.List;
import java.util.Optional;

/**
 * Monta {@link DocumentoExtraido} para teste de regra.
 *
 * <p>Teste de regra nao passa por zip nem por XML de proposito: a regra so
 * conhece fatos, e montar um .docx para testar "H1 seguido de H3" misturaria o
 * defeito da regra com o defeito do extrator. Quem prova que o XML vira fatos
 * corretos e o teste do extrator.
 */
final class Documentos {

    private Documentos() {
    }

    static DocumentoExtraido comImagens(ImagemDoDocumento... imagens) {
        return montar(List.of(imagens), List.of(), List.of(), List.of(), List.of(),
                Optional.of("titulo"));
    }

    static DocumentoExtraido comTabelas(TabelaDoDocumento... tabelas) {
        return montar(List.of(), List.of(tabelas), List.of(), List.of(), List.of(),
                Optional.of("titulo"));
    }

    static DocumentoExtraido comCabecalhos(CabecalhoDoDocumento... cabecalhos) {
        return montar(List.of(), List.of(), List.of(cabecalhos), List.of(), List.of(),
                Optional.of("titulo"));
    }

    static DocumentoExtraido comLinks(HyperlinkDoDocumento... links) {
        return montar(List.of(), List.of(), List.of(), List.of(links), List.of(),
                Optional.of("titulo"));
    }

    static DocumentoExtraido comIdiomas(IdiomaDeclarado... idiomas) {
        return montar(List.of(), List.of(), List.of(), List.of(), List.of(idiomas),
                Optional.of("titulo"));
    }

    static DocumentoExtraido comTitulo(Optional<String> titulo) {
        return montar(List.of(), List.of(), List.of(), List.of(), List.of(), titulo);
    }

    static CabecalhoDoDocumento titulo(int nivel, String texto) {
        return new CabecalhoDoDocumento("word/document.xml", nivel, texto);
    }

    static TabelaDoDocumento tabela(int indice, int linhas, boolean comCabecalho) {
        return new TabelaDoDocumento("word/document.xml", indice, linhas, comCabecalho);
    }

    static HyperlinkDoDocumento link(String texto, String destino) {
        return new HyperlinkDoDocumento("word/document.xml", texto, destino);
    }

    private static DocumentoExtraido montar(List<ImagemDoDocumento> imagens,
                                            List<TabelaDoDocumento> tabelas,
                                            List<CabecalhoDoDocumento> cabecalhos,
                                            List<HyperlinkDoDocumento> links,
                                            List<IdiomaDeclarado> idiomas,
                                            Optional<String> titulo) {
        return new DocumentoExtraido(imagens, tabelas, cabecalhos, links, idiomas, titulo);
    }
}
