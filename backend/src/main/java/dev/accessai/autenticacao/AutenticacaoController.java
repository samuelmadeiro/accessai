package dev.accessai.autenticacao;

import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cadastro e login.
 *
 * <p>Controller nao tem regra de negocio (CONTRIBUTING.md secao 5): ele converte
 * corpo em chamada de servico e servico em resposta. Quem decide se a senha
 * serve, se o email ja existe ou se o login vale e o
 * {@link ServicoDeAutenticacao}.
 */
@RestController
@RequestMapping("/auth")
public class AutenticacaoController {

    private final ServicoDeAutenticacao servico;

    public AutenticacaoController(ServicoDeAutenticacao servico) {
        this.servico = servico;
    }

    @PostMapping("/registrar")
    @ResponseStatus(HttpStatus.CREATED)
    public @NonNull RespostaDeToken registrar(@RequestBody PedidoDeConta pedido) {
        return new RespostaDeToken(servico.registrar(pedido.email(), pedido.senha()));
    }

    @PostMapping("/login")
    public @NonNull RespostaDeToken login(@RequestBody PedidoDeConta pedido) {
        return new RespostaDeToken(servico.autenticar(pedido.email(), pedido.senha()));
    }

    /**
     * Um record, nunca a entidade: `Usuario` carrega hash de senha.
     *
     * <p>Sem `@NotBlank`/`@Email`: a validacao mora no
     * {@link ServicoDeAutenticacao}, que e quem sabe o que e uma senha aceitavel
     * e o que fazer com email repetido. Anotar aqui tambem criaria duas regras
     * para a mesma coisa, e a do Bean Validation venceria sem que a mensagem do
     * servico chegasse a rodar.
     */
    public record PedidoDeConta(String email, String senha) {
    }

    /**
     * So o token.
     *
     * <p>Sem `expiresIn` e sem dado do usuario: o proprio JWT carrega `exp`, e
     * repetir a validade fora dele cria duas fontes de verdade que divergem no
     * dia em que uma mudar.
     */
    public record RespostaDeToken(String token) {
    }
}
