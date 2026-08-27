package dev.accessai.autenticacao;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Autenticacao JWT stateless (D4, ADR 0004).
 *
 * <p><b>Stateless de verdade:</b> nenhuma sessao em servidor, nenhum
 * {@code JSESSIONID}. O consumidor Kafka e o worker do outbox rodam fora de
 * qualquer requisicao, e sessao em servidor obrigaria a existir estado
 * compartilhado entre processos so para autenticar.
 *
 * <p><b>Segredo simetrico (HS256), nao par de chaves.</b> Quem emite e quem
 * valida o token e o MESMO servico. RS256 existe para o caso em que o validador
 * nao pode conhecer o segredo do emissor — nao e o caso aqui, e o par de chaves
 * custaria rotacao e distribuicao de chave publica para resolver um problema
 * que o projeto nao tem.
 *
 * <p><b>O segredo vem do ambiente, sempre</b> (CONTRIBUTING.md secao 5). Nao ha
 * valor padrao: sem {@code ACCESSAI_JWT_SECRET} a aplicacao NAO sobe. Um padrao
 * de desenvolvimento aqui seria um segredo publicado no GitHub que alguem usaria
 * em producao sem perceber.
 */
@Configuration
public class SegurancaConfig {

    /**
     * HS256 exige chave de pelo menos 256 bits. Abaixo disso o Nimbus recusa —
     * e recusar e o comportamento certo: segredo curto e forca bruta viavel.
     */
    static final int MINIMO_DE_BYTES_DO_SEGREDO = 32;

    private final SecretKey chave;

    public SegurancaConfig(@Value("${accessai.jwt.segredo}") String segredo) {
        byte[] bytes = segredo == null ? new byte[0] : segredo.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MINIMO_DE_BYTES_DO_SEGREDO) {
            throw new IllegalStateException(
                    "ACCESSAI_JWT_SECRET precisa de pelo menos "
                            + MINIMO_DE_BYTES_DO_SEGREDO + " bytes para HS256; veio com "
                            + bytes.length + ". Gere com: openssl rand -base64 48");
        }
        this.chave = new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    public SecurityFilterChain cadeia(@NonNull HttpSecurity http) throws Exception {
        return http
                // CSRF desligado porque nao ha cookie de sessao: o token viaja no
                // cabecalho Authorization, e cabecalho nao e enviado
                // automaticamente pelo navegador em requisicao de outro site —
                // que e exatamente o vetor que o CSRF protege.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(rotas -> rotas
                        .requestMatchers(HttpMethod.POST, "/auth/registrar", "/auth/login")
                        .permitAll()
                        // O health e consumido pela orquestracao do compose, que
                        // nao tem como se autenticar. `/actuator/**` inteiro NAO
                        // e liberado: metrics e env expoem configuracao.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // O frontend da Slice 8. Sao arquivos estaticos, sem
                        // dado de ninguem dentro: o que eles fazem e pedir o
                        // token e chamar a API, que continua exigindo
                        // autenticacao em toda rota de analise. Exigir token
                        // para baixar a propria tela de login seria circular.
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/analise.html",
                                "/estilo.css", "/app.js", "/entrada.js", "/resultado.js")
                        .permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(decoder())))
                // 401 puro em vez do redirecionamento para pagina de login, que e
                // o padrao e nao faz sentido numa API.
                .exceptionHandling(e -> e.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .build();
    }

    @Bean
    public JwtEncoder encoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(chave));
    }

    @Bean
    public JwtDecoder decoder() {
        return NimbusJwtDecoder.withSecretKey(chave)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    /**
     * BCrypt com custo padrao (10).
     *
     * <p>Nao e o algoritmo mais moderno — Argon2 e — mas e o que o Spring
     * Security traz sem dependencia extra, e a diferenca so importa contra um
     * atacante que ja tem o dump do banco. Trocar depois e trocar este bean: o
     * {@code PasswordEncoder} e a fronteira.
     */
    @Bean
    public PasswordEncoder codificadorDeSenha() {
        return new BCryptPasswordEncoder();
    }
}
