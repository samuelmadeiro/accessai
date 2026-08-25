package dev.accessai.analise.api;

import dev.accessai.analise.app.AnaliseNaoEncontradaException;
import dev.accessai.analise.app.DocumentoInvalidoException;
import dev.accessai.autenticacao.LimitadorDeUpload;
import dev.accessai.ia.ContadorDeGastoDeIa;
import dev.accessai.ia.GuardrailDeFundamentacao;
import dev.accessai.ia.ServicoDeRecomendacoes;
import dev.accessai.autenticacao.ServicoDeAutenticacao;
import dev.accessai.config.PropriedadesAccessAi;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Traduz excecao de dominio para status HTTP, num corpo de erro unico. */
@RestControllerAdvice
public class TratadorDeErros {

    private static final Logger log = LoggerFactory.getLogger(TratadorDeErros.class);

    private final PropriedadesAccessAi propriedades;

    public TratadorDeErros(PropriedadesAccessAi propriedades) {
        this.propriedades = propriedades;
    }

    @ExceptionHandler(DocumentoInvalidoException.class)
    public @NonNull ResponseEntity<AnaliseDto.Erro> documentoInvalido(@NonNull DocumentoInvalidoException e) {
        // Nivel INFO: e erro do cliente, nao incidente do servidor.
        log.info("upload recusado: {}", e.getMessage());
        return ResponseEntity.unprocessableContent()
                .body(new AnaliseDto.Erro("DOCUMENTO_INVALIDO", e.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public @NonNull ResponseEntity<AnaliseDto.Erro> arquivoGrandeDemais(@NonNull MaxUploadSizeExceededException e) {
        log.info("upload recusado por tamanho: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(new AnaliseDto.Erro("ARQUIVO_GRANDE_DEMAIS",
                        "documento excede o tamanho maximo permitido de "
                                + propriedades.upload().tamanhoMaximo()));
    }

    @ExceptionHandler(AnaliseNaoEncontradaException.class)
    public @NonNull ResponseEntity<AnaliseDto.Erro> naoEncontrada(@NonNull AnaliseNaoEncontradaException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new AnaliseDto.Erro("ANALISE_NAO_ENCONTRADA", e.getMessage()));
    }

    @ExceptionHandler(ServicoDeAutenticacao.CredenciaisInvalidasException.class)
    public @NonNull ResponseEntity<AnaliseDto.Erro> credenciaisInvalidas(
            ServicoDeAutenticacao.CredenciaisInvalidasException e) {
        // Sem log do email tentado: virar registro de "quem tentou entrar" e
        // dado pessoal acumulado sem necessidade.
        log.info("login recusado");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new AnaliseDto.Erro("CREDENCIAIS_INVALIDAS", e.getMessage()));
    }

    @ExceptionHandler(ServicoDeAutenticacao.EmailEmUsoException.class)
    public @NonNull ResponseEntity<AnaliseDto.Erro> emailEmUso(
            ServicoDeAutenticacao.EmailEmUsoException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new AnaliseDto.Erro("EMAIL_EM_USO", e.getMessage()));
    }

    @ExceptionHandler(ServicoDeAutenticacao.SenhaFracaException.class)
    public @NonNull ResponseEntity<AnaliseDto.Erro> senhaFraca(
            ServicoDeAutenticacao.SenhaFracaException e) {
        return ResponseEntity.unprocessableContent()
                .body(new AnaliseDto.Erro("CADASTRO_INVALIDO", e.getMessage()));
    }

    @ExceptionHandler(LimitadorDeUpload.LimiteDeUploadExcedidoException.class)
    public @NonNull ResponseEntity<AnaliseDto.Erro> limiteExcedido(
            LimitadorDeUpload.LimiteDeUploadExcedidoException e) {
        log.info("upload recusado por rate limit");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                // `Retry-After` em segundos: sem ele, um cliente automatizado so
                // pode adivinhar quando tentar de novo — e adivinhar significa
                // repetir imediatamente, que e o que piora a situacao.
                .header("Retry-After", String.valueOf(e.esperarSegundos()))
                .body(new AnaliseDto.Erro("LIMITE_DE_UPLOAD_EXCEDIDO", e.getMessage()));
    }

    @ExceptionHandler(GuardrailDeFundamentacao.SemFundamentoException.class)
    public @NonNull ResponseEntity<AnaliseDto.Erro> semFundamento(
            GuardrailDeFundamentacao.SemFundamentoException e) {
        // 422 e nao 400: o pedido esta bem formado, o que falta e base na
        // analise para responde-lo.
        log.info("recomendacao recusada pelo guardrail: {}", e.getMessage());
        return ResponseEntity.unprocessableContent()
                .body(new AnaliseDto.Erro("SEM_FUNDAMENTO_NA_ANALISE", e.getMessage()));
    }

    @ExceptionHandler(ContadorDeGastoDeIa.OrcamentoEsgotadoException.class)
    public @NonNull ResponseEntity<AnaliseDto.Erro> orcamentoEsgotado(
            ContadorDeGastoDeIa.OrcamentoEsgotadoException e) {
        // 402 diz exatamente o que aconteceu: nao ha orcamento. A analise em si
        // continua completa — IA e enriquecimento opcional (§2).
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                .body(new AnaliseDto.Erro("AI_BUDGET_EXHAUSTED", e.getMessage()));
    }

    @ExceptionHandler(ServicoDeRecomendacoes.AnaliseNaoConcluidaException.class)
    public @NonNull ResponseEntity<AnaliseDto.Erro> analiseNaoConcluida(
            ServicoDeRecomendacoes.AnaliseNaoConcluidaException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new AnaliseDto.Erro("ANALISE_NAO_CONCLUIDA", e.getMessage()));
    }

    @ExceptionHandler(ContadorDeGastoDeIa.ContadorIndisponivelException.class)
    public @NonNull ResponseEntity<AnaliseDto.Erro> contadorIndisponivel(
            ContadorDeGastoDeIa.ContadorIndisponivelException e) {
        // 503 e nao 402: o orcamento pode estar intacto — o que falta e como
        // conferi-lo. Temporario, e o cliente pode tentar de novo.
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new AnaliseDto.Erro("ORCAMENTO_NAO_VERIFICAVEL", e.getMessage()));
    }
}
