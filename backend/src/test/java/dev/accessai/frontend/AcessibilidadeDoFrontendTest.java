package dev.accessai.frontend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * As invariantes de acessibilidade do proprio frontend, conferidas no build.
 *
 * <p>Uma ferramenta que audita acessibilidade com uma interface inacessivel e
 * autogol — o `fase-0.md` diz isso com essas palavras ao proteger a Slice 8. O
 * que este teste faz e o minimo coerente: aplicar ao AccessAI a mesma regra que
 * o AccessAI aplica em documento de terceiro.
 *
 * <p><b>O que ele NAO faz, e precisa estar dito:</b> teste estatico nao aperta
 * Tab, nao ouve leitor de tela e nao mede contraste renderizado. Ele pega a
 * classe de defeito que e estrutural — rotulo ausente, `tabindex` positivo,
 * imagem sem alt, `div` fazendo papel de botao — e nada alem disso. O criterio
 * de pronto do §7 pede teste com leitor de tela, e esse continua sendo manual.
 *
 * <p>Le do classpath, e nao de {@code ../frontend}: em {@code static/} esta o
 * arquivo que o Boot realmente serve. Testar a origem deixaria passar um erro na
 * copia do build.
 */
@DisplayName("Slice 8: acessibilidade do proprio frontend")
class AcessibilidadeDoFrontendTest {

    private static final Pattern INPUT = Pattern.compile("<input\\b[^>]*>", Pattern.DOTALL);
    private static final Pattern LABEL_FOR = Pattern.compile("<label[^>]*\\bfor=\"([^\"]+)\"");
    private static final Pattern IMG = Pattern.compile("<img\\b[^>]*>", Pattern.DOTALL);
    private static final Pattern TABINDEX = Pattern.compile("tabindex=\"(-?\\d+)\"");
    private static final Pattern H1 = Pattern.compile("<h1\\b");
    private static final Pattern ID = Pattern.compile("\\bid=\"([^\"]+)\"");

    private static String pagina(String nome) throws IOException {
        try (InputStream in = AcessibilidadeDoFrontendTest.class
                .getClassLoader().getResourceAsStream("static/" + nome)) {
            assertThat(in).as("%s precisa estar em static/: sem isso o Boot nao o serve", nome)
                    .isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"index.html", "analise.html"})
    @DisplayName("declara idioma, titulo e um unico h1")
    void idiomaTituloECabecalho(String nome) throws IOException {
        String html = pagina(nome);

        // 3.1.1 Language of Page: sem lang, o leitor de tela le portugues com
        // pronuncia da lingua padrao dele. E a mesma regra que o Rule Engine
        // cobra do .docx.
        assertThat(html).contains("<html lang=\"pt-BR\">");
        assertThat(html).matches("(?s).*<title>\\s*\\S.*</title>.*");

        // 2.4.2 Page Titled e 1.3.1: um h1 por pagina. Zero deixa a pagina sem
        // topo de estrutura; dois criam duas raizes e quebram a navegacao por
        // cabecalho.
        assertThat(H1.matcher(html).results().count())
                .as("%s precisa de exatamente um h1", nome).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"index.html", "analise.html"})
    @DisplayName("tem link de pular apontando para um alvo que existe")
    void linkDePular(String nome) throws IOException {
        String html = pagina(nome);

        // 2.4.1 Bypass Blocks. O link que aponta para ancora inexistente e pior
        // que nenhum: ele promete um atalho e nao leva a lugar nenhum.
        assertThat(html).contains("class=\"pular\" href=\"#conteudo\"");
        assertThat(html).contains("id=\"conteudo\"");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"index.html", "analise.html"})
    @DisplayName("tem as tres regioes de marco: header, main e footer")
    void marcos(String nome) throws IOException {
        String html = pagina(nome);

        // 1.3.1: quem navega por regiao pula direto para o conteudo. Sem os
        // marcos, a pagina inteira e um bloco unico.
        assertThat(html).contains("<header>").contains("<main id=\"conteudo\">")
                .contains("<footer>");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"index.html", "analise.html"})
    @DisplayName("todo campo tem rotulo ligado por for/id")
    void camposRotulados(String nome) throws IOException {
        String html = pagina(nome);

        List<String> rotulados = new ArrayList<>();
        Matcher rotulo = LABEL_FOR.matcher(html);
        while (rotulo.find()) {
            rotulados.add(rotulo.group(1));
        }

        Matcher campo = INPUT.matcher(html);
        while (campo.find()) {
            String tag = campo.group();
            Matcher id = ID.matcher(tag);
            // 3.3.2 Labels or Instructions. Campo sem rotulo e anunciado como
            // "caixa de edicao" e nada mais: quem nao ve a tela nao tem como
            // saber o que digitar ali.
            assertThat(id.find())
                    .as("campo sem id em %s nao tem como ser rotulado: %s", nome, tag)
                    .isTrue();
            assertThat(rotulados)
                    .as("campo %s de %s nao tem <label for>", id.group(1), nome)
                    .contains(id.group(1));
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"index.html", "analise.html"})
    @DisplayName("nenhum tabindex positivo")
    void semTabindexPositivo(String nome) throws IOException {
        String html = pagina(nome);

        Matcher indice = TABINDEX.matcher(html);
        while (indice.find()) {
            // 2.4.3 Focus Order. `tabindex` positivo tira o elemento da ordem do
            // documento e joga na frente de tudo; basta um para embaralhar a
            // sequencia da pagina inteira. Negativo e legitimo — e o que permite
            // mover foco por programa para um titulo.
            assertThat(Integer.parseInt(indice.group(1)))
                    .as("tabindex positivo em %s reordena a navegacao da pagina toda", nome)
                    .isLessThanOrEqualTo(0);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"index.html", "analise.html"})
    @DisplayName("toda imagem tem alt, e nenhum controle e div com onclick")
    void imagensEControles(String nome) throws IOException {
        String html = pagina(nome);

        Matcher imagem = IMG.matcher(html);
        while (imagem.find()) {
            // 1.1.1 Non-text Content — a primeira regra do proprio Rule Engine.
            assertThat(imagem.group()).as("imagem sem alt em %s", nome).contains("alt=");
        }

        // 4.1.2 Name, Role, Value e 2.1.1 Keyboard. `div` com onclick nao recebe
        // foco, nao responde a Enter e nao se anuncia como botao. O elemento
        // certo resolve os tres de graca.
        assertThat(html).doesNotContain("onclick");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"index.html", "analise.html"})
    @DisplayName("regiao viva existe desde o carregamento")
    void regiaoViva(String nome) throws IOException {
        String html = pagina(nome);

        // 4.1.3 Status Messages. A regiao precisa estar no HTML inicial: criada
        // depois por JavaScript, ela costuma nao ser observada, e a mensagem
        // aparece na tela sem ser anunciada.
        assertThat(html).contains("aria-live=\"polite\"");
        assertThat(html).doesNotContain("aria-live=\"assertive\"");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("a tabela do score tem legenda e cabecalho de coluna")
    void tabelaComEscopo() throws IOException {
        String html = pagina("analise.html");

        // 1.3.1 de novo, agora do lado de dentro: e a mesma regra que o
        // RegraTabelaSemCabecalho cobra do .docx analisado. Falhar aqui seria o
        // produto reprovando um documento pelo defeito que ele mesmo tem.
        assertThat(html).contains("<caption>");
        assertThat(html).contains("<th scope=\"col\">");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("o JavaScript nao escreve HTML de terceiro na tela")
    void semInnerHtml() throws IOException {
        for (String script : List.of("app.js", "entrada.js", "resultado.js")) {
            // Nome de arquivo, evidencia extraida do .docx e resposta do
            // copiloto vem de fora. `innerHTML` transformaria qualquer um deles
            // em XSS — o CONTRIBUTING.md secao 5 trata conteudo de terceiro como
            // hostil, e a tela nao e excecao.
            //
            // A conferencia e sobre CODIGO, e por isso os comentarios saem
            // antes: a primeira versao deste teste reprovou o comentario que
            // explica por que innerHTML nao e usado, o que teria empurrado a
            // explicacao para fora do arquivo em nome da propria regra.
            assertThat(semComentarios(pagina(script)))
                    .as("%s usa innerHTML com conteudo que veio de fora", script)
                    .doesNotContain("innerHTML");
        }
    }

    private static String semComentarios(String javascript) {
        return javascript
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)^\\s*//.*$", "");
    }
}
