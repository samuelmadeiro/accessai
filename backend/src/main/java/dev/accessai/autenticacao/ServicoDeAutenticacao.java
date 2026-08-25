package dev.accessai.autenticacao;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cadastro e login. Emite o JWT que o resto da API exige.
 *
 * <p>Service nao tem SQL e Controller nao tem regra (CONTRIBUTING.md secao 5):
 * a decisao de aceitar ou recusar um login mora aqui.
 */
@Service
public class ServicoDeAutenticacao {

    /**
     * Validade do token. Oito horas cobre um dia de trabalho sem renovacao.
     *
     * <p>Sem refresh token: rotacao de refresh foi cortada por over-engineering
     * na Fase 0 (D4). Token expirado significa autenticar de novo, o que num
     * projeto de um usuario e aceitavel e evita a parte mais delicada de
     * autenticacao — invalidacao de refresh.
     */
    static final Duration VALIDADE = Duration.ofHours(8);

    static final String EMISSOR = "accessai";
    static final int TAMANHO_MINIMO_DA_SENHA = 12;

    private final UsuarioRepository usuarios;
    private final PasswordEncoder codificador;
    private final JwtEncoder jwt;
    private final Clock clock;

    public ServicoDeAutenticacao(UsuarioRepository usuarios, PasswordEncoder codificador,
                                 JwtEncoder jwt, Clock clock) {
        this.usuarios = usuarios;
        this.codificador = codificador;
        this.jwt = jwt;
        this.clock = clock;
    }

    /**
     * Cria a conta e ja devolve o token.
     *
     * @throws EmailEmUsoException quando o email ja existe
     * @throws SenhaFracaException quando a senha nao alcanca o minimo
     */
    @Transactional
    public @NonNull String registrar(String email, String senha) {
        String normalizado = Usuario.normalizarEmail(email);
        if (normalizado.isEmpty()) {
            throw new SenhaFracaException("email obrigatorio");
        }
        // Comprimento e o unico criterio. Regra de "uma maiuscula, um simbolo"
        // empurra a pessoa para `Senha123!` — previsivel e curta — enquanto uma
        // frase longa e mais forte e mais facil de lembrar.
        if (senha == null || senha.length() < TAMANHO_MINIMO_DA_SENHA) {
            throw new SenhaFracaException(
                    "senha precisa de pelo menos " + TAMANHO_MINIMO_DA_SENHA + " caracteres");
        }
        if (usuarios.existsByEmail(normalizado)) {
            throw new EmailEmUsoException(normalizado);
        }
        Usuario usuario = usuarios.save(Usuario.criar(
                normalizado, codificador.encode(senha), clock.instant()));
        return emitir(usuario.getId());
    }

    /**
     * Autentica e devolve o token.
     *
     * <p>Email inexistente e senha errada levantam a MESMA excecao, com a mesma
     * mensagem. Distinguir as duas transforma o login num oraculo de quais
     * emails estao cadastrados.
     *
     * @throws CredenciaisInvalidasException sempre que nao autenticar
     */
    @Transactional(readOnly = true)
    public @NonNull String autenticar(String email, String senha) {
        String normalizado = Usuario.normalizarEmail(email);
        return usuarios.findByEmail(normalizado)
                .filter(u -> senha != null && codificador.matches(senha, u.getSenhaHash()))
                .map(u -> emitir(u.getId()))
                .orElseThrow(CredenciaisInvalidasException::new);
    }

    /**
     * O token carrega o id do usuario em {@code sub}, e mais nada.
     *
     * <p>Email dentro do token pareceria conveniente e seria dado pessoal
     * viajando em cada requisicao, gravado em log de proxy e em ferramenta de
     * rede. O id basta para o isolamento por linha, que e para o que ele serve.
     */
    private String emitir(UUID usuarioId) {
        Instant agora = clock.instant();
        JwtClaimsSet reivindicacoes = JwtClaimsSet.builder()
                .issuer(EMISSOR)
                .issuedAt(agora)
                .expiresAt(agora.plus(VALIDADE))
                .subject(usuarioId.toString())
                .build();
        // O cabecalho precisa declarar HS256 EXPLICITAMENTE. Sem ele o
        // NimbusJwtEncoder assume RS256, procura uma chave RSA no conjunto,
        // nao acha e falha com "Failed to select a JWK signing key" — erro que
        // aponta para chave ausente quando o problema e algoritmo errado.
        JwsHeader cabecalho = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwt.encode(JwtEncoderParameters.from(cabecalho, reivindicacoes))
                .getTokenValue();
    }

    public static class EmailEmUsoException extends RuntimeException {
        public EmailEmUsoException(String email) {
            super("ja existe conta para " + email);
        }
    }

    public static class SenhaFracaException extends RuntimeException {
        public SenhaFracaException(String motivo) {
            super(motivo);
        }
    }

    public static class CredenciaisInvalidasException extends RuntimeException {
        public CredenciaisInvalidasException() {
            super("email ou senha invalidos");
        }
    }
}
