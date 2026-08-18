package dev.accessai.analise.regras;

import dev.accessai.analise.dominio.Problema;
import dev.accessai.analise.extracao.DocumentoExtraido;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Documento sem titulo nas propriedades — WCAG 2.4.2, nivel A (com substituicao
 * de termo pelo WCAG2ICT: "web page" le-se "documento nao-web").
 *
 * <p>O titulo aqui e {@code dc:title} de {@code docProps/core.xml}, nao o
 * primeiro paragrafo e nao o nome do arquivo. Nome de arquivo nao serve: muda
 * quando alguem salva de novo e nao acompanha o documento dentro de um anexo.
 *
 * <p>Ausente e em branco recebem a mesma decisao — nos dois casos nao ha o que
 * anunciar — mas evidencias diferentes, para quem for corrigir saber o que
 * procurar.
 */
@Component
public class RegraTituloAusente implements RegraDeAcessibilidade {

    private static final String ID = "TITULO_AUSENTE";
    private static final String CRITERIO = "2.4.2";
    private static final String PARTE = "docProps/core.xml";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String criterioWcag() {
        return CRITERIO;
    }

    @Override
    public List<Achado> avaliar(DocumentoExtraido documento) {
        if (!documento.semTitulo()) {
            return List.of();
        }
        String evidencia = documento.titulo().isEmpty()
                ? "o pacote nao declara dc:title em docProps/core.xml"
                : "dc:title existe em docProps/core.xml mas esta em branco";
        // MEDIA: atrapalha identificar o documento, mas nao impede a leitura do
        // conteudo, ao contrario de tabela sem cabecalho ou imagem sem alt.
        return List.of(new Achado(Problema.Severidade.MEDIA, PARTE, evidencia));
    }
}
