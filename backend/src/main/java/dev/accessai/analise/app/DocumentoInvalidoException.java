package dev.accessai.analise.app;

/** Conteudo enviado nao e um DOCX utilizavel. Vira HTTP 422 na borda. */
public class DocumentoInvalidoException extends RuntimeException {

    public DocumentoInvalidoException(String mensagem) {
        super(mensagem);
    }

    public DocumentoInvalidoException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
