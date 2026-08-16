package dev.accessai.analise.app;

import dev.accessai.analise.evento.AnaliseSolicitadaV1;
import dev.accessai.analise.extracao.ExtratorDeImagens;
import java.io.UncheckedIOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decide o que acontece quando uma analise falha.
 *
 * <p>O trabalho em si esta em {@link ExecucaoDaAnalise}, numa transacao. Aqui
 * fica a politica, e ela separa dois mundos:
 *
 * <ul>
 *   <li><b>Falha permanente</b> (o pacote nao abre, o binario sumiu, a linha
 *       nao existe): reprocessar produz o mesmo resultado. A analise vai para
 *       FALHOU, o evento e marcado como processado e a mensagem morre aqui.</li>
 *   <li><b>Falha transitoria</b> (banco fora do ar, qualquer coisa nao
 *       classificada): a excecao sobe para o Kafka tentar de novo. Marcar
 *       FALHOU aqui esconderia um problema de infraestrutura como se fosse
 *       defeito do documento do usuario.</li>
 * </ul>
 *
 * <p>Antes desta separacao qualquer excecao derrubava a transacao inteira e a
 * analise voltava para RECEBIDA — sem registro de falha, sem log de erro e sem
 * jeito de distinguir "ainda vai processar" de "morreu no meio".
 *
 * <p>Ainda nao ha retry com backoff nem DLT: isso e a Slice 3. O que existe
 * aqui e o minimo para que nenhuma analise fique presa em RECEBIDA em silencio.
 */
@Component
public class ProcessadorDeAnalise {

    private static final Logger log = LoggerFactory.getLogger(ProcessadorDeAnalise.class);

    private final ExecucaoDaAnalise execucao;
    private final RegistroDeFalha registroDeFalha;

    public ProcessadorDeAnalise(ExecucaoDaAnalise execucao, RegistroDeFalha registroDeFalha) {
        this.execucao = execucao;
        this.registroDeFalha = registroDeFalha;
    }

    public void processar(AnaliseSolicitadaV1 evento) {
        try {
            execucao.executar(evento);
        } catch (RuntimeException e) {
            if (!ehPermanente(e)) {
                log.error("falha transitoria ao processar analiseId={} correlationId={}; "
                                + "a mensagem sera reentregue",
                        evento.analiseId(), evento.correlationId(), e);
                throw e;
            }
            log.error("falha permanente ao processar analiseId={} correlationId={}; "
                            + "analise marcada como FALHOU",
                    evento.analiseId(), evento.correlationId(), e);
            registroDeFalha.registrar(evento);
        }
    }

    /**
     * Falha que reprocessar nao conserta.
     *
     * <p>Lista explicita, e nao "tudo que nao for erro de banco": classificar
     * como permanente por engano descarta trabalho que ainda podia dar certo.
     * Na duvida, transitoria — o custo de uma reentrega e menor que o de uma
     * analise marcada FALHOU sem motivo.
     */
    private static boolean ehPermanente(RuntimeException e) {
        return e instanceof ExtratorDeImagens.ParteIlegivelException
                || e instanceof UncheckedIOException
                || e instanceof BinarioAusenteException
                || e instanceof AnaliseNaoEncontradaException
                || e instanceof DocumentoInvalidoException;
    }
}
