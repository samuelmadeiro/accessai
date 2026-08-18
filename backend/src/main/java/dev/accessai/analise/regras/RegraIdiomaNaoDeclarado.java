package dev.accessai.analise.regras;

import dev.accessai.analise.dominio.Problema;
import dev.accessai.analise.extracao.DocumentoExtraido;
import dev.accessai.analise.extracao.IdiomaDeclarado;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Documento sem idioma padrao declarado — WCAG 3.1.1, nivel A (com substituicao
 * de termo pelo WCAG2ICT: "web page" le-se "documento nao-web").
 *
 * <p>Sem idioma, o leitor de tela pronuncia o texto com as regras foneticas do
 * idioma configurado na maquina de quem le. Um edital em portugues lido com
 * fonetica inglesa e ininteligivel.
 *
 * <p>O idioma padrao mora em {@code word/styles.xml}, no
 * {@code docDefaults/rPrDefault}. Declaracao local em run NAO satisfaz o
 * criterio — ela cobre um trecho, nao o documento — mas muda a evidencia: dizer
 * "nao ha idioma nenhum" quando ha idioma em trechos mandaria quem for corrigir
 * procurar no lugar errado.
 */
@Component
public class RegraIdiomaNaoDeclarado implements RegraDeAcessibilidade {

    private static final String ID = "IDIOMA_NAO_DECLARADO";
    private static final String CRITERIO = "3.1.1";
    private static final String PARTE = "word/styles.xml";

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
        List<IdiomaDeclarado> idiomas = documento.idiomas();
        if (idiomas.stream().anyMatch(IdiomaDeclarado::ehPadraoDoDocumento)) {
            return List.of();
        }

        String evidencia = idiomas.isEmpty()
                ? "o documento nao declara idioma em lugar nenhum (w:lang ausente)"
                : "ha idioma declarado apenas em trechos (" + amostra(idiomas)
                        + "), mas nao no padrao do documento em word/styles.xml";
        // ALTA: afeta a leitura de TODO o texto do documento, nao de um trecho.
        return List.of(new Achado(Problema.Severidade.ALTA, PARTE, evidencia));
    }

    private static String amostra(List<IdiomaDeclarado> idiomas) {
        return idiomas.stream()
                .map(IdiomaDeclarado::valor)
                .distinct()
                .limit(3)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }
}
