# ADR 0001 - Formato do documento no MVP: DOCX

- **Status:** aceita (aprovada por Samuel em 2026-08-19)
- **Decisao original:** D1 de `docs/architecture/fase-0.md`

## Contexto

O MVP precisa de exatamente um formato. A escolha muda parser, regras, dataset e
a pergunta que vai aparecer na entrevista: "por que nao usar a ferramenta que ja
existe?".

## Decisao

DOCX.

## Alternativas consideradas

| Opcao | Concorrente pronto | Por que nao |
|---|---|---|
| HTML (jsoup) | axe-core, Pa11y, Lighthouse | O argumento contra o axe e fraco: bastaria rodar Playwright + axe. |
| PDF (PDFBox) | veraPDF | Tagged PDF e raro; documento sem tags pontua ~0 sempre, e o produto vira detector de "sem tags". |
| DOCX | Verificador de Acessibilidade do Word | Escolhido: o concorrente nao tem API, nao roda em lote, nao da score, nao cita WCAG e nao prioriza. |

## Consequencias

**Boas.** O Rule Engine deixa de ser wrapper: a regra de heading formatado a mao
(negrito e fonte maior sem estilo de Heading) so existe em DOCX e nenhuma
biblioteca faz. A categoria "Visual 20%" fica computavel sem renderizar nada.

**Ruins.** A cascata de cor (formatacao direta, estilo de caractere, estilo de
paragrafo, `docDefaults`, tema, com `themeTint`/`themeShade`) e o maior sumidouro
de tempo do projeto - por isso contraste ficou para a Slice 2, nunca a 1.
Publico menor: menos gente se importa com DOCX do que com HTML.

**Custo aceito.** Parte do acesso desce ao nivel dos schemas OOXML.
