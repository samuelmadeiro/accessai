package dev.accessai.ia;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * O AI Gateway: o unico lugar que chama {@link AiProvider}.
 *
 * <p>Ele nao e um repassador. A ordem das quatro etapas e a decisao inteira
 * desta classe:
 *
 * <ol>
 *   <li><b>Guardrail de entrada.</b> Antes de qualquer coisa — recusar custa
 *       zero, e recusar depois de pagar a chamada seria pagar para descobrir
 *       que a pergunta nao tinha base.</li>
 *   <li><b>Teto de gasto.</b> Depois do guardrail e antes do provider.</li>
 *   <li><b>O provider.</b> A unica linha do sistema que fala com um modelo.</li>
 *   <li><b>Guardrail de saida.</b> O que o provider disse sobre regra que a
 *       analise nao encontrou nao chega ao usuario.</li>
 * </ol>
 *
 * <p>O custo e registrado DEPOIS da chamada, com o valor que ela reportou — nao
 * antes, com estimativa. Estimativa somada ao contador faria o teto ser atingido
 * por um numero que ninguem gastou.
 */
@Component
public class GatewayDeIa {

    private static final Logger log = LoggerFactory.getLogger(GatewayDeIa.class);

    private final AiProvider provider;
    private final GuardrailDeFundamentacao guardrail;
    private final ContadorDeGastoDeIa contador;
    private final MontadorDePrompt montador;

    public GatewayDeIa(AiProvider provider, GuardrailDeFundamentacao guardrail,
                       ContadorDeGastoDeIa contador, MontadorDePrompt montador) {
        this.provider = provider;
        this.guardrail = guardrail;
        this.contador = contador;
        this.montador = montador;
    }

    public @NonNull RespostaDeIa recomendar(AiProvider.@NonNull Fundamento fundamento) {
        guardrail.conferirEntrada(fundamento);
        contador.conferir();

        // O prompt e montado AQUI, nao no provider: conteudo nao confiavel
        // encosta na instrucao num lugar so.
        RespostaDeIa bruta = provider.recomendar(fundamento,
                montador.montar(fundamento));
        contador.registrar(bruta.custoEstimadoEmCentavos());

        RespostaDeIa filtrada = guardrail.filtrarSaida(fundamento, bruta);
        registrar(fundamento.analiseId(), filtrada);
        return filtrada;
    }

    /**
     * Um turno de conversa sobre a analise (Slice 7, ADR 0012).
     *
     * <p>Mesmas quatro etapas, na mesma ordem, e isso e o ponto: o copiloto nao
     * ganhou um caminho proprio para o modelo. Ele passa pelo guardrail de
     * entrada, pelo teto de gasto e pelo guardrail de saida exatamente como a
     * recomendacao passa.
     *
     * <p><b>Por turno, e nao por conversa.</b> Conferir so na abertura deixaria
     * o segundo turno entrar sem verificacao — e e no segundo que a pergunta
     * fora de escopo aparece, depois de o primeiro ter estabelecido confianca.
     * O teto de gasto pelo mesmo motivo: multi-turno cobra por turno.
     *
     * @param historico turnos anteriores, ja recortados por quem chama
     */
    public @NonNull RespostaDeConversa conversar(AiProvider.@NonNull Fundamento fundamento,
                                                 @NonNull List<AiProvider.Turno> historico) {
        guardrail.conferirEntrada(fundamento);
        contador.conferir();

        RespostaDeConversa bruta = provider.conversar(fundamento, historico,
                montador.montarConversa(fundamento, historico));
        contador.registrar(bruta.custoEstimadoEmCentavos());

        // Recusa por inteiro, e nao filtro: conversa e texto corrido, sem item
        // a descartar. O motivo esta no guardrail.
        RespostaDeConversa conferida = guardrail.conferirSaidaDeConversa(fundamento, bruta);
        log.info("turno de conversa respondido para analiseId={}: procedencia={}, "
                + "modelo={}, historico={} turno(s), custo={} centavo(s)",
                fundamento.analiseId(), conferida.procedencia(), conferida.modelo(),
                historico.size(), conferida.custoEstimadoEmCentavos());
        return conferida;
    }

    /**
     * Uma linha por geracao, com a procedencia.
     *
     * <p>Sem ela, "o AccessAI usa IA" viraria uma crenca que ninguem confere —
     * o mesmo defeito que o log de `usouHeuristica` evita no ML Service.
     */
    private void registrar(UUID analiseId, RespostaDeIa resposta) {
        log.info("recomendacoes geradas para analiseId={}: {} item(ns), procedencia={}, "
                + "modelo={}, custo={} centavo(s)",
                analiseId, resposta.recomendacoes().size(), resposta.procedencia(),
                resposta.modelo(), resposta.custoEstimadoEmCentavos());
    }

    /** Para a API dizer de onde a resposta veio sem gerar nada. */
    public AiProvider.@NonNull Procedencia procedencia() {
        return provider.procedencia();
    }
}
