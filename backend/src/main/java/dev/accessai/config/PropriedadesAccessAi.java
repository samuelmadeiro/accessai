package dev.accessai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Configuracao da aplicacao. Existe para que nao haja numero magico espalhado
 * pelo codigo (CONTRIBUTING.md secao 5): limite de upload, nome de topico, particoes e
 * os numeros do score sao decisoes de operacao, nao constantes de classe.
 */
@ConfigurationProperties(prefix = "accessai")
public record PropriedadesAccessAi(Upload upload, Kafka kafka, Score score) {

    public record Upload(DataSize tamanhoMaximo) {
    }

    public record Kafka(String topicoAnaliseSolicitada, int particoes, short replicas) {
    }

    /**
     * Numeros do score.
     *
     * <p>Ficam em configuracao, e nao no codigo, por um motivo que nao e
     * flexibilidade: eles sao <b>escolha</b>, nao medida. A WCAG nao pontua nada
     * e nao hierarquiza principios. Deixa-los visiveis num arquivo que alguem le
     * e mais honesto que escondê-los numa constante privada.
     *
     * @param pesos       peso de cada principio na media global
     * @param penalidades pontos descontados por problema, conforme a severidade
     */
    public record Score(Pesos pesos, Penalidades penalidades) {

        /**
         * Pesos iguais por padrao. Nao existe evidencia publicada de que
         * Perceptivel valha mais que Operavel, e inventar 35/30/25/10 daria ao
         * numero uma precisao que ele nao tem. Quem tiver um motivo para
         * diferenciar muda no {@code application.yml} — e passa a ter que
         * defender o motivo.
         */
        public record Pesos(int perceptivel, int operavel, int compreensivel, int robusto) {
        }

        /**
         * Escala escolhida para que a nota de uma categoria chegue a zero com
         * uma quantidade plausivel de problemas graves (sete de severidade ALTA),
         * e nao com um so. O numero exato e arbitrario; a ordem entre eles nao e.
         */
        public record Penalidades(int critica, int alta, int media, int baixa) {
        }
    }
}
