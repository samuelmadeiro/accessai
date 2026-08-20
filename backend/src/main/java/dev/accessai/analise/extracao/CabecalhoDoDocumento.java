package dev.accessai.analise.extracao;

import org.jspecify.annotations.NonNull;

/**
 * Um paragrafo marcado como titulo, na ordem em que aparece no corpo.
 *
 * <p>A ordem e o dado principal: a regra de hierarquia compara cada nivel com o
 * anterior, e uma lista fora de ordem produziria salto inexistente.
 *
 * @param partePacote parte onde o titulo esta
 * @param nivel       1 a 9, ja normalizado a partir do estilo ou do outlineLvl
 * @param texto       texto do paragrafo, para a evidencia apontar onde e
 */
public record CabecalhoDoDocumento(String partePacote, int nivel, String texto) {

    /** Titulo longo vira evidencia ilegivel; o comeco basta para localizar. */
    public @NonNull String resumo() {
        String limpo = texto == null ? "" : texto.strip();
        if (limpo.isEmpty()) {
            return "(sem texto)";
        }
        return limpo.length() <= 60 ? limpo : limpo.substring(0, 57) + "...";
    }
}
