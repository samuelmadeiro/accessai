# Corpus de documentos públicos

Entregável da condição **C-3** de `docs/architecture/fase-0.md`.

## O que está versionado

| Caminho | No git? | Por quê |
|---|---|---|
| `sources.json` | sim | lista-semente de URLs; o script não faz crawling |
| `corpus/manifest.json` | sim | procedência, licença, SHA-256, estrutura de cada arquivo |
| `corpus/raw/` | **não** | binários — ver abaixo |

**Os binários não entram no repositório.** Documento público real contém dado
pessoal (nome, CPF, matrícula). Comitar o arquivo resolveria reprodutibilidade e
criaria um problema de privacidade permanente no histórico. O manifesto guarda o
SHA-256 de cada arquivo, então a reprodutibilidade fica preservada sem publicar
dado de terceiro.

Reconstruir:

```bash
python scripts/fetch-corpus.py
```

## Licença

`licenca: "nao declarada"` significa que a página de origem não publicou termo de
licença. **Isso não é domínio público.** Material de órgão público brasileiro
varia por órgão, e o campo é preenchido por arquivo, nunca por lote. Enquanto
estiver "não declarada", o arquivo serve para teste local e não é
redistribuível.

## Coleta de 2026-08-19

13 URLs na semente, **9 utilizáveis**, 4 descartadas:

| Falha | Quantidade | Detalhe |
|---|---|---|
| HTTP 404 | 2 | link publicado que já não existe |
| HTTP 200 com corpo HTML | 2 | URL termina em `.docx`, `Content-Type: text/html`, corpo `<!DOCTYPE html>` |

O segundo caso é a razão de o script validar o tipo **real** (assinatura `PK` +
`[Content_Types].xml` + `word/document.xml`) em vez de confiar na extensão,
conforme `CLAUDE.md` §5. Status 200 e extensão `.docx` não provam nada.

## O que os 9 documentos mostraram

- **4 imagens em 9 documentos.** Editais e formulários são quase todo texto.
- **Nenhuma com texto alternativo utilizável:** 3 ausentes, 1 vazia, **zero
  preenchidas**.
- **Metade das imagens estava fora de `word/document.xml`** — uma em
  `header1.xml`, uma em `footer1.xml`.
- Um documento trouxe `word/commentsDocument.xml`, e não `word/comments.xml`.

## Pendências

1. Corpus enviesado para texto. Ampliar com material ilustrado (cartilhas,
   manuais, relatórios) para exercitar a regra de alt text de verdade.
2. Preencher `licenca` arquivo a arquivo consultando a página de origem.
3. Nenhum alt text preenchido no corpus real — ver o impacto em D2 registrado no
   relatório da coleta.
