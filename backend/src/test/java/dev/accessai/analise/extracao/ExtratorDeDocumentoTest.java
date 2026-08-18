package dev.accessai.analise.extracao;

import static dev.accessai.analise.extracao.DocxDeTeste.alternateContent;
import static dev.accessai.analise.extracao.DocxDeTeste.cabecalhoDePagina;
import static dev.accessai.analise.extracao.DocxDeTeste.caixaDeTexto;
import static dev.accessai.analise.extracao.DocxDeTeste.caixaDeTextoComImagem;
import static dev.accessai.analise.extracao.DocxDeTeste.corpoComOutlineDeTexto;
import static dev.accessai.analise.extracao.DocxDeTeste.documento;
import static dev.accessai.analise.extracao.DocxDeTeste.formaVml;
import static dev.accessai.analise.extracao.DocxDeTeste.imagemAncorada;
import static dev.accessai.analise.extracao.DocxDeTeste.imagemInline;
import static dev.accessai.analise.extracao.DocxDeTeste.imagemVml;
import static dev.accessai.analise.extracao.DocxDeTeste.link;
import static dev.accessai.analise.extracao.DocxDeTeste.linkInterno;
import static dev.accessai.analise.extracao.DocxDeTeste.pacote;
import static dev.accessai.analise.extracao.DocxDeTeste.paragrafo;
import static dev.accessai.analise.extracao.DocxDeTeste.tabela;
import static dev.accessai.analise.extracao.DocxDeTeste.tabelaVazia;
import static dev.accessai.analise.extracao.DocxDeTeste.tituloPorEstilo;
import static dev.accessai.analise.extracao.DocxDeTeste.tituloPorEstiloEmPortugues;
import static dev.accessai.analise.extracao.DocxDeTeste.tituloPorOutline;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.accessai.analise.extracao.ImagemDoDocumento.SituacaoDoAlt;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Testes do extrator que roda em producao.
 *
 * <p>O spike tem sua propria suite, mas testa o codigo do spike — que ja
 * divergiu deste. Um extrator sem teste proprio e um extrator sem teste.
 */
@DisplayName("ExtratorDeDocumento")
class ExtratorDeDocumentoTest {

    private final ExtratorDeDocumento extrator = new ExtratorDeDocumento();

    @Nested
    @DisplayName("imagens: situacao do texto alternativo")
    class SituacaoDoTextoAlternativo {

        @Test
        @DisplayName("descr preenchido e PRESENTE")
        void descrPreenchido() {
            List<ImagemDoDocumento> imagens = corpo(
                    imagemInline("grafico.png", "Grafico de barras da receita")).imagens();

            assertThat(imagens).singleElement().satisfies(i -> {
                assertThat(i.situacaoAlt()).isEqualTo(SituacaoDoAlt.PRESENTE);
                assertThat(i.texto()).isEqualTo("Grafico de barras da receita");
                assertThat(i.nome()).isEqualTo("grafico.png");
            });
        }

        @Test
        @DisplayName("descr ausente e AUSENTE — o unico caso que vira problema")
        void descrAusente() {
            assertThat(corpo(imagemInline("foto.png", null)).imagens())
                    .singleElement()
                    .extracting(ImagemDoDocumento::situacaoAlt)
                    .isEqualTo(SituacaoDoAlt.AUSENTE);
        }

        @Test
        @DisplayName("descr vazio e VAZIO: imagem declarada decorativa, nao defeito")
        void descrVazio() {
            assertThat(corpo(imagemInline("linha.png", "")).imagens())
                    .singleElement()
                    .extracting(ImagemDoDocumento::situacaoAlt)
                    .isEqualTo(SituacaoDoAlt.VAZIO);
        }

        @Test
        @DisplayName("descr so com espacos conta como VAZIO, nao como preenchido")
        void descrSoComEspacos() {
            assertThat(corpo(imagemInline("assinatura.png", "   ")).imagens())
                    .singleElement()
                    .extracting(ImagemDoDocumento::situacaoAlt)
                    .isEqualTo(SituacaoDoAlt.VAZIO);
        }
    }

    @Nested
    @DisplayName("imagens: o que e e o que nao e imagem")
    class OQueEhImagem {

        @Test
        @DisplayName("caixa de texto tem docPr mas nao e imagem")
        void caixaDeTextoNaoEhImagem() {
            assertThat(corpo(caixaDeTexto("Caixa de Texto 2", "Prazo ate sexta")).imagens())
                    .as("caixa de texto sem descr viraria falso positivo de alt faltando")
                    .isEmpty();
        }

        @Test
        @DisplayName("autoforma VML sem imagedata nao e imagem")
        void formaVmlNaoEhImagem() {
            assertThat(corpo(formaVml("_x0000_s1030")).imagens()).isEmpty();
        }

        @Test
        @DisplayName("imagem dentro de caixa de texto conta uma vez, a caixa nao conta")
        void imagemDentroDaCaixaDeTexto() {
            assertThat(corpo(caixaDeTextoComImagem("Caixa 1", "selo.png", null)).imagens())
                    .singleElement()
                    .extracting(ImagemDoDocumento::nome)
                    .isEqualTo("selo.png");
        }

        @Test
        @DisplayName("VML com imagedata e imagem, e o alt vem do atributo alt")
        void vmlComImagedata() {
            assertThat(corpo(imagemVml("_x0000_s1026", "Logotipo")).imagens())
                    .singleElement()
                    .extracting(ImagemDoDocumento::texto)
                    .isEqualTo("Logotipo");
        }

        @Test
        @DisplayName("mc:AlternateContent nao conta a mesma imagem duas vezes")
        void alternateContentContaUmaVez() {
            assertThat(corpo(alternateContent("selo.png", "Selo")).imagens())
                    .as("Choice e Fallback descrevem o MESMO desenho")
                    .hasSize(1);
        }

        @Test
        @DisplayName("imagem ancorada e encontrada como a inline")
        void imagemAncoradaEhEncontrada() {
            assertThat(corpo(imagemAncorada("image1.png", null)).imagens()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("tabelas")
    class Tabelas {

        @Test
        @DisplayName("w:tblHeader na primeira linha marca a tabela como tendo cabecalho")
        void comCabecalho() {
            assertThat(corpo(tabela(3, true)).tabelas()).singleElement().satisfies(t -> {
                assertThat(t.primeiraLinhaEhCabecalho()).isTrue();
                assertThat(t.linhas()).isEqualTo(3);
                assertThat(t.indiceNaParte()).isEqualTo(1);
            });
        }

        @Test
        @DisplayName("sem w:tblHeader a tabela fica sem cabecalho")
        void semCabecalho() {
            assertThat(corpo(tabela(2, false)).tabelas())
                    .singleElement()
                    .extracting(TabelaDoDocumento::primeiraLinhaEhCabecalho)
                    .isEqualTo(false);
        }

        @Test
        @DisplayName("w:tblHeader com val=false desliga o marcador")
        void cabecalhoDesligadoExplicitamente() {
            // Ignorar o atributo transformaria uma tabela explicitamente sem
            // cabecalho em tabela conforme.
            assertThat(corpo(tabela(2, "<w:tblHeader w:val=\"false\"/>")).tabelas())
                    .singleElement()
                    .extracting(TabelaDoDocumento::primeiraLinhaEhCabecalho)
                    .isEqualTo(false);
        }

        @Test
        @DisplayName("w:tblHeader fora da primeira linha nao marca a tabela")
        void cabecalhoNaSegundaLinha() {
            String tabela = "<w:tbl><w:tblPr/>"
                    + "<w:tr><w:tc><w:p/></w:tc></w:tr>"
                    + "<w:tr><w:trPr><w:tblHeader/></w:trPr><w:tc><w:p/></w:tc></w:tr>"
                    + "</w:tbl>";

            assertThat(corpo(tabela).tabelas())
                    .singleElement()
                    .extracting(TabelaDoDocumento::primeiraLinhaEhCabecalho)
                    .isEqualTo(false);
        }

        @Test
        @DisplayName("tabela vazia e reconhecida como vazia")
        void tabelaSemLinhas() {
            assertThat(corpo(tabelaVazia()).tabelas())
                    .singleElement()
                    .matches(TabelaDoDocumento::ehVazia);
        }

        @Test
        @DisplayName("tabela aninhada nao contamina a de fora")
        void tabelaAninhada() {
            // A interna tem cabecalho, a externa nao. Sem pilha, a marcacao da
            // interna faria a externa passar.
            String externa = "<w:tbl><w:tblPr/><w:tr><w:tc>"
                    + tabela(2, true)
                    + "</w:tc></w:tr></w:tbl>";

            List<TabelaDoDocumento> tabelas = corpo(externa).tabelas();

            assertThat(tabelas).hasSize(2);
            assertThat(tabelas).filteredOn(TabelaDoDocumento::primeiraLinhaEhCabecalho).hasSize(1);
        }

        @Test
        @DisplayName("tabelas sao numeradas na ordem em que aparecem na parte")
        void numeracao() {
            assertThat(corpo(tabela(2, true), tabela(2, false)).tabelas())
                    .extracting(TabelaDoDocumento::indiceNaParte)
                    .containsExactly(1, 2);
        }
    }

    @Nested
    @DisplayName("titulos do corpo")
    class Titulos {

        @Test
        @DisplayName("estilo Heading vira nivel")
        void porEstilo() {
            assertThat(corpo(tituloPorEstilo(2, "Objeto")).cabecalhos())
                    .singleElement()
                    .satisfies(c -> {
                        assertThat(c.nivel()).isEqualTo(2);
                        assertThat(c.texto()).isEqualTo("Objeto");
                    });
        }

        @Test
        @DisplayName("estilo em portugues (Ttulo1, como Word pt-BR grava) tambem vira nivel")
        void porEstiloEmPortugues() {
            assertThat(corpo(tituloPorEstiloEmPortugues(1, "Edital")).cabecalhos())
                    .singleElement()
                    .extracting(CabecalhoDoDocumento::nivel)
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("outlineLvl vence o nome do estilo")
        void outlineVenceEstilo() {
            // Estilo proprio com nome que nao casa com nada, mas outlineLvl 2 = H3.
            assertThat(corpo(tituloPorOutline(3, "Anexo")).cabecalhos())
                    .singleElement()
                    .extracting(CabecalhoDoDocumento::nivel)
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("outlineLvl 9 e corpo de texto, nao titulo")
        void outlineDeCorpoNaoEhTitulo() {
            assertThat(corpo(corpoComOutlineDeTexto("paragrafo comum")).cabecalhos()).isEmpty();
        }

        @Test
        @DisplayName("paragrafo comum nao e titulo")
        void paragrafoComum() {
            assertThat(corpo(paragrafo("texto solto")).cabecalhos()).isEmpty();
        }

        @Test
        @DisplayName("a ordem do corpo e preservada")
        void ordem() {
            assertThat(corpo(tituloPorEstilo(1, "A"), paragrafo("x"), tituloPorEstilo(3, "B"))
                    .cabecalhos())
                    .extracting(CabecalhoDoDocumento::nivel)
                    .containsExactly(1, 3);
        }

        @Test
        @DisplayName("titulo em cabecalho de pagina nao entra no sumario do documento")
        void tituloEmHeaderNaoConta() {
            byte[] docx = pacote()
                    .com("word/header1.xml", cabecalhoDePagina(tituloPorEstilo(1, "Brasao")))
                    .bytes();

            assertThat(extrator.extrair(docx).cabecalhos()).isEmpty();
        }
    }

    @Nested
    @DisplayName("links")
    class Links {

        @Test
        @DisplayName("o destino vem do arquivo de relacionamentos")
        void destinoResolvido() {
            byte[] docx = pacote()
                    .comCorpo(link("rId7", "Edital completo"))
                    .comLinksExternos(Map.of("rId7", "https://prefeitura.gov.br/edital.pdf"))
                    .bytes();

            assertThat(extrator.extrair(docx).links()).singleElement().satisfies(l -> {
                assertThat(l.texto()).isEqualTo("Edital completo");
                assertThat(l.destino()).isEqualTo("https://prefeitura.gov.br/edital.pdf");
                assertThat(l.ehExterno()).isTrue();
            });
        }

        @Test
        @DisplayName("relacionamento inexistente deixa o destino nulo, sem quebrar")
        void relacionamentoAusente() {
            assertThat(corpo(link("rId99", "Clique aqui")).links())
                    .singleElement()
                    .satisfies(l -> {
                        assertThat(l.destino()).isNull();
                        assertThat(l.ehExterno()).isFalse();
                    });
        }

        @Test
        @DisplayName("ancora interna nao tem destino externo")
        void ancoraInterna() {
            assertThat(corpo(linkInterno("secao2", "ver secao 2")).links())
                    .singleElement()
                    .extracting(HyperlinkDoDocumento::ehExterno)
                    .isEqualTo(false);
        }

        @Test
        @DisplayName("texto quebrado em varios runs e remontado")
        void textoEmVariosRuns() {
            String linkPartido = "<w:p><w:hyperlink r:id=\"rId1\">"
                    + "<w:r><w:t>Saiba </w:t></w:r><w:r><w:t>mais</w:t></w:r>"
                    + "</w:hyperlink></w:p>";

            assertThat(corpo(linkPartido).links())
                    .singleElement()
                    .extracting(HyperlinkDoDocumento::textoNormalizado)
                    .isEqualTo("Saiba mais");
        }

        @Test
        @DisplayName("cada parte resolve seus proprios relacionamentos")
        void relacionamentosPorParte() {
            // rId1 significa coisas diferentes em document.xml e header1.xml.
            byte[] docx = pacote()
                    .comCorpo(link("rId1", "corpo"))
                    .comLinksExternos(Map.of("rId1", "https://a.gov.br"))
                    .com("word/header1.xml", cabecalhoDePagina(link("rId1", "topo")))
                    .com("word/_rels/header1.xml.rels",
                            "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas."
                            + "openxmlformats.org/package/2006/relationships\">"
                            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/"
                            + "officeDocument/2006/relationships/hyperlink\" "
                            + "Target=\"https://b.gov.br\" TargetMode=\"External\"/>"
                            + "</Relationships>")
                    .bytes();

            assertThat(extrator.extrair(docx).links())
                    .extracting(HyperlinkDoDocumento::destino)
                    .containsExactlyInAnyOrder("https://a.gov.br", "https://b.gov.br");
        }
    }

    @Nested
    @DisplayName("idioma e titulo do pacote")
    class IdiomaETitulo {

        @Test
        @DisplayName("w:lang em styles.xml e idioma padrao do documento")
        void idiomaPadrao() {
            byte[] docx = pacote().comIdiomaPadrao("pt-BR").bytes();

            assertThat(extrator.extrair(docx).idiomas()).singleElement().satisfies(i -> {
                assertThat(i.valor()).isEqualTo("pt-BR");
                assertThat(i.ehPadraoDoDocumento()).isTrue();
            });
        }

        @Test
        @DisplayName("x-none nao e idioma: e marca de 'sem verificacao ortografica'")
        void xNoneNaoEhIdioma() {
            byte[] docx = pacote().comEstilosSemIdioma().bytes();

            assertThat(extrator.extrair(docx).idiomas()).isEmpty();
        }

        @Test
        @DisplayName("w:lang em run e declaracao local, nao padrao do documento")
        void idiomaLocal() {
            String runComIdioma = "<w:p><w:r><w:rPr><w:lang w:val=\"en-US\"/></w:rPr>"
                    + "<w:t>hello</w:t></w:r></w:p>";

            assertThat(corpo(runComIdioma).idiomas())
                    .singleElement()
                    .extracting(IdiomaDeclarado::ehPadraoDoDocumento)
                    .isEqualTo(false);
        }

        @Test
        @DisplayName("dc:title preenchido e lido")
        void tituloPreenchido() {
            byte[] docx = pacote().comTitulo("Edital de Fomento 3/2026").bytes();

            DocumentoExtraido extraido = extrator.extrair(docx);
            assertThat(extraido.titulo()).contains("Edital de Fomento 3/2026");
            assertThat(extraido.semTitulo()).isFalse();
        }

        @Test
        @DisplayName("dc:title em branco e diferente de dc:title ausente")
        void tituloEmBrancoOuAusente() {
            DocumentoExtraido comBranco = extrator.extrair(pacote().comTitulo("   ").bytes());
            DocumentoExtraido semElemento =
                    extrator.extrair(pacote().comPropriedadesSemTitulo().bytes());

            assertThat(comBranco.titulo()).as("elemento existe, conteudo em branco").isPresent();
            assertThat(semElemento.titulo()).as("elemento nao existe").isEmpty();
            assertThat(comBranco.semTitulo()).isTrue();
            assertThat(semElemento.semTitulo()).isTrue();
        }

        @Test
        @DisplayName("pacote sem docProps nenhum nao tem titulo")
        void semDocProps() {
            assertThat(extrator.extrair(pacote().bytes()).semTitulo()).isTrue();
        }
    }

    @Nested
    @DisplayName("partes do pacote")
    class PartesDoPacote {

        @Test
        @DisplayName("imagem so no cabecalho e encontrada")
        void imagemNoCabecalho() {
            byte[] docx = pacote()
                    .com("word/header1.xml", cabecalhoDePagina(imagemAncorada("brasao.png", null)))
                    .bytes();

            assertThat(extrator.extrair(docx).imagens())
                    .singleElement()
                    .extracting(ImagemDoDocumento::partePacote)
                    .isEqualTo("word/header1.xml");
        }

        @Test
        @DisplayName("parte de nome imprevisto e varrida: a selecao e por exclusao")
        void parteDeNomeImprevisto() {
            // commentsDocument.xml apareceu no corpus real e derrubou a lista branca.
            byte[] docx = pacote()
                    .com("word/commentsDocument.xml", documento(imagemInline("nota.png", null)))
                    .bytes();

            assertThat(extrator.extrair(docx).imagens()).hasSize(1);
        }

        @Test
        @DisplayName("numbering.xml e ignorado: numPicBullet e marcador decorativo")
        void numberingEhIgnorado() {
            byte[] docx = pacote()
                    .com("word/numbering.xml", documento(imagemInline("bullet.png", null)))
                    .bytes();

            assertThat(extrator.extrair(docx).imagens()).isEmpty();
        }

        @Test
        @DisplayName("styles.xml nao entra como conteudo, mas ainda da o idioma")
        void stylesNaoEhConteudo() {
            byte[] docx = pacote()
                    .com("word/styles.xml",
                            "<?xml version=\"1.0\"?><w:styles " + DocxDeTeste.NS + ">"
                            + "<w:docDefaults><w:rPrDefault><w:rPr>"
                            + "<w:lang w:val=\"pt-BR\"/></w:rPr></w:rPrDefault></w:docDefaults>"
                            + imagemInline("nao-deveria-contar.png", null)
                            + "</w:styles>")
                    .bytes();

            DocumentoExtraido extraido = extrator.extrair(docx);

            assertThat(extraido.imagens()).as("styles.xml e configuracao").isEmpty();
            assertThat(extraido.idiomas()).as("mas o idioma padrao mora ali").hasSize(1);
        }

        @Test
        @DisplayName("word/theme/ e word/glossary/ sao ignorados")
        void temaEGlossarioIgnorados() {
            byte[] docx = pacote()
                    .com("word/theme/theme1.xml", documento(imagemInline("c.png", null)))
                    .com("word/glossary/document.xml", documento(imagemInline("d.png", null)))
                    .bytes();

            assertThat(extrator.extrair(docx).imagens()).isEmpty();
        }

        @Test
        @DisplayName("o resultado sai ordenado por parte do pacote")
        void resultadoOrdenado() {
            byte[] docx = pacote()
                    .comCorpo(imagemInline("corpo.png", null))
                    .com("word/header1.xml", cabecalhoDePagina(imagemInline("topo.png", null)))
                    .bytes();

            assertThat(extrator.extrair(docx).imagens())
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
                    .isInstanceOf(ParteIlegivelException.class)
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
                    .isInstanceOf(ParteIlegivelException.class)
                    .rootCause()
                    .hasMessageContaining("The entity \"x\" was referenced, but not declared");
        }

        @Test
        @DisplayName("pacote minimo devolve tudo vazio, nao erro")
        void pacoteMinimo() {
            DocumentoExtraido extraido = extrator.extrair(pacote().bytes());

            assertThat(extraido.imagens()).isEmpty();
            assertThat(extraido.tabelas()).isEmpty();
            assertThat(extraido.cabecalhos()).isEmpty();
            assertThat(extraido.links()).isEmpty();
            assertThat(extraido.idiomas()).isEmpty();
            assertThat(extraido.titulo()).isEmpty();
        }
    }

    private DocumentoExtraido corpo(String... fragmentos) {
        return extrator.extrair(pacote().comCorpo(fragmentos).bytes());
    }
}
