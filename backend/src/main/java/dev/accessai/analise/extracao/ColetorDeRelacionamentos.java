package dev.accessai.analise.extracao;

import java.util.HashMap;
import java.util.Map;
import javax.xml.stream.XMLStreamReader;

/**
 * Le os arquivos {@code .rels} e monta o mapa que resolve o destino dos links.
 *
 * <p>A chave e o par (parte dona, id do relacionamento): dois cabecalhos podem
 * usar {@code rId4} para destinos diferentes, e um mapa so por id misturaria os
 * dois.
 *
 * <p>Só relacionamento de hyperlink entra. Imagem, fonte e cabecalho tambem sao
 * relacionamentos, e carrega-los seria guardar memoria para nada.
 */
final class ColetorDeRelacionamentos implements ColetorDeParte {

    private final Map<String, String> destinos = new HashMap<>();
    private String parteDona;

    @Override
    public boolean aceita(String parte) {
        return Ooxml.ehRelacionamento(parte);
    }

    @Override
    public void aoIniciarParte(String parte) {
        this.parteDona = Ooxml.parteDonaDoRelacionamento(parte);
    }

    @Override
    public void aoAbrirElemento(XMLStreamReader r, int profundidade) {
        if (!Ooxml.NS_RELS.equals(r.getNamespaceURI())
                || !"Relationship".equals(r.getLocalName())) {
            return;
        }
        if (!Ooxml.TIPO_REL_HYPERLINK.equals(r.getAttributeValue(null, "Type"))) {
            return;
        }
        String id = r.getAttributeValue(null, "Id");
        String destino = r.getAttributeValue(null, "Target");
        if (id != null && destino != null) {
            destinos.put(chave(parteDona, id), destino);
        }
    }

    /** {@code null} quando o link nao tem relacionamento correspondente. */
    String destinoDe(String parte, String idDoRelacionamento) {
        if (idDoRelacionamento == null) {
            return null;
        }
        return destinos.get(chave(parte, idDoRelacionamento));
    }

    private static String chave(String parte, String id) {
        return parte + '#' + id;
    }
}
