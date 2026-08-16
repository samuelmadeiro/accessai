package dev.accessai.analise.extracao;

import static dev.accessai.analise.extracao.DocxDeTeste.alternateContent;
import static dev.accessai.analise.extracao.DocxDeTeste.cabecalho;
import static dev.accessai.analise.extracao.DocxDeTeste.caixaDeTexto;
import static dev.accessai.analise.extracao.DocxDeTeste.caixaDeTextoComImagem;
import static dev.accessai.analise.extracao.DocxDeTeste.documento;
import static dev.accessai.analise.extracao.DocxDeTeste.formaVml;
import static dev.accessai.analise.extracao.DocxDeTeste.imagemAncorada;
import static dev.accessai.analise.extracao.DocxDeTeste.imagemInline;
import static dev.accessai.analise.extracao.DocxDeTeste.imagemVml;
import static dev.accessai.analise.extracao.DocxDeTeste.pacote;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.accessai.analise.extracao.ImagemDoDocumento.SituacaoDoAlt;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Testes do extrator que roda em producao.
 *
 * <p>O spike tem sua propria suite, mas testa o codigo do spike — que ja
 * divergiu deste. Um extrator sem teste proprio e um extrator sem teste.
 */
@DisplayName("ExtratorDeImagens")
class ExtratorDeImagensTest {

    private final ExtratorDeImagens extrator = new ExtratorDeImagens();

    @Nested
    @DisplayName("situacao do texto alternativo")
    class SituacaoDoTextoAlternativo {

        @Test
        @DisplayName("descr preenchido e PRESENTE")
        void descrPreenchido() {
            List<ImagemDoDocumento> imagens = extrair(
                    imagemInline("grafico.png", "Grafico de barras da receita"));

            assertThat(imagens).singleElement().satisfies(i -> {
                assertThat(i.situacaoAlt()).isEqualTo(SituacaoDoAlt.PRESENTE);
                assertThat(i.texto()).isEqualTo("Grafico de barras da receita");
                assertThat(i.nome()).isEqualTo("grafico.png");
            });
        }

        @Test
        @DisplayName("descr ausente e AUSENTE — o unico caso que vira problema")
        void descrAusente() {
            List<ImagemDoDocumento> imagens = extrair(imagemInline("foto.png", null));

            assertThat(imagens).singleElement().satisfies(i -> {
                assertThat(i.situacaoAlt()).isEqualTo(SituacaoDoAlt.AUSENTE);
                assertThat(i.texto()).isNull();
            });
        }

        @Test
        @DisplayName("descr vazio e VAZIO: imagem declarada decorativa, nao defeito")
        void descrVazio() {
            List<ImagemDoDocumento> imagens = extrair(imagemInline("linha.png", ""));

            assertThat(imagens).singleElement()
                    .extracting(ImagemDoDocumento::situacaoAlt)
                    .isEqualTo(SituacaoDoAlt.VAZIO);
        }

        @Test
        @DisplayName("descr so com espacos conta como VAZIO, nao como preenchido")
        void descrSoComEspacos() {
            List<ImagemDoDocumento> imagens = extrair(imagemInline("assinatura.png", "   "));

            assertThat(imagens).singleElement()
                    .extracting(ImagemDoDocumento::situacaoAlt)
                    .isEqualTo(SituacaoDoAlt.VAZIO);
        }
    }

    @Nested
    @DisplayName("o que e e o que nao e imagem")
    class OQueEhImagem {

        @Test
        @DisplayName("caixa de texto tem docPr mas nao e imagem")
        void caixaDeTextoNaoEhImagem() {
            List<ImagemDoDocumento> imagens = extrair(
                    caixaDeTexto("Caixa de Texto 2", "Atencao: prazo ate sexta"));

            assertThat(imagens)
                    .as("caixa de texto sem descr viraria falso positivo de alt faltando")
                    .isEmpty();
        }

        @Test
        @DisplayName("autoforma VML sem imagedata nao e imagem")
        void formaVmlNaoEhImagem() {
            assertThat(extrair(formaVml("_x0000_s1030"))).isEmpty();
        }

        @Test
        @DisplayName("imagem dentro de caixa de texto conta uma vez, a caixa nao conta")
        void imagemDentroDaCaixaDeTexto() {
            List<ImagemDoDocumento> imagens = extrair(
                    caixaDeTextoComImagem("Caixa 1", "selo.png", null));

            assertThat(imagens).singleElement().satisfies(i -> {
                assertThat(i.nome()).isEqualTo("selo.png");
                assertThat(i.situacaoAlt()).isEqualTo(SituacaoDoAlt.AUSENTE);
            });
        }

        @Test
        @DisplayName("VML com imagedata e imagem, e o alt vem do atributo alt")
        void vmlComImagedata() {
            List<ImagemDoDocumento> imagens = extrair(
                    imagemVml("_x0000_s1026", "Logotipo da prefeitura"));

            assertThat(imagens).singleElement().satisfies(i -> {
                assertThat(i.situacaoAlt()).isEqualTo(SituacaoDoAlt.PRESENTE);
                assertThat(i.texto()).isEqualTo("Logotipo da prefeitura");
            });
        }

        @Test
        @DisplayName("mc:AlternateContent nao conta a mesma imagem duas vezes")
        void alternateContentContaUmaVez() {
            List<ImagemDoDocumento> imagens = extrair(
                    alternateContent("selo.png", "Selo de acessibilidade"));

            assertThat(imagens)
                    .as("Choice e Fallback descrevem o MESMO desenho")
                    .hasSize(1);
        }

        @Test
        @DisplayName("imagem ancorada e encontrada como a inline")
        void imagemAncoradaEhEncontrada() {
            assertThat(extrair(imagemAncorada("image1.png", null))).hasSize(1);
        }
    }

    @Nested
    @DisplayName("partes do pacote")
    class PartesDoPacote {

        @Test
        @DisplayName("imagem so no cabecalho e encontrada")
        void imagemNoCabecalho() {
            byte[] docx = pacote()
                    .com("word/header1.xml", cabecalho(imagemAncorada("brasao.png", null)))
                    .bytes();

            List<ImagemDoDocumento> imagens = extrator.extrair(docx);

            assertThat(imagens).singleElement()
                    .extracting(ImagemDoDocumento::partePacote)
                    .isEqualTo("word/header1.xml");
        }

        @Test
        @DisplayName("imagem no rodape tambem")
        void imagemNoRodape() {
            byte[] docx = pacote()
                    .com("word/footer1.xml", cabecalho(imagemInline("rodape.png", null)))
                    .bytes();

            assertThat(extrator.extrair(docx)).singleElement()
                    .extracting(ImagemDoDocumento::partePacote)
                    .isEqualTo("word/footer1.xml");
        }

        @Test
        @DisplayName("parte de nome imprevisto e varrida: a selecao e por exclusao")
        void parteDeNomeImprevisto() {
            // commentsDocument.xml apareceu no corpus real e derrubou a lista branca.
            byte[] docx = pacote()
                    .com("word/commentsDocument.xml", documento(imagemInline("nota.png", null)))
                    .bytes();

            assertThat(extrator.extrair(docx)).hasSize(1);
        }

        @Test
        @DisplayName("numbering.xml e ignorado: numPicBullet e marcador decorativo")
        void numberingEhIgnorado() {
            byte[] docx = pacote()
                    .com("word/numbering.xml", documento(imagemInline("bullet.png", null)))
                    .bytes();

            assertThat(extrator.extrair(docx)).isEmpty();
        }

        @Test
        @DisplayName("styles.xml, settings.xml e word/theme/ sao ignorados")
        void configuracaoEhIgnorada() {
            byte[] docx = pacote()
                    .com("word/styles.xml", documento(imagemInline("a.png", null)))
                    .com("word/settings.xml", documento(imagemInline("b.png", null)))
                    .com("word/theme/theme1.xml", documento(imagemInline("c.png", null)))
                    .com("word/glossary/document.xml", documento(imagemInline("d.png", null)))
                    .bytes();

            assertThat(extrator.extrair(docx)).isEmpty();
        }

        @Test
        @DisplayName("o resultado sai ordenado por parte do pacote")
        void resultadoOrdenado() {
            byte[] docx = pacote()
                    .comCorpo(imagemInline("corpo.png", null))
                    .com("word/header1.xml", cabecalho(imagemInline("topo.png", null)))
                    .bytes();

            assertThat(extrator.extrair(docx))
                    .extracting(ImagemDoDocumento::partePacote)
                    .containsExactly("word/document.xml", "word/header1.xml");
        }
    }

    @Nested
    @DisplayName("entrada hostil")
    class EntradaHostil {

        @Test
        @DisplayName("XML quebrado vira ParteIlegivelException nomeando a parte")
        void xmlQuebrado() {
            byte[] docx = pacote().com("word/document.xml", "<w:document><w:body>").bytes();

            assertThatThrownBy(() -> extrator.extrair(docx))
                    .isInstanceOf(ExtratorDeImagens.ParteIlegivelException.class)
                    .hasMessageContaining("word/document.xml");
        }

        @Test
        @DisplayName("entidade declarada em DTD nao expande: XXE e billion laughs fechados")
        void entidadeDeDtdNaoExpande() {
            // Com supportDTD=false o parser do JDK nao recusa o DOCTYPE: ele
            // ignora a declaracao. O efeito de seguranca aparece no uso — a
            // entidade fica indefinida e a leitura da parte falha, em vez de
            // expandir conteudo escolhido por quem enviou o arquivo.
            String comDtd = "<?xml version=\"1.0\"?>"
                    + "<!DOCTYPE w:document [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                    + "<w:document " + DocxDeTeste.NS + "><w:body><w:p><w:t>&x;</w:t></w:p>"
                    + "</w:body></w:document>";
            byte[] docx = pacote().com("word/document.xml", comDtd).bytes();

            assertThatThrownBy(() -> extrator.extrair(docx))
                    .isInstanceOf(ExtratorDeImagens.ParteIlegivelException.class)
                    .rootCause()
                    .hasMessageContaining("The entity \"x\" was referenced, but not declared");
        }

        @Test
        @DisplayName("documento sem imagem nenhuma devolve lista vazia, nao erro")
        void documentoSemImagem() {
            assertThat(extrator.extrair(pacote().bytes())).isEmpty();
        }
    }

    private List<ImagemDoDocumento> extrair(String... fragmentos) {
        return extrator.extrair(pacote().comCorpo(fragmentos).bytes());
    }
}
