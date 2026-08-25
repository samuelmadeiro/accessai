package dev.accessai.autenticacao;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * O dono da requisicao atual, extraido do JWT.
 *
 * <p>Existe para que nenhum caso de uso precise conhecer `Jwt`, `Authentication`
 * ou o nome da claim. Se um dia o `sub` deixar de ser o id do usuario, muda
 * aqui e em nenhum outro lugar.
 */
public final class UsuarioAutenticado {

    private UsuarioAutenticado() {
    }

    /**
     * @throws IllegalStateException quando nao ha usuario autenticado. Nao e
     *     defensividade: a cadeia de seguranca ja recusa a requisicao antes de
     *     chegar aqui, entao cair neste ponto significa que alguem liberou uma
     *     rota sem perceber — e falhar alto e melhor que atribuir o recurso a um
     *     dono inventado.
     */
    public static @NonNull UUID id() {
        Authentication autenticacao = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacao == null || !(autenticacao.getPrincipal() instanceof Jwt token)) {
            throw new IllegalStateException(
                    "sem usuario autenticado no contexto: rota liberada por engano?");
        }
        return UUID.fromString(token.getSubject());
    }
}
