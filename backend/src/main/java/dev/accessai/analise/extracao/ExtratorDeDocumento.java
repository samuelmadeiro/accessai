package dev.accessai.analise.extracao;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Le um DOCX e devolve os fatos que o Rule Engine precisa.
 *
 * <p>Parsing direto do XML, sem Apache POI (ADR 0008): o POI nao enxerga desenho
 * dentro de {@code mc:AlternateContent} e devolve zero imagens nesse caso —
 * falso negativo silencioso, que num produto de score significa afirmar
 * conformidade inexistente.
 *
 * <p>Uma passagem pelo pacote alimenta todos os coletores. Os coletores sao
 * criados por chamada, e nao injetados: eles guardam o estado da varredura e um
 * bean singleton mutavel quebraria com duas analises simultaneas.
 */
@Component
public class ExtratorDeDocumento {

    private final VarredorDoPacote varredor = new VarredorDoPacote();

    public DocumentoExtraido extrair(byte[] docx) {
        ColetorDeImagens imagens = new ColetorDeImagens();
        ColetorDeTabelas tabelas = new ColetorDeTabelas();
        ColetorDeCabecalhos cabecalhos = new ColetorDeCabecalhos();
        ColetorDeHyperlinks links = new ColetorDeHyperlinks();
        ColetorDeIdioma idioma = new ColetorDeIdioma();
        ColetorDeTitulo titulo = new ColetorDeTitulo();
        ColetorDeRelacionamentos relacionamentos = new ColetorDeRelacionamentos();

        varredor.varrer(docx, List.of(imagens, tabelas, cabecalhos, links, idioma, titulo,
                relacionamentos));

        return new DocumentoExtraido(
                imagens.imagens(),
                tabelas.tabelas(),
                cabecalhos.cabecalhos(),
                resolverDestinos(links.links(), relacionamentos),
                idioma.idiomas(),
                titulo.titulo());
    }

    /**
     * O link so fica completo depois do pacote inteiro: o {@code w:hyperlink}
     * esta numa parte e o destino dele no {@code .rels} correspondente, e a
     * ordem das entradas no zip nao e garantida.
     */
    private static List<HyperlinkDoDocumento> resolverDestinos(
            List<ColetorDeHyperlinks.LinkBruto> brutos, ColetorDeRelacionamentos relacionamentos) {
        return brutos.stream()
                .map(bruto -> new HyperlinkDoDocumento(
                        bruto.partePacote(),
                        bruto.texto(),
                        relacionamentos.destinoDe(bruto.partePacote(), bruto.relacionamento())))
                .toList();
    }
}
