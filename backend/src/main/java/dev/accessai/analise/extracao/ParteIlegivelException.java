package dev.accessai.analise.extracao;

/**
 * Parte do pacote nao pode ser lida como XML.
 *
 * <p>Falha permanente: reprocessar o mesmo pacote da o mesmo erro. Quem trata
 * isso e a politica de falha do consumidor, que marca a analise como FALHOU em
 * vez de devolver a mensagem para o Kafka.
 */
public class ParteIlegivelException extends RuntimeException {

    public ParteIlegivelException(String parte, Throwable causa) {
        super("XML invalido na parte " + parte, causa);
    }
}
