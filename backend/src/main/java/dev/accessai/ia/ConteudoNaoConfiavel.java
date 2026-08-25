package dev.accessai.ia;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;

/**
 * Texto que veio de fora e vai entrar num prompt.
 *
 * <p>O {@code CONTRIBUTING.md} secao 5 exige: "trate conteudo extraido como nao
 * confiavel ao montar prompt". Duas coisas chegam de fora e acabam num prompt —
 * a EVIDENCIA extraida do `.docx` enviado e a PERGUNTA digitada. As duas sao
 * hostis pela mesma razao: quem escreve o documento nao e necessariamente quem
 * pede a analise, e nenhum dos dois e o dono do sistema.
 *
 * <p><b>Isto nao e um filtro de palavras proibidas.</b> Lista negra de "ignore as
 * instrucoes anteriores" perde para a primeira parafrase, e a cada bypass alguem
 * adiciona mais uma linha. O que este arquivo faz e outra coisa, em tres
 * camadas:
 *
 * <ol>
 *   <li><b>Tira o poder de formatar.</b> Quebra de linha, caractere de controle
 *       e marcador de papel ({@code System:}, {@code Assistant:}, {@code ###},
 *       cerca de codigo, {@code <|...|>}) sao o que permite a um texto fingir
 *       que a mensagem acabou e outra comecou. Sem eles, o conteudo continua
 *       sendo conteudo.</li>
 *   <li><b>Limita o tamanho.</b> Evidencia longa e superficie de ataque, e um
 *       trecho de 500 caracteres ja e mais que suficiente para explicar um
 *       problema de acessibilidade.</li>
 *   <li><b>Envelopa com delimitador imprevisivel.</b> Um nonce por chamada. O
 *       texto nao tem como fechar um bloco cujo delimitador ele nao conhece —
 *       e e por isso que o nonce e sorteado, e nao uma constante.</li>
 * </ol>
 *
 * <p><b>A ultima linha de defesa nao esta aqui.</b> Mesmo que uma injecao
 * sobreviva a tudo isto e convenca o modelo a recomendar outra coisa,
 * {@link GuardrailDeFundamentacao#filtrarSaida} descarta o que citar regra
 * ausente da analise. Sanitizar reduz a chance; o guardrail de saida limita o
 * estrago.
 */
public final class ConteudoNaoConfiavel {

    /**
     * Teto de um trecho de evidencia. Nao e limite de seguranca sozinho — e
     * reducao de superficie: quanto menos texto de terceiro entra no prompt,
     * menos espaco para construir uma instrucao convincente.
     */
    static final int MAXIMO_DE_CARACTERES = 500;

    private static final String CORTE = "[...]";

    /**
     * Sequencias que so servem para simular estrutura de conversa.
     *
     * <p>Nenhuma delas aparece em evidencia legitima de acessibilidade — texto
     * de alt, titulo de documento ou texto de link. Elas sao NEUTRALIZADAS, nao
     * removidas: apagar mudaria o texto que o usuario ve na resposta, e trocar
     * por um marcador visivel deixa o ataque aparente para quem ler o log.
     */
    private static final List<Pattern> MARCADORES_DE_PAPEL = List.of(
            Pattern.compile("(?i)\\b(system|assistant|user|human)\\s*:"),
            Pattern.compile("<\\|[^|>]*\\|>"),
            Pattern.compile("(?m)^\\s*#{2,}"),
            Pattern.compile("```"),
            Pattern.compile("(?i)\\[/?(INST|SYS)]"));

    private static final Pattern CONTROLE_E_QUEBRA = Pattern.compile("[\\p{Cntrl}\\p{Cf}]+");
    private static final Pattern ESPACOS = Pattern.compile("\\s{2,}");

    private static final SecureRandom SORTEIO = new SecureRandom();

    private ConteudoNaoConfiavel() {
    }

    /**
     * Devolve o texto sem poder de formatar, achatado numa linha e truncado.
     *
     * <p>Nulo vira string vazia em vez de propagar: este metodo roda no caminho
     * de montar prompt, e {@code NullPointerException} ali seria falha de
     * disponibilidade causada por documento malformado.
     */
    public static @NonNull String sanitizar(String bruto) {
        if (bruto == null || bruto.isBlank()) {
            return "";
        }
        String limpo = CONTROLE_E_QUEBRA.matcher(bruto).replaceAll(" ");
        for (Pattern marcador : MARCADORES_DE_PAPEL) {
            limpo = marcador.matcher(limpo).replaceAll("[removido]");
        }
        limpo = ESPACOS.matcher(limpo).replaceAll(" ").strip();

        if (limpo.length() > MAXIMO_DE_CARACTERES) {
            limpo = limpo.substring(0, MAXIMO_DE_CARACTERES - CORTE.length()) + CORTE;
        }
        return limpo;
    }

    /**
     * Envelopa o texto em delimitadores que ele nao tem como fechar.
     *
     * <p>O nonce e sorteado a cada chamada. Com delimitador fixo — {@code ---} ou
     * {@code <dados>} — bastaria ao atacante escrever o mesmo delimitador para
     * "sair" do bloco e o resto do texto virar instrucao.
     *
     * <p>O envelope NAO substitui a sanitizacao: ele impede a fuga do bloco,
     * enquanto a sanitizacao tira o poder de formatar dentro dele.
     */
    public static @NonNull String envelopar(String rotulo, String sanitizado) {
        byte[] bytes = new byte[8];
        SORTEIO.nextBytes(bytes);
        String nonce = HexFormat.of().formatHex(bytes);
        return "<<" + rotulo + ":" + nonce + ">>\n"
                + sanitizado + "\n"
                + "<</" + rotulo + ":" + nonce + ">>";
    }
}
