# ADR 0005 - Provider de LLM, custo e teto

- **Status:** PROPOSTA - aguarda decisao de Samuel (perguntas 5 e 6 de `fase-0.md`)
- **Decisao original:** D5 de `docs/architecture/fase-0.md`

> Proposta, nao decisao: depende de orcamento e de chave de API que sao do
> Samuel. Nenhuma linha de codigo de IA existe no repositorio.

## Proposta

- **Provider:** Anthropic Claude API, atras da interface `AiProvider`
  (CLAUDE.md secao 5).
- **Modelo:** Haiku 4.5 para recomendacoes (explicar resultado ja calculado);
  Sonnet 5 para o copilot da Slice 7, se ele existir.
- **Custo por analise** (entrada ~4.000 tokens, saida ~1.200): ~US$ 0,010 no
  Haiku 4.5, ~US$ 0,030 no Sonnet 5.
- **Teto:** US$ 10/mes (~1.000 analises no Haiku), com contador incremental no
  Redis alimentado pelo campo `usage` de cada resposta.
- **Ao bater o teto:** nada quebra. Rule Engine e ML sao locais e gratuitos; so
  a secao de recomendacoes responde `AI_BUDGET_EXHAUSTED`. A IA e camada de
  enriquecimento, nunca caminho critico.
- **Testes sem gastar:** `FakeAiProvider` com fixtures; teste contra a API real
  marcado `@Tag("live")` e fora do build padrao.

## Ressalva medida

O prefixo minimo cacheavel e maior no Haiku 4.5 do que no Sonnet 5, e abaixo do
limite a API nao devolve erro: simplesmente nao cacheia. Instrumentar
`usage.cache_read_input_tokens` como metrica desde o primeiro dia - o lugar de
descobrir que o cache nao funciona e o dashboard, nao a fatura.

## Consequencias

Custo real depende do volume, que hoje e desconhecido. O numero acima e
estimativa de tokens, nao medicao.
