package dev.accessai.apoio;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Segredo de JWT para os testes de integracao, gerado a cada execucao.
 *
 * <p>Gerado, e nao escrito num `application.yml` de teste, porque o
 * CONTRIBUTING.md secao 5 diz "zero hardcode, INCLUSIVE em teste". Segredo
 * literal em arquivo de teste e segredo publicado — e o proximo passo previsivel
 * e alguem copiar para o ambiente real "so para subir rapido".
 *
 * <p>Constante por JVM para que o token emitido num ponto do teste continue
 * valido no ponto seguinte.
 */
public final class SegredoDeTeste {

    private static final String VALOR = gerar();

    private SegredoDeTeste() {
    }

    public static String valor() {
        return VALOR;
    }

    private static String gerar() {
        byte[] bytes = new byte[48];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
