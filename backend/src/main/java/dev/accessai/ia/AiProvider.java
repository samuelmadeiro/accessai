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
     * Responde a um turno de conversa sobre a analise (Slice 7, ADR 0012).
     *
     * <p><b>Isto ESTENDE a porta unica; nao abre uma segunda.</b> O copiloto
     * podia ter ganhado a sua propria interface — e teria, se a fronteira do §5
     * fosse "cada caso de uso fala com o seu modelo". Ela nao e: existe uma
     * porta, e trocar de provider, medir custo e testar sem rede continuam sendo
     * a mesma mudanca num lugar so.
     *
     * <p>O {@code fundamento} continua sendo o mesmo tipo do caminho de
     * recomendacao, e pelo mesmo motivo: e o que impede qualquer chamador de
     * mandar texto livre ao modelo. O que muda e o {@code historico}, que a
     * chamada unica nao tinha.
     *
     * @param fundamento os achados reais da analise, ja sanitizados
     * @param historico  turnos anteriores, do mais antigo para o mais novo, ja
     *     recortados pelo gateway. Vem sanitizado por {@link Turno}
     * @param prompt     montado por {@link MontadorDePrompt}, como no
     *     {@code recomendar}: nenhum provider monta o seu proprio
     */
    @NonNull RespostaDeConversa conversar(@NonNull Fundamento fundamento,
                                          @NonNull List<Turno> historico,
                                          @NonNull String prompt);

    /**
     * Um turno ja dito, de qualquer um dos dois lados.
     *
     * <p>Sanitizado no construtor compacto, como {@link Fundamento} — e pela
     * mesma razao. Em multi-turno isso vale MAIS, e nao menos: o texto do
     * usuario volta ao prompt a cada turno seguinte, entao uma injecao que
     * passasse uma vez seria reenviada em todas as chamadas seguintes da
     * conversa.
     *
     * <p>A fala do assistente tambem passa pelo filtro. Ela e nossa saida, mas
     * quando o provider for generativo ela e, na origem, texto de modelo — e
     * modelo induzido por injecao pode repetir o que recebeu.
     */
    record Turno(Papel papel, String texto) {

        public enum Papel {
            USUARIO,
            ASSISTENTE
        }

        public Turno {
            texto = ConteudoNaoConfiavel.sanitizar(texto);
        }
    }

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
