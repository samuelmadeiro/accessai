package dev.accessai.apoio;

import java.util.Map;
import java.util.UUID;
import org.springframework.web.client.RestClient;

/**
 * Cria uma conta pela API e devolve o token dela.
 *
 * <p>Pela API, e nao gravando direto no repositorio: assim o proprio caminho de
 * cadastro entra no teste de integracao. Um atalho que insere a linha na mao
 * deixaria `POST /auth/registrar` sem cobertura ponta a ponta e ainda esconderia
 * um defeito de hash de senha.
 */
public final class TokenDeTeste {

    /** Longa o bastante para passar no minimo do servico, sem ser adivinhavel. */
    private static final String SENHA = "frase-de-teste-longa-o-bastante";

    private TokenDeTeste() {
    }

    /** Uma conta nova a cada chamada: dois testes nunca compartilham dono. */
    public static String novaConta(int porta) {
        RestClient anonimo = RestClient.builder()
                .baseUrl("http://localhost:" + porta)
                .build();
        Map<?, ?> resposta = anonimo.post()
                .uri("/auth/registrar")
                .body(Map.of("email", UUID.randomUUID() + "@teste.invalid",
                        "senha", SENHA))
                .retrieve()
                .body(Map.class);
        if (resposta == null || resposta.get("token") == null) {
            throw new IllegalStateException("cadastro nao devolveu token: " + resposta);
        }
        return (String) resposta.get("token");
    }
}
