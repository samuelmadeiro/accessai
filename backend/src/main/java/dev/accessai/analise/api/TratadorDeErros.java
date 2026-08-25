package dev.accessai.analise.api;

import dev.accessai.analise.app.AnaliseNaoEncontradaException;
import dev.accessai.analise.app.DocumentoInvalidoException;
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
}
