# ADR 0006 - O que nao sera feito

- **Status:** aceita
- **Decisao original:** D6 de `docs/architecture/fase-0.md`

## Contexto

Lista de nao-features vale tanto quanto a de features: sem ela, um projeto solo
de nove slices vira nove projetos pela metade.

## Decisao

Explicitamente fora de escopo:

1. Qualquer formato alem de DOCX (nada de PDF, HTML, ODT).
2. Correcao automatica do documento - so recomendacao textual.
3. Modelo 2 (severidade contextual por ML) - ver ADR 0002.
4. Multi-tenancy organizacional, RBAC, convites, papeis.
5. Refresh token rotation, login social OAuth, MFA.
6. Deploy em cloud, Kubernetes, pipeline multi-ambiente, alta disponibilidade.
   O alvo e `docker compose up` local.
7. Fine-tuning de LLM, RAG sobre corpus externo, banco vetorial.
8. i18n do produto e das recomendacoes.
9. Analise de midia embutida (video, audio, legendas).
10. Benchmark de carga e tuning de performance.
11. Verificacao de contraste que exija renderizacao - so o computavel do XML.

## Consequencias

Cada item aqui e uma pergunta de entrevista respondida com "decidi nao fazer, e
esta e a razao" em vez de "nao deu tempo".
