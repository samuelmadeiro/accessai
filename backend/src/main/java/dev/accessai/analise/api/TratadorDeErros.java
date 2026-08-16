package dev.accessai.analise.api;

import dev.accessai.analise.app.AnaliseNaoEncontradaException;
import dev.accessai.analise.app.DocumentoInvalidoException;
import dev.accessai.config.PropriedadesAccessAi;
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
    public ResponseEntity<AnaliseDto.Erro> documentoInvalido(DocumentoInvalidoException e) {
        // Nivel INFO: e erro do cliente, nao incidente do servidor.
        log.info("upload recusado: {}", e.getMessage());
        return ResponseEntity.unprocessableEntity()
                .body(new AnaliseDto.Erro("DOCUMENTO_INVALIDO", e.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<AnaliseDto.Erro> arquivoGrandeDemais(MaxUploadSizeExceededException e) {
        log.info("upload recusado por tamanho: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new AnaliseDto.Erro("ARQUIVO_GRANDE_DEMAIS",
                        "documento excede o tamanho maximo permitido de "
                                + propriedades.upload().tamanhoMaximo()));
    }

    @ExceptionHandler(AnaliseNaoEncontradaException.class)
    public ResponseEntity<AnaliseDto.Erro> naoEncontrada(AnaliseNaoEncontradaException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new AnaliseDto.Erro("ANALISE_NAO_ENCONTRADA", e.getMessage()));
    }
}
