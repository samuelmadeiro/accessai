package dev.accessai.analise.regras;

import dev.accessai.analise.dominio.Problema;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Tabela de criterios WCAG carregada de {@code docs/wcag/criteria.json}.
 *
 * <p>Nenhuma regra escreve criterio, nivel ou numeracao em codigo (CONTRIBUTING.md
 * secao 6). A regra declara o identificador; nivel e titulo vem daqui. Se a
 * regra citar um criterio que nao existe na tabela, a aplicacao nao sobe — erro
 * de digitacao em criterio nao pode virar relatorio publicado.
 *
 * <p>A base e o WCAG2ICT, que resolve a aplicabilidade a documento nao-web.
 * O campo {@code aplicabilidade_ict} decide se o achado e violacao ou apenas
 * recomendacao.
 */
@Component
public class CatalogoWcag {

    private static final String CAMINHO = "wcag/criteria.json";

    private final Map<String, Criterio> porId;
    private final Fonte fonte;

    /**
     * {@code @Autowired} e obrigatorio aqui: a classe tem uma segunda
     * construtora (privada, para teste) e o Spring conta TODAS as construtoras
     * declaradas. Com duas candidatas e nenhuma marcada, ele procura uma
     * construtora sem argumento, nao acha, e o contexto nao sobe.
     */
    @Autowired
    public CatalogoWcag(ObjectMapper objectMapper) {
        this(carregar(objectMapper));
    }

    /**
     * Visivel para teste: monta o catalogo a partir de uma tabela em memoria.
     *
     * <p>Existe porque a tabela real vive em {@code docs/wcag/criteria.json} e
     * hoje tem um unico criterio. Testar "criterio inaplicavel nao gera
     * violacao" pelo arquivo exigiria poluir a tabela de producao com um
     * criterio que nenhuma regra usa — o oposto do que o proprio arquivo diz.
     *
     * <p>Fabrica, e nao segunda construtora publica: com duas construtoras o
     * Spring nao sabe qual usar e o contexto nem sobe.
     */
    static CatalogoWcag deTabela(Tabela tabela) {
        return new CatalogoWcag(tabela);
    }

    private CatalogoWcag(Tabela tabela) {
        this.fonte = tabela.fonte();
        this.porId = tabela.criterios().stream()
                .collect(Collectors.toMap(Criterio::id, Function.identity()));
    }

    private static Tabela carregar(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource(CAMINHO).getInputStream()) {
            return objectMapper.readValue(in, Tabela.class);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "nao foi possivel carregar a tabela WCAG em " + CAMINHO, e);
        }
    }

    /**
     * @throws CriterioDesconhecidoException se o identificador nao existe na tabela
     */
    public Criterio buscar(String id) {
        Criterio criterio = porId.get(id);
        if (criterio == null) {
            throw new CriterioDesconhecidoException(id, porId.keySet());
        }
        return criterio;
    }

    public Fonte fonte() {
        return fonte;
    }

    /**
     * {@code leiaAntes} e mapeado de proposito: se ficasse fora do record, o
     * arquivo teria uma propriedade desconhecida e a leitura dependeria de uma
     * configuracao global do ObjectMapper que este codigo nao controla.
     */
    public record Tabela(List<String> leiaAntes, Fonte fonte, List<Criterio> criterios) {
    }

    public record Fonte(Wcag wcag, Ict ict) {
        public record Wcag(String versao, String url) {
        }

        public record Ict(String documento, String titulo, String status, String publicadoEm,
                          String abrangencia, String url) {
        }
    }

    public record Criterio(String id, String titulo, Problema.Nivel nivel,
                           String aplicabilidadeIct, List<Substituicao> substituicoes,
                           String notaIct, String resumo) {

        public record Substituicao(String termo, String substituto) {
        }

        /**
         * WCAG2ICT pode marcar um criterio como inaplicavel a documento nao-web.
         * Nesse caso o achado e recomendacao, nunca violacao (CONTRIBUTING.md secao 6).
         */
        public boolean geraViolacao() {
            return !"inaplicavel".equals(aplicabilidadeIct);
        }
    }

    public static class CriterioDesconhecidoException extends RuntimeException {
        public CriterioDesconhecidoException(String id, java.util.Set<String> conhecidos) {
            super("criterio '" + id + "' nao existe em " + CAMINHO
                    + "; conhecidos: " + conhecidos);
        }
    }
}
