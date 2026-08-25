package dev.accessai.copiloto;

import dev.accessai.ia.AiProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Uma fala gravada da conversa sobre uma analise.
 *
 * <p>Entidade JPA nunca cruza a fronteira da API (CONTRIBUTING.md secao 5).
 *
 * <p><b>O que NAO existe aqui e a decisao do ADR 0013:</b> nao ha coluna de
 * prompt, nem de evidencia, nem qualquer recorte do `.docx`. A evidencia
 * continua sendo ENVIADA ao provider — sem ela a resposta nao seria fundamentada
 * — mas ela ja esta gravada em `problema.evidencia`, presa ao problema que a
 * originou. Grava-la de novo criaria uma segunda copia do mesmo dado pessoal de
 * terceiro, com ciclo de vida proprio, num projeto cuja unica decisao de
 * privacidade anterior foi nao comprometer esses arquivos no git (condicao C-3).
 */
@Entity
@Table(name = "turno_de_conversa")
public class TurnoDeConversa {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "analise_id", nullable = false, updatable = false)
    private UUID analiseId;

    @Column(name = "papel", nullable = false, updatable = false)
    private String papel;

    @Column(name = "texto", nullable = false, updatable = false)
    private String texto;

    /** Nulo na fala do usuario: ela nao veio de provider nenhum. */
    @Column(name = "procedencia", updatable = false)
    private String procedencia;

    @Column(name = "modelo", updatable = false)
    private String modelo;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm;

    protected TurnoDeConversa() {
        // exigido pelo JPA
    }

    /** A pergunta como o usuario escreveu. Sem procedencia, e o campo diz isso. */
    public static @NonNull TurnoDeConversa doUsuario(UUID analiseId, String texto,
                                                     Instant agora) {
        TurnoDeConversa t = novo(analiseId, AiProvider.Turno.Papel.USUARIO, texto, agora);
        return t;
    }

    /**
     * A resposta, com a procedencia obrigatoria.
     *
     * <p>Os dois campos sao exigidos aqui e pelo CHECK da V7. Um deles sozinho
     * seria convencao; os dois juntos fazem com que uma fala de fixture nao
     * consiga se passar por saida de modelo nem por engano nem por atalho.
     */
    public static @NonNull TurnoDeConversa doAssistente(UUID analiseId, String texto,
                                                        AiProvider.Procedencia procedencia,
                                                        String modelo, Instant agora) {
        TurnoDeConversa t = novo(analiseId, AiProvider.Turno.Papel.ASSISTENTE, texto, agora);
        t.procedencia = procedencia.name();
        t.modelo = modelo;
        return t;
    }

    private static TurnoDeConversa novo(UUID analiseId, AiProvider.Turno.Papel papel,
                                        String texto, Instant agora) {
        TurnoDeConversa t = new TurnoDeConversa();
        t.id = UUID.randomUUID();
        t.analiseId = analiseId;
        t.papel = papel.name();
        t.texto = texto;
        t.criadoEm = agora;
        return t;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAnaliseId() {
        return analiseId;
    }

    public String getPapel() {
        return papel;
    }

    public String getTexto() {
        return texto;
    }

    public String getProcedencia() {
        return procedencia;
    }

    public String getModelo() {
        return modelo;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }
}
