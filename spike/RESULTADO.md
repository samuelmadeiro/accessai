# Spike de extração de alt text em DOCX — resultado

Executado conforme a condição **C-2** de `docs/architecture/fase-0.md`.
Timebox: 3 dias úteis. Consumido: uma sessão.

Projeto descartável. Não contém Spring, Kafka, Docker, banco nem pacote de
produção.

---

## Veredito

**Parsing XML direto.** O POI não fica no caminho de extração de alt text.

## Como os dois se saíram

| Caso | Armadilha | POI | XML direto |
|---|---|---|---|
| 01 | `wp:inline` com `descr` | ✅ | ✅ |
| 02 | `descr=""` (decorativa) | ✅ | ✅ |
| 03 | `descr` ausente | ✅ | ✅ |
| 04 | `wp:anchor` sem `descr` | ✅ | ✅ |
| 05 | LibreOffice: `descr` + `title` | ✅ | ✅ |
| 06 | `mc:AlternateContent` duplicado | ❌ **devolve 0 imagens** | ✅ 1 imagem |
| 07 | VML legado `v:shape/@alt` | ⚠️ só relendo XML cru | ✅ |
| 08 | imagem só no cabeçalho | ✅ | ✅ |
| 09 | 3 imagens mistas | ✅ | ✅ |
| 10 | `descr="   "` | ✅ | ✅ |

26 testes, 0 falhas. O teste da amostra real e PULADO quando
`-Dspike.amostraReal=/caminho/arquivo.docx` nao e informado — o arquivo e
material de terceiro e nao esta no repositorio.

## Por que o caso 06 decide a questão

O XmlBeans do POI só vincula elementos previstos no schema de `w:r`. Um
`w:drawing` dentro de `mc:AlternateContent` não aparece em
`CTR.getDrawingList()`. O POI não devolve alt errado — **devolve imagem
nenhuma**.

Para uma ferramenta de score isso é um falso negativo silencioso: um documento
com imagem inacessível pontuaria como limpo, e o relatório afirmaria
conformidade que não existe. Errar para mais é ruim; afirmar conformidade falsa
inviabiliza o produto.

`ExtratorPoiTest.naoEnxergaAlternateContent` trava a limitação por escrito e
falha se uma versão futura do POI corrigir — momento de reavaliar.

## O que a medição mostrou, contrariando a hipótese

**Linhas de código empataram: 112 (POI) × 124 (XML direto)**, descontando
comentário e linha em branco; com tudo, 140 × 177. A premissa de que
o POI economizaria código está errada para este caso. Ele economiza em leitura
de parágrafo e tabela, não em metadados de acessibilidade — `getAllPictures()`
devolve bytes de imagem, não `descr`. Para chegar ao alt text é preciso descer
a `CTP` → `CTR` → `CTDrawing` → `CTInline`/`CTAnchor` → `CTNonVisualDrawingProps`,
que é o mesmo nível de detalhe do XML cru, com uma camada de indireção a mais.

Como o custo em código empatou, a correção decide sozinha.

**Peso de dependência:** 13 jars, ~18 MB (POI, XmlBeans, 4 commons, log4j-api,
SparseBitSet, curvesapi) contra **0 jars** — `java.util.zip` e
`javax.xml.stream` são do JDK.

**O caminho POI não evita XML cru mesmo assim.** VML (`v:shape/@alt`) não tem
acessor; o extrator POI reabre `xmlText()` e cata o atributo na unha. Ou seja,
adota-se a dependência e ainda se escreve o parser.

## Consequência para a Slice 2

Reforça a inclinação já registrada em D1: a cascata de contraste
(direta → estilo de caractere → estilo de parágrafo → `docDefaults` → tema, com
`themeTint`/`themeShade`) precisa de `styles.xml` e `theme1.xml`, que o POI não
expõe como herança resolvida. Extração e contraste ficam no mesmo caminho.

**Isso não elimina o POI do projeto.** Para texto corrido, parágrafos e tabelas
ele continua sendo a escolha óbvia. A decisão é escopada: alt text e contraste
saem por XML direto.

## Critérios de C-2 — avaliação honesta

| Critério | Estado |
|---|---|
| Abordagem decidida com evidência | ✅ |
| Teste automatizado verde | ✅ 26 testes (1 pulado sem a amostra real) |
| Alt correto em 10 `.docx` **reais** e heterogêneos | ⚠️ **não atendido** |

**O corpus é sintético.** Os 10 arquivos foram escritos por mim
(`tools/make_corpus.py`), modelados nas estruturas OOXML que Word, Google Docs
e LibreOffice produzem — mas não são exports reais. Um extrator testado só
contra arquivos que o próprio autor escreveu tem o mesmo vício do dataset
sintético recusado em D2.

O que salva parcialmente: **um arquivo real** (export de Google Docs achado na
máquina) foi incluído por caminho externo em `AmostraRealTest`, e os dois
extratores concordaram nele. Esse arquivo derrubou três premissas do escopo
original antes de eu escrever uma linha de código:

1. A imagem estava em `word/header2.xml`, **não** em `document.xml`.
2. Usava `wp:anchor`, **não** `wp:inline`.
3. O `docPr` real vinha **sem o atributo `descr`** — `<wp:docPr id="1"
   name="image1.png"/>`.

Se um único arquivo real quebrou três suposições, dez arquivos reais quebram
mais. **A validação com exports reais dos três programas continua pendente** e
deve entrar na Slice 1 antes de a extração ser considerada pronta.

## Como reproduzir

```bash
cd spike && python tools/make_corpus.py src/test/resources/corpus && mvn test
```

Requer JDK 25. A amostra real só roda com
`-Dspike.amostraReal=/caminho/arquivo.docx`; sem a propriedade o teste se abstém.
Não há mais caminho de máquina embutido no código.
