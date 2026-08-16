package dev.accessai.analise.regras;

import dev.accessai.analise.dominio.Problema;
import dev.accessai.analise.extracao.ImagemDoDocumento;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Imagem sem texto alternativo — WCAG 1.1.1, nivel A.
 *
 * <p>A unica regra da Slice 1, escolhida por ser a mais barata de todas: le um
 * atributo, sem cascata de estilo e sem heuristica.
 *
 * <p>Distincao que define a regra: alt <b>ausente</b> e defeito; alt
 * <b>vazio</b> nao. Um {@code descr=""} e a forma prevista de declarar imagem
 * decorativa, e o proprio 1.1.1 admite conteudo que possa ser ignorado pela
 * tecnologia assistiva. Tratar vazio como defeito produziria falso positivo em
 * todo documento bem marcado — exatamente o oposto do objetivo.
 */
@Component
public class RegraImagemSemTextoAlternativo implements RegraDeAcessibilidade {

    private static final String ID = "IMAGEM_SEM_TEXTO_ALTERNATIVO";
    private static final String CRITERIO = "1.1.1";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String criterioWcag() {
        return CRITERIO;
    }

    @Override
    public List<Achado> avaliar(List<ImagemDoDocumento> imagens) {
        return imagens.stream()
                .filter(i -> i.situacaoAlt() == ImagemDoDocumento.SituacaoDoAlt.AUSENTE)
                .map(RegraImagemSemTextoAlternativo::paraAchado)
                .toList();
    }

    private static Achado paraAchado(ImagemDoDocumento imagem) {
        String nome = imagem.nome() == null || imagem.nome().isBlank()
                ? "(sem nome)" : imagem.nome();
        // ALTA e nao CRITICA: a imagem pode ser decorativa e o autor apenas ter
        // esquecido de marca-la como tal. Severidade contextual e outro assunto,
        // e ficou fora do projeto (D2).
        return new Achado(Problema.Severidade.ALTA, imagem.partePacote(),
                "imagem '" + nome + "' nao tem atributo de texto alternativo (descr)");
    }
}
