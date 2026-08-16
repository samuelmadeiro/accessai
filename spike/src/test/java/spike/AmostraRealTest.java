package spike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O unico arquivo REAL do spike: um export de Google Docs (fontes GoogleSans
 * embutidas, sem docProps — assinatura do exportador).
 *
 * <p>Ele NAO e copiado para o repositorio: e material de terceiro, com licenca
 * propria, e o spike nao tem direito de redistribui-lo. O caminho tambem nao
 * fica no codigo — a versao anterior trazia o diretorio de Downloads de uma
 * maquina especifica, o que vazava o nome do usuario e nao rodava em lugar
 * nenhum. Sem a propriedade, o teste se abstem.
 *
 * <pre>
 * mvn test -Dspike.amostraReal=/caminho/para/arquivo.docx
 * </pre>
 *
 * <p>Valor deste teste: os 10 arquivos do corpus sao sinteticos, escritos por
 * mim. Um arquivo que eu nao escrevi e a unica evidencia de que as premissas
 * do extrator sobrevivem fora do laboratorio.
 */
@DisplayName("Amostra real (export de Google Docs)")
class AmostraRealTest {

    private static final String PROPRIEDADE = "spike.amostraReal";

    @Test
    @DisplayName("os dois caminhos concordam num arquivo que eu nao escrevi")
    void extratoresConcordamNaAmostraReal() throws IOException {
        String caminho = System.getProperty(PROPRIEDADE);
        assumeTrue(caminho != null && !caminho.isBlank(),
                "amostra real nao informada; rode com -D" + PROPRIEDADE + "=/caminho/arquivo.docx");

        Path arquivo = Path.of(caminho);
        assumeTrue(Files.isReadable(arquivo), "amostra real ilegivel: " + arquivo);

        List<AltText.Imagem> viaPoi = new ExtratorPoi().extrair(arquivo);
        List<AltText.Imagem> viaXml = new ExtratorXmlDireto().extrair(arquivo);

        assertEquals(viaXml, viaPoi, "os dois caminhos divergiram num arquivo real");
        assertFalse(viaXml.isEmpty(), "a amostra real tem imagem; extrair 0 e falso negativo");
    }
}
