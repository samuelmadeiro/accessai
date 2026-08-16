package spike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("Caminho 1: Apache POI (XWPF)")
class ExtratorPoiTest {

    private final ExtratorPoi extrator = new ExtratorPoi();

    /** Todos os casos menos os que o POI comprovadamente nao atende. */
    static List<String> casosAtendidos() {
        return EspecificacaoCorpus.ESPERADO.keySet().stream()
                .filter(k -> !EspecificacaoCorpus.POI_NAO_ATENDE.contains(k))
                .sorted()
                .toList();
    }

    @ParameterizedTest(name = "caso {0}")
    @MethodSource("casosAtendidos")
    @DisplayName("atende a especificacao em 9 dos 10 casos")
    void atendeEspecificacao(String prefixo) throws IOException {
        var esperado = EspecificacaoCorpus.ESPERADO.get(prefixo);
        var obtido = extrator.extrair(Corpus.arquivo(prefixo));

        assertEquals(esperado.size(), obtido.size(),
                () -> "numero de imagens divergente em " + prefixo + "; obtido=" + obtido);

        for (int i = 0; i < esperado.size(); i++) {
            var e = esperado.get(i);
            var o = obtido.get(i);
            int idx = i;
            assertEquals(e.parte(), o.parte(),
                    () -> "parte divergente na imagem " + idx + " de " + prefixo);
            assertEquals(e.status(), o.status(),
                    () -> "status divergente na imagem " + idx + " de " + prefixo);
            assertEquals(e.texto(), o.texto(),
                    () -> "texto divergente na imagem " + idx + " de " + prefixo);
        }
    }

    /**
     * LIMITACAO DOCUMENTADA, nao comportamento desejado.
     *
     * <p>O XmlBeans do POI so vincula elementos previstos no schema do w:r.
     * Um w:drawing dentro de mc:AlternateContent nao aparece em
     * {@code CTR.getDrawingList()}, entao o POI devolve ZERO imagens para o
     * caso 06 — nao uma imagem com alt errado, mas imagem nenhuma.
     *
     * <p>Para o AccessAI isso e um falso negativo silencioso: um documento com
     * imagem inacessivel pontuaria como limpo. Este teste existe para travar a
     * limitacao por escrito e falhar caso uma versao futura do POI a corrija —
     * momento em que a decisao do spike deve ser reavaliada.
     */
    @Test
    @DisplayName("LIMITACAO: nao enxerga desenho dentro de mc:AlternateContent")
    void naoEnxergaAlternateContent() throws IOException {
        var esperadoPelaEspecificacao = EspecificacaoCorpus.ESPERADO.get("06");
        var obtido = extrator.extrair(Corpus.arquivo("06"));

        assertEquals(1, esperadoPelaEspecificacao.size(),
                "a especificacao continua exigindo 1 imagem neste caso");
        assertEquals(0, obtido.size(),
                "se o POI passou a enxergar AlternateContent, reavaliar a escolha do spike");
    }

    /**
     * O POI nao modela v:shape/@alt. O caso 07 so passa porque o extrator
     * reabre o XML cru do bean e busca o atributo na unha.
     */
    @Test
    @DisplayName("VML legado exige releitura do XML cru, sem acessor de API")
    void vmlExigeReleituraDoXml() throws IOException {
        var r = extrator.extrair(Corpus.arquivo("07"));
        assertEquals(1, r.size());
        assertEquals(AltText.Status.PRESENTE, r.getFirst().status());
        assertTrue(r.getFirst().nomeImagem().startsWith("vml"),
                "sem acessor, o POI nao devolve nem o identificador real do shape");
    }
}
