package dev.accessai.analise.extracao;

import java.util.ArrayList;
import java.util.List;
import javax.xml.stream.XMLStreamReader;

/**
 * Coleta as declaracoes de idioma do pacote.
 *
 * <p>Duas partes interessam: {@code word/styles.xml}, onde
 * {@code docDefaults/rPrDefault} guarda o idioma padrao do documento, e
 * {@code word/document.xml}, onde runs podem declarar idioma local. A diferenca
 * importa: idioma so em trecho nao satisfaz "idioma padrao do documento" (3.1.1),
 * e a regra precisa poder dizer isso.
 *
 * <p>{@code w:val="x-none"} nao e idioma: e a marca de "sem verificacao
 * ortografica". Aceita-lo faria todo documento parecer conforme.
 */
final class ColetorDeIdioma implements ColetorDeParte {

    private static final String SEM_IDIOMA = "x-none";

    private final List<IdiomaDeclarado> idiomas = new ArrayList<>();
    private String parteAtual;

    @Override
    public boolean aceita(String parte) {
        return Ooxml.PARTE_ESTILOS.equals(parte) || Ooxml.PARTE_DOCUMENTO.equals(parte);
    }

    @Override
    public void aoIniciarParte(String parte) {
        this.parteAtual = parte;
    }

    @Override
    public void aoAbrirElemento(XMLStreamReader r, int profundidade) {
        if (!Ooxml.NS_W.equals(r.getNamespaceURI()) || !"lang".equals(r.getLocalName())) {
            return;
        }
        String valor = r.getAttributeValue(Ooxml.NS_W, "val");
        if (valor == null || valor.isBlank() || SEM_IDIOMA.equalsIgnoreCase(valor.trim())) {
            return;
        }
        idiomas.add(new IdiomaDeclarado(parteAtual, valor.trim()));
    }

    List<IdiomaDeclarado> idiomas() {
        return List.copyOf(idiomas);
    }
}
