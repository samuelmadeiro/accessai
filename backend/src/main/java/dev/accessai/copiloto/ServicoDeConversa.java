package dev.accessai.copiloto;

import dev.accessai.analise.app.AnaliseNaoEncontradaException;
import dev.accessai.analise.app.ServicoDeAnalise;
import dev.accessai.analise.app.VisaoDaAnalise;
import dev.accessai.analise.dominio.SituacaoAnalise;
import dev.accessai.ia.AiProvider;
import dev.accessai.ia.GatewayDeIa;
import dev.accessai.ia.RespostaDeConversa;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * O copiloto: conversa SOBRE a analise, com historico (Slice 7, ADR 0012).
 *
 * <p><b>O contexto e {@link VisaoDaAnalise}, e essa e a decisao inteira.</b> Nao
 * ha aqui nenhum caminho ate o `.docx`, ate o extrator ou ate o pacote OOXML —
 * e {@code ArquiteturaDaIaTest} falha se alguem abrir um. Sem o documento, o
 * copiloto nao tem como opinar sobre o que a regra nao mediu: a impossibilidade
 * e estrutural, e nao uma instrucao no prompt que o modelo pode ignorar.
 *
 * <p>Ele tambem nao produz achado. Nao cria {@code Problema}, nao mexe no score,
 * nao introduz criterio. O que ele acrescenta ao sistema e uma leitura do que o
 * motor deterministico ja decidiu.
 */
@Service
public class ServicoDeConversa {

    private final ServicoDeAnalise analises;
    private final TurnoDeConversaRepository repositorio;
    private final GatewayDeIa gateway;
    private final Clock clock;
    private final int turnosDeContexto;

    public ServicoDeConversa(ServicoDeAnalise analises, TurnoDeConversaRepository repositorio,
                             GatewayDeIa gateway, Clock clock,
                             @Value("${accessai.copiloto.turnos-de-contexto}")
                             int turnosDeContexto) {
        this.analises = analises;
        this.repositorio = repositorio;
        this.gateway = gateway;
        this.clock = clock;
        this.turnosDeContexto = turnosDeContexto;
    }

    /**
     * Responde a um turno e grava as duas falas.
     *
     * <p><b>Transacional de proposito.</b> Se o guardrail recusar a resposta, a
     * transacao inteira volta atras e NADA fica gravado — nem a pergunta. Uma
     * conversa que registra perguntas cujas respostas nunca existiram produz um
     * historico que nao aconteceu, e esse historico voltaria como contexto nos
     * turnos seguintes.
     *
     * @throws AnaliseNaoEncontradaException se a analise nao e deste usuario
     * @throws AnaliseNaoConversavelException se ela ainda nao terminou
     * @throws PerguntaVaziaException se nao ha o que perguntar
     */
    @Transactional
    public @NonNull Turno responder(UUID analiseId, UUID ownerId, String pergunta) {
        if (pergunta == null || pergunta.isBlank()) {
            throw new PerguntaVaziaException();
        }

        // Primeiro a analise, COM o ownerId. E a segunda condicao do ADR 0012:
        // nada nesta classe toca a tabela de turnos antes de o dono estar
        // confirmado, e o repositorio nao tem consulta que dispense isso.
        VisaoDaAnalise analise = analises.buscar(analiseId, ownerId);
        if (analise.situacao() != SituacaoAnalise.CONCLUIDA) {
            throw new AnaliseNaoConversavelException(analiseId, analise.situacao());
        }

        List<AiProvider.Turno> historico = historicoRecortado(analiseId);

        AiProvider.Fundamento fundamento = new AiProvider.Fundamento(
                analiseId,
                analise.problemas().stream()
                        .map(p -> new AiProvider.Fundamento.Achado(
                                p.regraId(), p.criterioWcag(),
                                p.severidade().name(), p.evidencia()))
                        .toList(),
                pergunta);

        RespostaDeConversa resposta = gateway.conversar(fundamento, historico);

        java.time.Instant agora = clock.instant();
        // A pergunta e gravada COMO O USUARIO ESCREVEU. A sanitizacao existe
        // para a fronteira do prompt, e acontece em AiProvider.Turno; aplica-la
        // tambem no banco faria o usuario reler a propria pergunta alterada.
        repositorio.save(TurnoDeConversa.doUsuario(analiseId, pergunta, agora));
        TurnoDeConversa gravado = repositorio.save(TurnoDeConversa.doAssistente(
                analiseId, resposta.textoOuVazio(), resposta.procedencia(),
                resposta.modelo(), agora));

        return Turno.de(gravado);
    }

    /** O historico gravado. Nunca chama a IA. */
    @Transactional(readOnly = true)
    public @NonNull List<Turno> historico(UUID analiseId, UUID ownerId) {
        analises.buscar(analiseId, ownerId);
        return repositorio.findByAnaliseIdOrderByCriadoEmAsc(analiseId).stream()
                .map(Turno::de)
                .toList();
    }

    /**
     * Os ultimos turnos, e nao a conversa inteira.
     *
     * <p>Recorte de ENVIO, e nao de retencao (ADR 0013): o historico completo
     * continua gravado e continua sendo devolvido na leitura. O que e limitado e
     * quanto volta para o prompt — com provider pago, cada turno reenviaria a
     * conversa inteira, e o custo cresceria com o quadrado do numero de turnos.
     */
    private List<AiProvider.Turno> historicoRecortado(UUID analiseId) {
        List<TurnoDeConversa> gravados = repositorio.findByAnaliseIdOrderByCriadoEmAsc(analiseId);
        int desde = Math.max(0, gravados.size() - turnosDeContexto);
        return gravados.subList(desde, gravados.size()).stream()
                .map(t -> new AiProvider.Turno(
                        AiProvider.Turno.Papel.valueOf(t.getPapel()), t.getTexto()))
                .toList();
    }

    /**
     * Uma fala, ja fora da entidade.
     *
     * @param procedencia FIXTURE ou MODELO na fala do assistente; nulo na do
     *     usuario, que nao veio de provider nenhum
     */
    public record Turno(String papel, String texto, String procedencia, String modelo,
                        java.time.Instant criadoEm) {

        static @NonNull Turno de(@NonNull TurnoDeConversa t) {
            return new Turno(t.getPapel(), t.getTexto(), t.getProcedencia(), t.getModelo(),
                    t.getCriadoEm());
        }
    }

    /** Conversar sobre analise em andamento seria conversar sobre lista incompleta. */
    public static class AnaliseNaoConversavelException extends RuntimeException {
        public AnaliseNaoConversavelException(UUID analiseId, SituacaoAnalise situacao) {
            super("a analise " + analiseId + " esta " + situacao
                    + ": o copiloto so conversa sobre analise concluida");
        }
    }

    /** Turno sem pergunta. */
    public static class PerguntaVaziaException extends RuntimeException {
        public PerguntaVaziaException() {
            super("informe a pergunta deste turno");
        }
    }
}
