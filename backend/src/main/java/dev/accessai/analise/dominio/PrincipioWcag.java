package dev.accessai.analise.dominio;

import org.jspecify.annotations.NonNull;

/**
 * Os quatro principios da WCAG: Perceptivel, Operavel, Compreensivel, Robusto.
 *
 * <p>A categoria do score sai daqui, e sai do NUMERO do criterio: 1.x e
 * Perceptivel, 2.x Operavel, 3.x Compreensivel, 4.x Robusto. Nao existe tabela
 * de regra para categoria em lugar nenhum — ela seria uma segunda fonte de
 * verdade para algo que a propria numeracao da WCAG ja define, e divergiria na
 * primeira regra nova.
 */
public enum PrincipioWcag {

    PERCEPTIVEL(1, "Perceptivel"),
    OPERAVEL(2, "Operavel"),
    COMPREENSIVEL(3, "Compreensivel"),
    ROBUSTO(4, "Robusto");

    private final int digito;
    private final String titulo;

    PrincipioWcag(int digito, String titulo) {
        this.digito = digito;
        this.titulo = titulo;
    }

    public String titulo() {
        return titulo;
    }

    /**
     * @param criterioId identificador no formato {@code 1.3.1}
     * @throws IllegalArgumentException se o identificador nao comeca com 1 a 4;
     *         criterio fora da WCAG nao tem principio, e inventar um colocaria
     *         penalidade numa categoria errada sem ninguem perceber
     */
    public static @NonNull PrincipioWcag doCriterio(String criterioId) {
        if (criterioId != null && !criterioId.isBlank()) {
            char primeiro = criterioId.charAt(0);
            for (PrincipioWcag principio : values()) {
                if (primeiro == Character.forDigit(principio.digito, 10)) {
                    return principio;
                }
            }
        }
        throw new IllegalArgumentException(
                "criterio '" + criterioId + "' nao comeca com um principio WCAG (1 a 4)");
    }
}
