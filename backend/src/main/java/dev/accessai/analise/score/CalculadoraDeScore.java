package dev.accessai.analise.score;

import dev.accessai.analise.dominio.PrincipioWcag;
import dev.accessai.analise.dominio.Problema;
import dev.accessai.config.PropriedadesAccessAi;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Calcula o score a partir dos problemas ja encontrados.
 *
 * <p>Tres coisas que este componente NAO faz, e que sao o ponto dele:
 *
 * <ul>
 *   <li><b>Nao prediz nada.</b> O score e soma ponderada de penalidades
 *       deterministicas (CLAUDE.md secao 6). ML pode ajustar a severidade de um
 *       problema no futuro; nunca a nota.</li>
 *   <li><b>Nao guarda estado.</b> O score e funcao pura dos problemas
 *       persistidos mais a configuracao, calculada na leitura. Grava-lo criaria
 *       uma copia que diverge no dia em que um peso mudar — e um numero salvo
 *       que ninguem sabe mais como foi obtido e pior que numero nenhum.</li>
 *   <li><b>Nao inventa categoria.</b> O principio sai do numero do criterio.</li>
 * </ul>
 *
 * <p>A conta, por principio avaliado:
 *
 * <pre>
 *   penalidade = soma das penalidades por severidade dos problemas do principio
 *   score      = max(0, 100 - penalidade)
 *   global     = soma(score * peso) / soma(peso)   — so dos avaliados
 * </pre>
 */
@Component
public class CalculadoraDeScore {

    private static final int NOTA_MAXIMA = 100;

    private final PropriedadesAccessAi.Score configuracao;

    public CalculadoraDeScore(PropriedadesAccessAi propriedades) {
        this.configuracao = propriedades.score();
    }

    /**
     * @param problemas problemas persistidos da analise
     * @param avaliados principios com ao menos uma regra implementada; vem do
     *                  motor de regras, nao de uma lista escrita a mao
     */
    public ScoreDaAnalise calcular(List<Problema> problemas, Set<PrincipioWcag> avaliados) {
        if (avaliados.isEmpty()) {
            // Nenhuma regra ativa: nao ha o que afirmar sobre o documento.
            return ScoreDaAnalise.naoCalculado();
        }

        Map<PrincipioWcag, Integer> penalidadePorPrincipio = new EnumMap<>(PrincipioWcag.class);
        Map<PrincipioWcag, Integer> contagemPorPrincipio = new EnumMap<>(PrincipioWcag.class);
        for (Problema problema : problemas) {
            PrincipioWcag principio = PrincipioWcag.doCriterio(problema.getCriterioWcag());
            penalidadePorPrincipio.merge(principio, penalidadeDe(problema.getSeveridade()),
                    Integer::sum);
            contagemPorPrincipio.merge(principio, 1, Integer::sum);
        }

        List<ScorePorPrincipio> categorias = new ArrayList<>();
        long somaPonderada = 0;
        long somaDosPesos = 0;

        for (PrincipioWcag principio : PrincipioWcag.values()) {
            if (!avaliados.contains(principio)) {
                continue;
            }
            int penalidade = penalidadePorPrincipio.getOrDefault(principio, 0);
            int nota = Math.max(0, NOTA_MAXIMA - penalidade);
            int peso = pesoDe(principio);

            categorias.add(new ScorePorPrincipio(principio, principio.titulo(), nota, peso,
                    contagemPorPrincipio.getOrDefault(principio, 0), penalidade));
            somaPonderada += (long) nota * peso;
            somaDosPesos += peso;
        }

        List<PrincipioWcag> naoAvaliados = new ArrayList<>();
        for (PrincipioWcag principio : PrincipioWcag.values()) {
            if (!avaliados.contains(principio)) {
                naoAvaliados.add(principio);
            }
        }

        if (somaDosPesos == 0) {
            // Todo peso configurado como zero: media sem denominador. Configuracao
            // invalida, e melhor nao pontuar do que dividir por zero.
            return new ScoreDaAnalise(null, categorias, naoAvaliados);
        }
        int global = Math.toIntExact(Math.round((double) somaPonderada / somaDosPesos));
        return new ScoreDaAnalise(global, categorias, naoAvaliados);
    }

    private int penalidadeDe(Problema.Severidade severidade) {
        PropriedadesAccessAi.Score.Penalidades p = configuracao.penalidades();
        return switch (severidade) {
            case CRITICA -> p.critica();
            case ALTA -> p.alta();
            case MEDIA -> p.media();
            case BAIXA -> p.baixa();
        };
    }

    private int pesoDe(PrincipioWcag principio) {
        PropriedadesAccessAi.Score.Pesos p = configuracao.pesos();
        return switch (principio) {
            case PERCEPTIVEL -> p.perceptivel();
            case OPERAVEL -> p.operavel();
            case COMPREENSIVEL -> p.compreensivel();
            case ROBUSTO -> p.robusto();
        };
    }
}
