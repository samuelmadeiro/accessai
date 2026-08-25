package dev.accessai.integracao.ml;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * A heuristica de qualidade de alt text, do lado Java.
 *
 * <p><b>Isto e uma segunda implementacao da mesma regra, e isso e um risco
 * conhecido.</b> Ate a Slice 5 a heuristica morava so no servico Python, de
 * proposito: duas copias da mesma regra divergem — foi o defeito da lista branca
 * de partes OOXML. O argumento contra portar era bom, e a razao de ter mudado e
 * outra: sem esta classe, o Python fora do ar significava <b>zero</b>
 * classificacao, e o usuario via um documento analisado pela metade sem nenhuma
 * explicacao do porque.
 *
 * <p>O que torna a duplicacao administravel e o corpus de contrato em
 * {@code docs/ml/heuristica-alt.golden.json}: os dois lados tem um teste que
 * reproduz todas as linhas dele. Quem mudar uma regra de um lado so quebra o
 * teste do outro. A fonte da verdade continua sendo o Python — o JSON e gerado
 * de la por {@code scripts/gerar_golden_heuristica.py}.
 *
 * <p><b>O que esta classe NAO faz:</b> ela nao se apresenta como modelo. Toda
 * resposta que sai daqui carrega {@code usouHeuristica = true} e
 * {@code confianca = null}, igual a do servico Python quando ele responde sem
 * artefato. Regra nao tem probabilidade, e um {@code 1.0} ali faria o resto do
 * sistema tratar regra como modelo confiante.
 */
@Component
public class HeuristicaDeAltLocal {

    static final String BOM = "GOOD";
    static final String FRACO = "WEAK";
    static final String INSUFICIENTE = "INSUFFICIENT";

    /**
     * Expressoes que, sozinhas, nao descrevem nada. Espelham
     * {@code EXPRESSOES_GENERICAS} do Python, na mesma ordem.
     */
    private static final List<String> EXPRESSOES_GENERICAS = List.of(
            "clique aqui", "click here", "saiba mais", "leia mais", "veja mais",
            "imagem", "image", "foto", "figura", "logo", "logotipo", "banner",
            "icone", "ícone", "picture", "sem titulo", "sem título", "untitled");

    /**
     * {@code UNICODE_CHARACTER_CLASS} nao e detalhe: sem ele o {@code \w} do
     * Java casa so ASCII, enquanto o do Python casa letra acentuada por padrao.
     * Um alt chamado {@code "gráfico.png"} seria nome de arquivo de um lado e
     * nao do outro — divergencia silenciosa entre as duas implementacoes.
     */
    private static final Pattern NOME_DE_ARQUIVO = Pattern.compile(
            "^[\\w\\-. ]+\\.(jpe?g|png|gif|bmp|svg|webp|emf|wmf|tiff?)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
                    | Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern SO_RUIDO = Pattern.compile(
            "^[\\W\\d_]+$", Pattern.UNICODE_CHARACTER_CLASS);

    private static final int CURTO_DEMAIS = 15;
    private static final int LONGO_O_BASTANTE = 40;

    /**
     * Classifica um alt text pelas regras locais.
     *
     * <p>A ordem dos testes e a mesma do Python e importa: nome de arquivo antes
     * de comprimento, porque {@code "IMG_0421.jpg"} tambem e curto; e generico
     * curto antes de generico no comeco, porque {@code "imagem"} sozinho e
     * insuficiente enquanto {@code "imagem de um predio na avenida"} e apenas
     * fraco.
     */
    public @NonNull String classificar(String alt) {
        String limpo = alt == null ? "" : alt.strip();
        String minusculo = limpo.toLowerCase(Locale.ROOT);

        if (NOME_DE_ARQUIVO.matcher(limpo).matches()
                || SO_RUIDO.matcher(limpo).matches()) {
            return INSUFICIENTE;
        }
        if (limpo.length() < CURTO_DEMAIS && contemGenerico(minusculo)) {
            return INSUFICIENTE;
        }
        if (comecaComGenerico(minusculo)) {
            return FRACO;
        }
        if (limpo.length() < CURTO_DEMAIS) {
            return FRACO;
        }
        if (limpo.length() >= LONGO_O_BASTANTE) {
            return BOM;
        }
        return FRACO;
    }

    /**
     * A resposta no mesmo formato do servico Python, marcada como heuristica.
     *
     * <p>Devolver {@link RespostaMlDTO} em vez de {@code String} e o que permite
     * ao chamador tratar predicao local e remota pelo mesmo caminho — e o que
     * garante que a marca de procedencia viaje junto, em vez de depender de
     * alguem lembrar de preenche-la.
     */
    public @NonNull RespostaMlDTO predizer(String alt) {
        return new RespostaMlDTO(classificar(alt), null, null, true);
    }

    private static boolean contemGenerico(String minusculo) {
        return EXPRESSOES_GENERICAS.stream().anyMatch(minusculo::contains);
    }

    private static boolean comecaComGenerico(String minusculo) {
        return EXPRESSOES_GENERICAS.stream().anyMatch(minusculo::startsWith);
    }
}
