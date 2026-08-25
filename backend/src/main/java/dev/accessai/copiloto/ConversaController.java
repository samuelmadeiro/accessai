package dev.accessai.copiloto;

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
 * O copiloto conversacional de uma analise.
 *
 * <p>Controller nao tem regra de negocio (CONTRIBUTING.md secao 5). O guardrail,
 * o teto de gasto, o recorte de historico e o isolamento moram no servico e no
 * gateway.
 */
@RestController
@RequestMapping("/analyses/{id}/chat")
public class ConversaController {

    private final ServicoDeConversa servico;

    public ConversaController(ServicoDeConversa servico) {
        this.servico = servico;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public @NonNull TurnoVisto perguntar(@PathVariable("id") UUID analiseId,
                                         @RequestBody PedidoDeTurno pedido) {
        String pergunta = pedido == null ? null : pedido.pergunta();
        return TurnoVisto.de(servico.responder(analiseId, UsuarioAutenticado.id(), pergunta));
    }

    @GetMapping
    public @NonNull RespostaDeHistorico historico(@PathVariable("id") UUID analiseId) {
        return new RespostaDeHistorico(
                servico.historico(analiseId, UsuarioAutenticado.id()).stream()
                        .map(TurnoVisto::de)
                        .toList());
    }

    public record PedidoDeTurno(String pergunta) {
    }

    public record RespostaDeHistorico(List<TurnoVisto> turnos) {
    }

    /**
     * Uma fala, com a procedencia visivel (I5 do ADR 0012).
     *
     * <p>Ela viaja em toda resposta do assistente, e nao so no primeiro turno:
     * numa conversa longa, quem entra no meio precisa ver de onde aquela fala
     * especifica veio. `FIXTURE` significa que nenhum modelo foi consultado.
     */
    public record TurnoVisto(String papel, String texto, String procedencia, String modelo,
                             java.time.Instant criadoEm) {

        static @NonNull TurnoVisto de(ServicoDeConversa.@NonNull Turno t) {
            return new TurnoVisto(t.papel(), t.texto(), t.procedencia(), t.modelo(),
                    t.criadoEm());
        }
    }
}
