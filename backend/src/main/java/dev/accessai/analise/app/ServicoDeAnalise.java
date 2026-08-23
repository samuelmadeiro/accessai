package dev.accessai.analise.app;

import dev.accessai.analise.dominio.Analise;
import dev.accessai.analise.dominio.AnaliseRepository;
import dev.accessai.analise.dominio.PredicaoDeAlt;
import dev.accessai.analise.dominio.PredicaoDeAltRepository;
import dev.accessai.analise.dominio.Problema;
import dev.accessai.analise.dominio.ProblemaRepository;
import dev.accessai.analise.dominio.SituacaoAnalise;
import dev.accessai.analise.regras.MotorDeRegras;
import dev.accessai.analise.score.CalculadoraDeScore;
import dev.accessai.analise.score.ScoreDaAnalise;
import dev.accessai.correlacao.Correlacao;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orquestra o recebimento de um documento.
 *
 * <p>Servico nao tem SQL e controller nao tem regra de negocio (CONTRIBUTING.md
 * secao 5). O que este servico decide: se o conteudo e aceitavel, qual o
 * correlationId da jornada e o que vai para o outbox.
 */
@Service
public class ServicoDeAnalise {

    private final AnaliseRepository analiseRepository;
    private final ProblemaRepository problemaRepository;
    private final PredicaoDeAltRepository predicaoRepository;
    private final RegistroDeAnalise registro;
    private final ValidadorDeDocx validador;
    private final CalculadoraDeScore calculadora;
    private final MotorDeRegras motor;
    private final Clock clock;

    public ServicoDeAnalise(AnaliseRepository analiseRepository,
                            ProblemaRepository problemaRepository,
                            PredicaoDeAltRepository predicaoRepository,
                            RegistroDeAnalise registro,
                            ValidadorDeDocx validador,
                            CalculadoraDeScore calculadora,
                            MotorDeRegras motor,
                            Clock clock) {
        this.analiseRepository = analiseRepository;
        this.problemaRepository = problemaRepository;
        this.predicaoRepository = predicaoRepository;
        this.registro = registro;
        this.validador = validador;
        this.calculadora = calculadora;
        this.motor = motor;
        this.clock = clock;
    }

    /**
     * Recebe o documento e grava tudo — analise, binario e evento — numa
     * transacao so.
     *
     * <p>Nada e publicado aqui. A publicacao e trabalho do
     * {@code PublicadorDeOutbox}, que le a tabela depois do commit. Foi o que
     * eliminou a janela em que a analise existia no banco e o evento nao existia
     * em lugar nenhum.
     *
     * <p>O correlationId vem do MDC, preenchido pelo filtro HTTP a partir do
     * cabecalho {@code X-Correlation-ID} — ou gerado, quando o cliente nao
     * manda. Assim a jornada do cliente e a jornada interna sao a mesma.
     */
    public ResultadoDoRecebimento receber(byte[] conteudo, String nomeArquivo) {
        String tipoDetectado = validador.detectarTipo(conteudo);
        String sha256 = calcularSha256(conteudo);
        UUID correlationId = Correlacao.atualComoUuid();
        Instant agora = clock.instant();

        Analise analise = registro.registrar(conteudo, nomeArquivo, tipoDetectado, sha256,
                correlationId, agora);

        return new ResultadoDoRecebimento(analise.getId(), correlationId, analise.getSituacao());
    }

    /**
     * Le a analise e ja a converte para {@link VisaoDaAnalise}, ainda dentro da
     * transacao. Nenhuma entidade sai deste metodo (CONTRIBUTING.md secao 5).
     *
     * <p>O score e calculado aqui, na leitura, e nao gravado no banco: ele e
     * funcao pura dos problemas persistidos mais a configuracao de pesos.
     * Persisti-lo criaria uma copia que diverge no dia em que um peso mudar.
     *
     * <p>Analise que ainda nao concluiu nao recebe score. Pontuar um documento
     * que ninguem processou seria afirmar conformidade sem verificacao.
     */
    @Transactional(readOnly = true)
    public VisaoDaAnalise buscar(UUID analiseId) {
        Analise analise = analiseRepository.findById(analiseId)
                .orElseThrow(() -> new AnaliseNaoEncontradaException(analiseId));
        List<Problema> problemas = problemaRepository.findByAnaliseIdOrderByCriadoEmAsc(analiseId);

        ScoreDaAnalise score = analise.getSituacao() == SituacaoAnalise.CONCLUIDA
                ? calculadora.calcular(problemas, motor.principiosAvaliados())
                : ScoreDaAnalise.naoCalculado();

        // As predicoes sao LIDAS, nunca recalculadas aqui: chamar o ML Service
        // na leitura poria latencia de rede em todo GET e faria a mesma analise
        // responder coisas diferentes a cada consulta.
        List<PredicaoDeAlt> predicoes =
                predicaoRepository.findByAnaliseIdOrderByIndiceAsc(analiseId);

        return VisaoDaAnalise.de(analise, problemas, score, predicoes);//retornando a propria analise com os problemas e o score
    }

    private static String calcularSha256(byte[] conteudo) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(conteudo));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 e obrigatorio em toda JVM; se faltar, o ambiente esta quebrado.
            throw new IllegalStateException("SHA-256 indisponivel nesta JVM", e);
        }
    }

    /** Resultado do POST. Nao expoe entidade JPA. */
    public record ResultadoDoRecebimento(UUID analiseId, UUID correlationId,
                                         SituacaoAnalise situacao) {
    }
}
