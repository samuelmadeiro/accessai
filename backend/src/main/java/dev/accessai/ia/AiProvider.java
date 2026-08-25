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
    /**
     * @param fundamento os achados reais, ja sanitizados
     * @param prompt     o prompt montado por {@link MontadorDePrompt}; um
     *     provider real manda ISTO ao modelo, e nao monta o seu proprio — a
     *     montagem e onde o conteudo nao confiavel encosta na instrucao, e ela
     *     mora num lugar so
     */
    @NonNull RespostaDeIa recomendar(@NonNull Fundamento fundamento, @NonNull String prompt);

    /**
     * O material da analise que a IA pode usar — e nada alem dele.
     *
     * @param analiseId  para o registro
     * @param achados    o que o Rule Engine encontrou, ja resumido
     * @param pergunta   pergunta opcional de quem pediu; pode ser nula
     */
    record Fundamento(java.util.UUID analiseId, List<Achado> achados, String pergunta) {

        /**
         * A sanitizacao acontece AQUI, no construtor compacto, e nao em quem
         * monta o prompt.
         *
         * <p>E a diferenca entre garantia e convencao. Se cada provider tivesse
         * que lembrar de sanitizar, o provider novo — escrito daqui a seis meses
         * por alguem com pressa — seria o que esquece. Como nenhum
         * {@code Fundamento} pode existir com texto bruto dentro, todo provider
         * recebe conteudo tratado por construcao.
         *
         * <p>Vale para a EVIDENCIA (que veio do `.docx` de terceiro) e para a
         * PERGUNTA (que veio do cliente). As duas sao hostis, pelo mesmo motivo:
         * nenhuma delas foi escrita pelo dono do sistema.
         */
        public Fundamento {
            achados = achados.stream().map(Achado::sanitizado).toList();
            pergunta = ConteudoNaoConfiavel.sanitizar(pergunta);
        }

        /** Um problema real, reduzido ao que a IA precisa ver. */
        public record Achado(String regraId, String criterioWcag, String severidade,
                             String evidencia) {

            /**
             * So a evidencia e sanitizada. {@code regraId}, {@code criterioWcag}
             * e {@code severidade} nao vem de fora: sao produzidos pelo Rule
             * Engine a partir da tabela versionada, e passa-los pelo mesmo
             * filtro esconderia um defeito nosso em vez de um ataque de fora.
             */
            Achado sanitizado() {
                return new Achado(regraId, criterioWcag, severidade,
                        ConteudoNaoConfiavel.sanitizar(evidencia));
            }
        }

        public boolean temAchados() {
            return !achados.isEmpty();
        }
    }
}
