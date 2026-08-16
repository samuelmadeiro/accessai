# ADR 0007 - A regua e o WCAG2ICT, nao a WCAG direta

- **Status:** aceita
- **Decisao original:** condicao C-1 de `docs/architecture/fase-0.md`

## Contexto

A WCAG foi escrita para conteudo web. Citar 1.1.1 num `.docx` sem justificativa e
analogia - e analogia nao documentada e exatamente o "inventar criterio" que
CLAUDE.md secao 6 proibe.

## Decisao

A base normativa de `docs/wcag/criteria.json` e o **WCAG2ICT** (*Guidance on
Applying WCAG 2 to Non-Web Information and Communications Technologies*, W3C
Group Note de 2025-12-11, cobrindo WCAG 2.0/2.1/2.2 niveis A e AA).

Cada criterio registra `aplicabilidadeIct` (`direta` | `com_substituicao` |
`inaplicavel`), as substituicoes de termo usadas e a nota que justifica.
Criterio `inaplicavel` **nao pode** gerar violacao - no maximo recomendacao.
Isso e verificado em codigo: `CatalogoWcag.Criterio.geraViolacao()`, com teste.

Regra que continua valendo: a regra cita apenas o identificador; nivel e titulo
vem da tabela. Criterio inexistente derruba a subida da aplicacao.

## Consequencias

**Disclaimer obrigatorio e literal**, no README e em qualquer relatorio: o
WCAG2ICT e uma Group Note informativa do W3C, nao e norma e nao estabelece
requisitos de conformidade. Omitir isso seria vender rigor que o documento nao
tem.

**Ganho:** dito o disclaimer, a regua nao e minha - e do W3C, versionada e
citavel linha a linha. Regulacao que a consome: EN 301 549, Section 508 e, no
Brasil, a LBI (Lei 13.146/2015).

**Custo:** cada criterio novo exige leitura do WCAG2ICT antes de entrar na
tabela. E o ponto.
