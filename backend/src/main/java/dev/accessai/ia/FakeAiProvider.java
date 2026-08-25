package dev.accessai.ia;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Recomendacoes de fixture, sem rede e sem custo.
 *
 * <p>O D5 prescreve exatamente isto: "FakeAiProvider implementando AiProvider,
 * devolvendo fixtures. Zero rede no CI." Ele nao e um andaime de teste — e o
 * provider ATIVO enquanto o ADR 0005 estiver em PROPOSTA, sem chave e sem teto
 * de custo aprovados.
 *
 * <p><b>Isto nao e "IA que e template string".</b> A diferenca esta em uma
 * palavra que viaja em toda resposta: {@link Procedencia#FIXTURE}. O §1 proibe
 * apresentar template como IA; não proíbe usar template declarado como template.
 * Quem consome sabe que nenhum modelo foi consultado, do mesmo jeito que
 * `usouHeuristica` diz que nenhum modelo classificou o alt.
 *
 * <p>O texto de cada fixture e deliberadamente generico e curto. Escrever
 * paragrafos elaborados aqui produziria uma demonstracao que parece o produto
 * final e some no dia em que o provider real entrar — e alguem compararia os
 * dois achando que o modelo regrediu.
 *
 * <p>{@code @ConditionalOnMissingBean}: o dia em que existir um provider real,
 * basta ele existir como bean para este sair de cena. Sem flag, sem perfil, sem
 * `if` em lugar nenhum.
 */
@Component
@ConditionalOnMissingBean(ignored = FakeAiProvider.class, value = AiProvider.class)
public class FakeAiProvider implements AiProvider {

    static final String NOME = "fixture-local";

    /**
     * Uma frase por regra do Rule Engine. A chave e o {@code regraId} porque e
     * por ele que o guardrail confere a fundamentacao.
     */
    private static final Map<String, String> POR_REGRA = Map.of(
            "IMAGEM_SEM_TEXTO_ALTERNATIVO",
            "Descreva a imagem no atributo de texto alternativo. Se ela for "
                    + "decorativa, deixe o alt vazio para que o leitor de tela a ignore.",
            "TABELA_SEM_CABECALHO",
            "Marque a primeira linha da tabela como linha de cabecalho, para que "
                    + "o leitor de tela anuncie a que coluna cada celula pertence.",
            "ORDEM_HIERARQUICA_CABECALHOS",
            "Nao pule niveis de titulo. Depois de um H1 vem H2; o salto quebra a "
                    + "navegacao por estrutura.",
            "TITULO_AUSENTE",
            "Preencha o titulo do documento nas propriedades do arquivo. E o "
                    + "primeiro dado que o leitor de tela anuncia.",
            "LINK_SEM_TEXTO_DESCRITIVO",
            "Troque o texto do link por algo que descreva o destino fora de "
                    + "contexto. 'Clique aqui' nao diz para onde leva.",
            "IDIOMA_NAO_DECLARADO",
            "Declare o idioma do documento, para que o leitor de tela escolha a "
                    + "pronuncia correta.");

    private static final String GENERICA =
            "Corrija o problema apontado seguindo o criterio WCAG citado ao lado.";

    @Override
    public @NonNull Procedencia procedencia() {
        return Procedencia.FIXTURE;
    }

    @Override
    public @NonNull String modelo() {
        return NOME;
    }

    /**
     * Uma recomendacao por achado, na ordem em que os achados chegaram.
     *
     * <p>O {@code prompt} chega montado e e IGNORADO: nao ha modelo para
     * manda-lo. Ele esta na assinatura mesmo assim porque o contrato e o mesmo
     * para todo provider — e porque um fake que aceitasse menos que o real
     * esconderia, no dia da troca, que a montagem nunca foi exercitada.
     *
     * <p>Nunca inventa achado. A lista de saida e derivada da de entrada, e e
     * por isso que este provider passa no guardrail por construcao — o que NAO
     * dispensa o guardrail: ele existe para o provider que ainda vai chegar.
     */
    @Override
    public @NonNull RespostaDeIa recomendar(@NonNull Fundamento fundamento,
                                           @NonNull String prompt) {
        List<RespostaDeIa.Recomendacao> recomendacoes = fundamento.achados().stream()
                .map(a -> new RespostaDeIa.Recomendacao(
                        a.regraId(), a.criterioWcag(),
                        POR_REGRA.getOrDefault(a.regraId(), GENERICA)))
                .toList();
        // Custo zero, e nao "custo desconhecido": nenhuma chamada paga aconteceu.
        return new RespostaDeIa(recomendacoes, Procedencia.FIXTURE, NOME, 0L);
    }
}
