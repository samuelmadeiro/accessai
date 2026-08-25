package dev.accessai.ia;

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

    public GatewayDeIa(AiProvider provider, GuardrailDeFundamentacao guardrail,
                       ContadorDeGastoDeIa contador) {
        this.provider = provider;
        this.guardrail = guardrail;
        this.contador = contador;
    }

    public @NonNull RespostaDeIa recomendar(AiProvider.@NonNull Fundamento fundamento) {
        guardrail.conferirEntrada(fundamento);
        contador.conferir();

        RespostaDeIa bruta = provider.recomendar(fundamento);
        contador.registrar(bruta.custoEstimadoEmCentavos());

        RespostaDeIa filtrada = guardrail.filtrarSaida(fundamento, bruta);
        registrar(fundamento.analiseId(), filtrada);
        return filtrada;
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
