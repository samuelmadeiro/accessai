# ADR 0002 - Procedencia do dataset e corte do Modelo 2

- **Status:** PROPOSTA - aguarda decisao de Samuel (perguntas 3, 4 e 7 de `fase-0.md`)
- **Decisao original:** D2 de `docs/architecture/fase-0.md`

> Registrado como proposta, e nao como decisao aceita: nao se escreve ADR para
> decisao que ainda nao foi tomada. Vira `aceita` quando as perguntas abertas
> forem respondidas.

## Contexto

"Crie um dataset realista" e uma armadilha: dataset gerado por LLM e apresentado
como real destroi a credibilidade do projeto numa entrevista.

## Proposta

1. **Cortar o Modelo 2 (severidade contextual).** Nao existe corpus publico de
   "quao grave e este problema neste documento", o rotulo e subjetivo e o Rule
   Engine ja atribui severidade deterministicamente. Qualquer dataset sairia da
   mesma heuristica que o modelo deveria superar - circular por construcao.
2. **Manter o Modelo 1 (qualidade de texto alternativo)**, com tres classes:
   `GOOD` / `WEAK` / `INSUFFICIENT`. `MISSING` nao e classe: alt ausente e
   deteccao deterministica, e usar ML nisso violaria CONTRIBUTING.md secao 2.
3. **Fonte:** `alt` de HTML publico real (Common Crawl e/ou Wikimedia Commons).
4. **Rotulagem hibrida declarada:** LLM pre-rotula, humano revisa; reportar taxa
   de correcao e kappa de Cohen em 150 amostras.
5. **Volume minimo:** ~600 amostras, ~200 por classe.
6. **Baseline a bater:** classe majoritaria e heuristica (comprimento, nome de
   arquivo, "imagem de", igual ao texto vizinho). Metrica: macro-F1.

## Consequencias

**Assumidas e declaradas no model card.** O modelo nao ve a imagem: detecta
padrao linguistico de inadequacao, nao verifica se o alt descreve a imagem. Alt
bem escrito e completamente errado sai como `GOOD`.

**Domain shift.** Treino em `alt` de HTML, aplicacao em DOCX. Mitigacao: conjunto
de teste com ~100 alt texts de `.docx` publicos e o gap in-domain x
out-of-domain publicado.

**Risco aberto.** A coleta de 2026-08-19 achou 4 imagens em 9 documentos, todas
sem alt utilizavel: o corpus real ainda nao sustenta o Modelo 1.
