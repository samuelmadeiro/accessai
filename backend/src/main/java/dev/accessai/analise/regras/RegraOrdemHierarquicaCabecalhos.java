package dev.accessai.analise.regras;

import dev.accessai.analise.dominio.Problema;
import dev.accessai.analise.extracao.CabecalhoDoDocumento;
import dev.accessai.analise.extracao.DocumentoExtraido;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Salto na hierarquia de titulos — WCAG 1.3.1, nivel A.
 *
 * <p>Quem navega por leitor de tela pula de titulo em titulo e usa o nivel para
 * montar o mapa do documento. Um H1 seguido de H3 diz que existe uma secao
 * intermediaria que nunca aparece — o mapa fica com um degrau faltando.
 *
 * <p>Duas coisas contam como salto:
 *
 * <ul>
 *   <li>descer mais de um nivel entre titulos consecutivos (H1 para H3);</li>
 *   <li>o primeiro titulo do documento nao ser H1 — comecar em H2 e o mesmo
 *       degrau faltando, so que no inicio.</li>
 * </ul>
 *
 * <p><b>Subir nivel nunca e salto.</b> Voltar de H3 para H1 e o fim de uma
 * secao, nao um erro; tratar isso como problema encheria de falso positivo
 * qualquer documento com mais de uma secao.
 */
@Component
public class RegraOrdemHierarquicaCabecalhos implements RegraDeAcessibilidade {

    private static final String ID = "ORDEM_HIERARQUICA_CABECALHOS";
    private static final String CRITERIO = "1.3.1";
    private static final int NIVEL_ESPERADO_NO_INICIO = 1;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String criterioWcag() {
        return CRITERIO;
    }

    @Override
    public List<Achado> avaliar(DocumentoExtraido documento) {
        List<Achado> achados = new ArrayList<>();
        int anterior = 0;

        for (CabecalhoDoDocumento cabecalho : documento.cabecalhos()) {
            int nivel = cabecalho.nivel();
            if (nivel > anterior + 1) {
                achados.add(paraAchado(cabecalho, anterior));
            }
            anterior = nivel;
        }
        return List.copyOf(achados);
    }

    private static Achado paraAchado(CabecalhoDoDocumento cabecalho, int anterior) {
        String origem = anterior == 0
                ? "o primeiro titulo do documento e H" + cabecalho.nivel()
                        + ", e nao H" + NIVEL_ESPERADO_NO_INICIO
                : "titulo H" + cabecalho.nivel() + " vem logo depois de um H" + anterior;
        // MEDIA: a informacao continua legivel, o que se perde e a navegacao.
        return new Achado(Problema.Severidade.MEDIA, cabecalho.partePacote(),
                origem + " — '" + cabecalho.resumo() + "'");
    }
}
