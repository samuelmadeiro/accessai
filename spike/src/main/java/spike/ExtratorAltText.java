package spike;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Contrato comum aos dois caminhos comparados no spike. */
public interface ExtratorAltText {

    /**
     * @return uma entrada por imagem encontrada, na ordem em que aparecem
     */
    List<AltText.Imagem> extrair(Path docx) throws IOException;

    /** Nome do caminho, para os relatorios de comparacao. */
    String nome();
}
