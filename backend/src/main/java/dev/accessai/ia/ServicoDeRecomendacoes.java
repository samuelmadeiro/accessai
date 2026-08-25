package dev.accessai.ia;

import dev.accessai.analise.app.AnaliseNaoEncontradaException;
import dev.accessai.analise.app.ServicoDeAnalise;
import dev.accessai.analise.app.VisaoDaAnalise;
import dev.accessai.analise.dominio.SituacaoAnalise;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gera e le recomendacoes de uma analise.
 *
 * <p>O fundamento vem SEMPRE do que o Rule Engine encontrou. Nao existe caminho
 * neste servico que mande texto livre para o gateway: {@link AiProvider.Fundamento}
 * so aceita achados, e e por isso que "recomendacao fundamentada na analise" e
 * um contrato de tipo em vez de uma promessa no README.
 */
@Service
public class ServicoDeRecomendacoes {

    private final ServicoDeAnalise analises;
    private final RecomendacaoRepository repositorio;
    private final GatewayDeIa gateway;
    private final Clock clock;

    public ServicoDeRecomendacoes(ServicoDeAnalise analises,
                                  RecomendacaoRepository repositorio,
                                  GatewayDeIa gateway, Clock clock) {
        this.analises = analises;
        this.repositorio = repositorio;
        this.gateway = gateway;
        this.clock = clock;
    }

    /**
     * Gera as recomendacoes da analise, uma vez.
     *
     * <p><b>Idempotente por analise.</b> Chamar de novo devolve o que ja existe
     * em vez de gerar outra vez: com provider pago, regenerar cobraria duas
     * vezes pelo mesmo documento, e com qualquer provider generativo o texto
     * mudaria entre as chamadas — a mesma analise responderia coisas diferentes.
     *
     * @throws AnaliseNaoEncontradaException se a analise nao e do usuario
     * @throws AnaliseNaoConcluidaException se ela ainda nao terminou
     * @throws GuardrailDeFundamentacao.SemFundamentoException se nao ha base
     */
    @Transactional
    public @NonNull List<Recomendacao> gerar(UUID analiseId, UUID ownerId, String pergunta) {
        VisaoDaAnalise analise = analises.buscar(analiseId, ownerId);
        if (analise.situacao() != SituacaoAnalise.CONCLUIDA) {
            // Recomendar sobre analise em andamento seria recomendar sobre uma
            // lista de problemas que ainda vai crescer.
            throw new AnaliseNaoConcluidaException(analiseId, analise.situacao());
        }

        List<Recomendacao> existentes = repositorio.findByAnaliseIdOrderByCriadaEmAsc(analiseId);
        if (!existentes.isEmpty() && (pergunta == null || pergunta.isBlank())) {
            return existentes;
        }

        AiProvider.Fundamento fundamento = new AiProvider.Fundamento(
                analiseId,
                analise.problemas().stream()
                        .map(p -> new AiProvider.Fundamento.Achado(
                                p.regraId(), p.criterioWcag(),
                                p.severidade().name(), p.evidencia()))
                        .toList(),
                pergunta);

        RespostaDeIa resposta = gateway.recomendar(fundamento);

        // Pergunta nao gera linha nova no banco: ela e uma consulta sobre a
        // analise, nao um segundo conjunto de recomendacoes dela.
        if (pergunta != null && !pergunta.isBlank()) {
            return resposta.recomendacoes().stream()
                    .map(r -> Recomendacao.de(analiseId, r, resposta.procedencia(),
                            resposta.modelo(), clock.instant()))
                    .toList();
        }
        return repositorio.saveAll(resposta.recomendacoes().stream()
                .map(r -> Recomendacao.de(analiseId, r, resposta.procedencia(),
                        resposta.modelo(), clock.instant()))
                .toList());
    }

    /** As recomendacoes ja gravadas. Nunca chama a IA. */
    @Transactional(readOnly = true)
    public @NonNull List<Recomendacao> listar(UUID analiseId, UUID ownerId) {
        // Passa pelo servico de analise de proposito: e ele que aplica o
        // isolamento por dono. Ler direto do repositorio de recomendacao
        // devolveria a recomendacao de outra pessoa.
        analises.buscar(analiseId, ownerId);
        return repositorio.findByAnaliseIdOrderByCriadaEmAsc(analiseId);
    }

    public static class AnaliseNaoConcluidaException extends RuntimeException {
        public AnaliseNaoConcluidaException(UUID analiseId, SituacaoAnalise situacao) {
            super("analise " + analiseId + " esta em " + situacao
                    + ": recomendacao so depois de concluida");
        }
    }
}
