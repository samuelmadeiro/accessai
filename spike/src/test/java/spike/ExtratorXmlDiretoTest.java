package spike;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("Caminho 2: parsing XML direto (StAX)")
class ExtratorXmlDiretoTest {

    private final ExtratorXmlDireto extrator = new ExtratorXmlDireto();

    static List<String> casos() {
        return EspecificacaoCorpus.ESPERADO.keySet().stream().sorted().toList();
    }

    @ParameterizedTest(name = "caso {0}")
    @MethodSource("casos")
    @DisplayName("atende a especificacao em todos os 10 casos")
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

    @Test
    @DisplayName("ausente e vazio sao estados distintos, nao sinonimos")
    void distingueAusenteDeVazio() throws IOException {
        var ausente = extrator.extrair(Corpus.arquivo("03")).getFirst();
        var vazio = extrator.extrair(Corpus.arquivo("02")).getFirst();

        assertEquals(AltText.Status.AUSENTE, ausente.status());
        assertEquals(null, ausente.texto(), "alt ausente nao tem texto");

        assertEquals(AltText.Status.VAZIO, vazio.status());
        assertEquals("", vazio.texto(), "alt vazio tem texto, so que vazio");
    }

    @Test
    @DisplayName("nao conta duas vezes o desenho repetido em mc:AlternateContent")
    void naoDuplicaAlternateContent() throws IOException {
        var r = extrator.extrair(Corpus.arquivo("06"));
        assertEquals(1, r.size(),
                "mc:Choice e mc:Fallback descrevem a MESMA imagem; contar 2 infla o denominador do score");
    }

    @Test
    @DisplayName("enxerga imagem que so existe no cabecalho")
    void enxergaCabecalho() throws IOException {
        var r = extrator.extrair(Corpus.arquivo("08"));
        assertEquals(1, r.size(), "quem le apenas word/document.xml devolve 0 aqui");
        assertEquals("word/header2.xml", r.getFirst().parte());
    }
}
