package dev.accessai.analise.extracao;

import java.util.Optional;
import javax.xml.stream.XMLStreamReader;

/**
 * Le {@code dc:title} de {@code docProps/core.xml}.
 *
 * <p>Titulo de documento OOXML nao e o primeiro paragrafo nem o nome do arquivo:
 * e propriedade do pacote. Nome de arquivo nao serve — ele muda quando alguem
 * salva de novo e nao acompanha o documento anexado num e-mail.
 *
 * <p>Ausente e vazio ficam distinguiveis: {@code Optional.empty()} quando o
 * elemento nao existe, {@code Optional.of("")} quando existe em branco. A regra
 * trata os dois como problema, mas a evidencia diz qual dos dois e.
 */
final class ColetorDeTitulo implements ColetorDeParte {

    private final StringBuilder texto = new StringBuilder();
    private boolean dentroDoTitulo;
    private boolean encontrado;

    @Override
    public boolean aceita(String parte) {
        return Ooxml.PARTE_PROPRIEDADES.equals(parte);
    }

    @Override
    public void aoAbrirElemento(XMLStreamReader r, int profundidade) {
        if (Ooxml.NS_DC.equals(r.getNamespaceURI()) && "title".equals(r.getLocalName())) {
            dentroDoTitulo = true;
            encontrado = true;
        }
    }

    @Override
    public void aoTexto(String conteudo) {
        if (dentroDoTitulo) {
            texto.append(conteudo);
        }
    }

    @Override
    public void aoFecharElemento(int profundidade) {
        dentroDoTitulo = false;
    }

    Optional<String> titulo() {
        return encontrado ? Optional.of(texto.toString()) : Optional.empty();
    }
}
