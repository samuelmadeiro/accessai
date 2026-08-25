# ADR 0005 - Provider de LLM, custo e teto

- **Status:** PROPOSTA - aguarda decisao de Samuel (perguntas 5 e 6 de `fase-0.md`)
- **Decisao original:** D5 de `docs/architecture/fase-0.md`

> Proposta, nao decisao: depende de orcamento e de chave de API que sao do
> Samuel. Nenhuma linha de codigo de IA existe no repositorio.

## Proposta

- **Provider:** Anthropic Claude API, atras da interface `AiProvider`
  (CONTRIBUTING.md secao 5).
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

## Nota de implementacao (Slice 6) — o gateway existe, o provider nao

A Slice 6 foi construida inteira com o **`FakeAiProvider`**, que devolve
fixtures. Nenhuma chamada a modelo aconteceu, e nao ha chave configurada.

Isto nao contorna esta proposta: o proprio D5 prescreve o fake para CI, e o
criterio de pronto do §7 — guardrail testado — nao depende de modelo nenhum.
O que continua travado sao as perguntas 5 e 6 de `fase-0.md`: chave, aceite do
teto, e a escolha entre Haiku 4.5 e Sonnet 5. Essa escolha e de custo x
qualidade, e e do dono do orcamento.

**O que ja existe e nao precisa mudar quando o provider real entrar:**

- `AiProvider` — a interface que o §5 exige como unica porta para LLM
- `GuardrailDeFundamentacao` — recusa e filtro, nas duas pontas
- `ContadorDeGastoDeIa` — o teto deste ADR, em centavos, no Redis, falhando
  FECHADO quando nao da para conferir
- `GatewayDeIa` — a ordem guardrail, teto, provider, guardrail

**O que falta:** um `AnthropicAiProvider` implementando a interface, e o modelo
escolhido. Pelo `@ConditionalOnMissingBean`, o fake sai de cena assim que ele
existir como bean.

**A honestidade da coisa mora num campo.** Toda resposta declara
`procedencia: FIXTURE`, do provider ate o corpo HTTP e a coluna do banco. Sem
ele isto seria a "IA que e template string" que o §1 proibe.

## Consequencias

Custo real depende do volume, que hoje e desconhecido. O numero acima e
estimativa de tokens, nao medicao.
