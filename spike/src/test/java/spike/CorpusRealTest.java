package spike;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Valida o extrator contra o corpus REAL baixado por scripts/fetch-corpus.py.
 *
 * <p>Diferente do corpus sintetico, aqui eu nao escrevi os arquivos e nao sei a
 * resposta certa de antemao. Entao a asserção nao e sobre o conteudo extraido —
 * e sobre robustez: nenhum documento publico real pode derrubar o extrator.
 * O relatorio impresso e a evidencia para a decisao humana.
 */
@DisplayName("Corpus real (documentos publicos brasileiros)")
class CorpusRealTest {

    private static Path diretorio() {
        String prop = System.getProperty("spike.corpusReal");
        if (prop != null) {
            return Path.of(prop);
        }
        return Path.of("..", "datasets", "corpus", "raw").toAbsolutePath().normalize();
    }

    private static List<Path> documentos() throws IOException {
        try (var s = Files.list(diretorio())) {
            return s.filter(p -> p.toString().endsWith(".docx"))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .toList();
        }
    }

    @Test
    @DisplayName("nenhum documento publico real derruba o extrator")
    void extratorSobreviveAoCorpusReal() throws IOException {
        Path dir = diretorio();
        assumeTrue(Files.isDirectory(dir),
                "corpus real ausente; rode: python scripts/fetch-corpus.py");

        List<Path> docs = documentos();
        assumeTrue(!docs.isEmpty(), "corpus real vazio");

        var extrator = new ExtratorXmlDireto();
        var poi = new ExtratorPoi();

        List<String> quebras = new ArrayList<>();
        List<String> divergencias = new ArrayList<>();
        Map<AltText.Status, Integer> totais = new LinkedHashMap<>();
        Map<String, Integer> partesComImagem = new TreeMap<>();
        int totalImagens = 0;

        System.out.println("\n=== corpus real: " + docs.size() + " documentos ===");

        for (Path doc : docs) {
            String nome = doc.getFileName().toString();
            List<AltText.Imagem> achados;
            try {
                achados = extrator.extrair(doc);
            } catch (Exception e) {
                quebras.add(nome + " -> " + e.getClass().getSimpleName() + ": " + e.getMessage());
                System.out.printf("  %-52s QUEBROU %s%n", corta(nome), e);
                continue;
            }

            totalImagens += achados.size();
            for (AltText.Imagem img : achados) {
                totais.merge(img.status(), 1, Integer::sum);
                partesComImagem.merge(img.parte(), 1, Integer::sum);
            }

            // O POI ficou de fora da decisao, mas continua sendo o plano B.
            // Divergencia em arquivo real e informacao, nao falha de teste.
            try {
                if (!poi.extrair(doc).equals(achados)) {
                    divergencias.add(nome);
                }
            } catch (Exception e) {
                divergencias.add(nome + " (POI lancou " + e.getClass().getSimpleName() + ")");
            }

            System.out.printf("  %-52s %d imagem(ns)%s%n",
                    corta(nome), achados.size(), resumo(achados));
        }

        System.out.println("\n  total de imagens: " + totalImagens);
        System.out.println("  por status: " + totais);
        System.out.println("  partes onde havia imagem: " + partesComImagem);
        System.out.println("  divergencias POI x XML: " + divergencias);

        assertTrue(quebras.isEmpty(),
                () -> "documentos reais derrubaram o extrator:\n  " + String.join("\n  ", quebras));
    }

    private static String resumo(List<AltText.Imagem> achados) {
        if (achados.isEmpty()) {
            return "";
        }
        var porStatus = new TreeMap<String, Integer>();
        for (AltText.Imagem i : achados) {
            porStatus.merge(i.status().name(), 1, Integer::sum);
        }
        return "  " + porStatus;
    }

    private static String corta(String s) {
        return s.length() <= 50 ? s : s.substring(0, 47) + "...";
    }
}
