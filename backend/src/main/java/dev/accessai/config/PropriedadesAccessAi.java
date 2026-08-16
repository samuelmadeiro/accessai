package dev.accessai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Configuracao da aplicacao. Existe para que nao haja numero magico espalhado
 * pelo codigo (CLAUDE.md secao 5): limite de upload, nome de topico e
 * particoes sao decisoes de operacao, nao constantes de classe.
 */
@ConfigurationProperties(prefix = "accessai")
public record PropriedadesAccessAi(Upload upload, Kafka kafka) {

    public record Upload(DataSize tamanhoMaximo) {
    }

    public record Kafka(String topicoAnaliseSolicitada, int particoes, short replicas) {
    }
}
