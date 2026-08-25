package dev.accessai.ia;

import dev.accessai.autenticacao.UsuarioAutenticado;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recomendacoes de uma analise.
 *
 * <p>Controller nao tem regra de negocio (CONTRIBUTING.md secao 5). O guardrail,
 * o teto de gasto e a idempotencia moram no gateway e no servico.
 */
@RestController
@RequestMapping("/analyses/{id}/recommendations")
public class RecomendacaoController {

    private final ServicoDeRecomendacoes servico;

    public RecomendacaoController(ServicoDeRecomendacoes servico) {
        this.servico = servico;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public @NonNull RespostaDeRecomendacoes gerar(@PathVariable("id") UUID analiseId,
                                                  @RequestBody(required = false)
                                                  PedidoDeRecomendacao pedido) {
        String pergunta = pedido == null ? null : pedido.pergunta();
        return RespostaDeRecomendacoes.de(
                servico.gerar(analiseId, UsuarioAutenticado.id(), pergunta));
    }

    @GetMapping
    public @NonNull RespostaDeRecomendacoes listar(@PathVariable("id") UUID analiseId) {
        return RespostaDeRecomendacoes.de(
                servico.listar(analiseId, UsuarioAutenticado.id()));
    }

    /** Pergunta opcional sobre a analise. Sem base no que foi medido, e recusada. */
    public record PedidoDeRecomendacao(String pergunta) {
    }

    /**
     * A resposta declara a procedencia, sempre.
     *
     * <p>`procedencia: "FIXTURE"` diz, em uma palavra, que nenhum modelo foi
     * consultado. E o mesmo papel de `usouHeuristica` no ML Service: sem ele, o
     * consumidor acreditaria em IA onde ha texto de fixture — o "nada pode ser
     * falso" do §1.
     */
    public record RespostaDeRecomendacoes(List<RecomendacaoVista> recomendacoes,
                                          String procedencia, String modelo) {

        static @NonNull RespostaDeRecomendacoes de(List<Recomendacao> gravadas) {
            if (gravadas.isEmpty()) {
                return new RespostaDeRecomendacoes(List.of(), null, null);
            }
            return new RespostaDeRecomendacoes(
                    gravadas.stream().map(RecomendacaoVista::de).toList(),
                    gravadas.getFirst().getProcedencia(),
                    gravadas.getFirst().getModelo());
        }
    }

    public record RecomendacaoVista(String regraId, String criterioWcag, String texto) {

        static @NonNull RecomendacaoVista de(@NonNull Recomendacao r) {
            return new RecomendacaoVista(r.getRegraId(), r.getCriterioWcag(), r.getTexto());
        }
    }
}
