package spike;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Acesso ao corpus de teste em src/test/resources/corpus. */
final class Corpus {

    private Corpus() {
    }

    static Path diretorio() {
        try {
            return Path.of(Corpus.class.getResource("/corpus").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("corpus nao encontrado no classpath", e);
        }
    }

    static List<Path> arquivos() {
        try (var s = Files.list(diretorio())) {
            return s.filter(p -> p.toString().endsWith(".docx"))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Path arquivo(String prefixo) {
        return arquivos().stream()
                .filter(p -> p.getFileName().toString().startsWith(prefixo))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "nenhum arquivo do corpus comeca com " + prefixo));
    }
}
