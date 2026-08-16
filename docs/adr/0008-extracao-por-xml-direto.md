# ADR 0008 - Extracao de alt text por parsing XML direto, sem Apache POI

- **Status:** aceita
- **Origem:** spike da condicao C-2, resultado em `spike/RESULTADO.md`

## Contexto

Duas formas de ler alt text de um `.docx`: Apache POI (XWPF) ou abrir o zip e
varrer o XML com StAX. O spike implementou as duas e mediu.

## Decisao

Parsing XML direto para alt text (e, pela mesma razao, para a cascata de
contraste da Slice 2). O POI segue sendo a escolha obvia para texto corrido,
paragrafos e tabelas - a decisao e escopada.

## Evidencia

- **Caso decisivo:** desenho dentro de `mc:AlternateContent`. O XmlBeans do POI
  so vincula elementos previstos no schema de `w:r`, entao `CTR.getDrawingList()`
  devolve **zero imagens**. Nao e alt errado: e imagem nenhuma - falso negativo
  silencioso, que num produto de score significa afirmar conformidade
  inexistente.
- **Codigo:** empate. 112 linhas (POI) contra 124 (XML direto), descontando
  comentario e linha em branco. A premissa de que o POI economizaria codigo
  estava errada: `getAllPictures()` devolve bytes, nao `descr`.
- **Dependencia:** 13 jars e ~18 MB contra zero - `java.util.zip` e
  `javax.xml.stream` sao do JDK.
- **O POI nao evita XML cru:** VML (`v:shape/@alt`) nao tem acessor e obriga a
  reler `xmlText()` na unha.

## Consequencias

**Boas.** Controle total sobre o que conta como imagem - foi o que permitiu
corrigir o falso positivo de caixa de texto (`wp:docPr` existe em qualquer
desenho; so vira imagem com `pic:pic`, `a:blip` ou `v:imagedata` na subarvore).

**Ruins.** Parser proprio e codigo que eu mantenho: cada estrutura OOXML nova
e uma linha a mais aqui. `ExtratorPoiTest.naoEnxergaAlternateContent` trava a
limitacao do POI por escrito e falha se uma versao futura corrigir - momento de
reavaliar esta decisao.

**Pendencia herdada do spike:** o corpus dos dois extratores e sintetico. A
validacao com exports reais de Word, Google Docs e LibreOffice continua aberta.
