package dev.accessai.ia;

import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * A UNICA porta de saida para um LLM (CONTRIBUTING.md secao 5).
 *
 * <p>"Chamadas a LLM passam exclusivamente pela interface {@code AiProvider}. Se
 * aparecer um {@code HttpClient} chamando um LLM fora do gateway, e bug." A
 * interface existe para que trocar de provider, medir custo e testar sem rede
 * sejam a mesma mudanca de um lugar so.
 *
 * <p><b>A implementacao diz quem ela e, sempre.</b> {@link #procedencia()} nao e
 * telemetria: e o que impede o produto de apresentar texto de fixture como saida
 * de modelo. O §1 proibe "nenhuma IA que e template string" — a proibicao so tem
 * como ser cumprida se a origem viajar junto com a resposta ate o usuario.
 */
public interface AiProvider {

    /** De onde a resposta veio, para o consumidor decidir o que fazer com ela. */
    enum Procedencia {
        /** Fixture local. Nao houve chamada a modelo nenhum. */
        FIXTURE,
        /** Modelo remoto de verdade. */
        MODELO
    }

    @NonNull Procedencia procedencia();

    /** Nome do modelo, ou o do fake. Vai para o registro e para a resposta da API. */
    @NonNull String modelo();

    /**
     * Gera recomendacoes a partir do que a analise ENCONTROU.
     *
     * <p>O parametro nao e um texto livre: e {@link Fundamento}, que carrega os
     * problemas reais. Aceitar `String` aqui deixaria qualquer chamador montar o
     * prompt do jeito dele, e a fundamentacao viraria convencao em vez de
     * contrato.
     */
    @NonNull RespostaDeIa recomendar(@NonNull Fundamento fundamento);

    /**
     * O material da analise que a IA pode usar — e nada alem dele.
     *
     * @param analiseId  para o registro
     * @param achados    o que o Rule Engine encontrou, ja resumido
     * @param pergunta   pergunta opcional de quem pediu; pode ser nula
     */
    record Fundamento(java.util.UUID analiseId, List<Achado> achados, String pergunta) {

        public Fundamento {
            achados = List.copyOf(achados);
        }

        /** Um problema real, reduzido ao que a IA precisa ver. */
        public record Achado(String regraId, String criterioWcag, String severidade,
                             String evidencia) {
        }

        public boolean temAchados() {
            return !achados.isEmpty();
        }
    }
}
