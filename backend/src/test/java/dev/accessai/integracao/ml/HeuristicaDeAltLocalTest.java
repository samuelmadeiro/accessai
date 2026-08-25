package dev.accessai.integracao.ml;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

/**
 * O contrato entre as duas implementacoes da heuristica.
 *
 * <p>A regra existe em Python ({@code training.modelo.BaselineHeuristico}) e
 * aqui. Duas copias da mesma regra divergem — foi o defeito da lista branca de
 * partes OOXML, corrigido na Slice 4. Este teste e o que faz a divergencia
 * aparecer no build em vez de em producao: ele reproduz TODAS as linhas de
 * {@code docs/ml/heuristica-alt.golden.json}, que e gerado a partir do Python.
 *
 * <p>Se este teste cair depois de alguem mexer na heuristica Python e regenerar
 * o golden, a mensagem e clara: a implementacao Java ficou para tras. O
 * contrario — mexer aqui sem mexer la — cai do mesmo jeito.
 */
@DisplayName("HeuristicaDeAltLocal")
class HeuristicaDeAltLocalTest {

    private static final String GOLDEN = "ml/heuristica-alt.golden.json";

    private final HeuristicaDeAltLocal heuristica = new HeuristicaDeAltLocal();

    @Test
    @DisplayName("reproduz o corpus de contrato gerado pelo Python, caso a caso")
    void concordaComOPython() {
        List<Caso> casos = carregar().casos();

        assertThat(casos)
                .as("golden vazio faria este teste passar sem provar nada")
                .isNotEmpty();
        assertThat(casos).allSatisfy(caso ->
                assertThat(heuristica.classificar(caso.alt()))
                        .as("alt %s", caso.alt())
                        .isEqualTo(caso.categoria()));
    }

    @Test
    @DisplayName("o corpus cobre as tres classes")
    void corpusCobreAsTresClasses() {
        // Um golden so de INSUFFICIENT passaria com uma implementacao que
        // devolve INSUFFICIENT para tudo.
        assertThat(carregar().casos()).extracting(Caso::categoria)
                .contains(HeuristicaDeAltLocal.BOM, HeuristicaDeAltLocal.FRACO,
                        HeuristicaDeAltLocal.INSUFICIENTE);
    }

    @Test
    @DisplayName("predizer devolve a resposta marcada como regra")
    void predizerDeclaraProcedencia() {
        RespostaMlDTO resposta = heuristica.predizer("IMG_0421.jpg");

        assertThat(resposta.categoria()).isEqualTo(HeuristicaDeAltLocal.INSUFICIENTE);
        assertThat(resposta.usouHeuristica())
                .as("regra que nao se declara vira 'ML que e if/else'").isTrue();
        assertThat(resposta.confianca())
                .as("regra nao tem probabilidade").isNull();
        assertThat(resposta.modeloVersao()).isNull();
    }

    @Test
    @DisplayName("alt nulo nao estoura e cai no mesmo ramo do alt vazio")
    void altNuloNaoEstoura() {
        // O caminho de fallback roda quando algo ja deu errado; nao e onde uma
        // NullPointerException pode aparecer. Nulo e tratado como vazio, e o
        // vazio esta no golden — entao este comportamento e acordado com o
        // Python, nao inventado aqui.
        assertThat(heuristica.classificar(null))
                .isEqualTo(heuristica.classificar(""));
    }

    private static Golden carregar() {
        try (InputStream in = new ClassPathResource(GOLDEN).getInputStream()) {
            return new ObjectMapper().readValue(in, Golden.class);
        } catch (IOException e) {
            throw new UncheckedIOException("golden ausente em " + GOLDEN, e);
        }
    }

    record Golden(List<String> leiaAntes, List<Caso> casos) {
    }

    record Caso(String alt, String categoria) {
    }
}
