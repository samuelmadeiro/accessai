package dev.accessai.analise.app;

import dev.accessai.analise.dominio.Analise;
import dev.accessai.analise.dominio.AnaliseRepository;
import dev.accessai.analise.dominio.Problema;
import dev.accessai.analise.dominio.ProblemaRepository;
import dev.accessai.analise.dominio.SituacaoAnalise;
import dev.accessai.analise.evento.AnaliseSolicitadaV1;
import dev.accessai.analise.evento.ProdutorDeAnalise;
import dev.accessai.analise.regras.MotorDeRegras;
import dev.accessai.analise.score.CalculadoraDeScore;
import dev.accessai.analise.score.ScoreDaAnalise;
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
 * <p>Servico nao tem SQL e controller nao tem regra de negocio (CLAUDE.md
 * secao 5). O que este servico decide: se o conteudo e aceitavel, qual o
 * correlationId da jornada e quando o evento pode ser publicado.
 */
@Service
public class ServicoDeAnalise {

    private final AnaliseRepository analiseRepository;
    private final ProblemaRepository problemaRepository;
    private final RegistroDeAnalise registro;
    private final ValidadorDeDocx validador;
    private final ProdutorDeAnalise produtor;
    private final CalculadoraDeScore calculadora;
    private final MotorDeRegras motor;
    private final Clock clock;

    public ServicoDeAnalise(AnaliseRepository analiseRepository,
                            ProblemaRepository problemaRepository,
                            RegistroDeAnalise registro,
                            ValidadorDeDocx validador,
                            ProdutorDeAnalise produtor,
                            CalculadoraDeScore calculadora,
                            MotorDeRegras motor,
                            Clock clock) {
        this.analiseRepository = analiseRepository;
        this.problemaRepository = problemaRepository;
        this.registro = registro;
        this.validador = validador;
        this.produtor = produtor;
        this.calculadora = calculadora;
        this.motor = motor;
        this.clock = clock;
    }

    /**
     * Recebe o documento, persiste e publica o evento.
     *
     * <p>A publicacao acontece DEPOIS do commit, de proposito. Publicar dentro
     * da transacao permite que o consumidor leia o evento antes de a linha
     * existir e nao encontre o binario.
     *
     * <p>Falha conhecida e aceita nesta slice: se o processo morrer entre o
     * commit e a publicacao, a analise fica em RECEBIDA para sempre. A solucao
     * e o padrao outbox, que entra na Slice 3 junto com retry e DLT.
     */
    public ResultadoDoRecebimento receber(byte[] conteudo, String nomeArquivo) {
        String tipoDetectado = validador.detectarTipo(conteudo);
        String sha256 = calcularSha256(conteudo);
        UUID correlationId = UUID.randomUUID();
        Instant agora = clock.instant();

        Analise analise = registro.registrar(conteudo, nomeArquivo, tipoDetectado, sha256,
                correlationId, agora);

        produtor.publicar(AnaliseSolicitadaV1.de(
                correlationId, analise.getId(), nomeArquivo, sha256, agora));

        return new ResultadoDoRecebimento(analise.getId(), correlationId, analise.getSituacao());
    }

    /**
     * Le a analise e ja a converte para {@link VisaoDaAnalise}, ainda dentro da
     * transacao. Nenhuma entidade sai deste metodo (CLAUDE.md secao 5).
     *
     * <p>O score e calculado aqui, na leitura, e nao gravado no banco: ele e
     * funcao pura dos problemas persistidos mais a configuracao de pesos.
     * Persisti-lo criaria uma copia que diverge no dia em que um peso mudar.
     * A contrapartida esta declarada no README: mudar peso muda a nota de
     * analises antigas.
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

        return VisaoDaAnalise.de(analise, problemas, score);
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
