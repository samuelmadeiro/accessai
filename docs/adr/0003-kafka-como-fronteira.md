# ADR 0003 - Kafka como fronteira entre runtimes

- **Status:** aceita
- **Decisao original:** D3 de `docs/architecture/fase-0.md`

## Contexto

Preciso responder em entrevista: "por que Kafka e nao `@Async`, ou uma fila em
Postgres com `SELECT FOR UPDATE SKIP LOCKED`?".

## Decisao

Kafka fica, em modo KRaft.

## Argumento honesto

1. **Consumidor em outra linguagem.** Backend Java, ML Service Python. Fila em
   Postgres transformaria o schema do banco principal em contrato entre dois
   runtimes: toda migration viraria mudanca de API para o Python.
2. **Deploy independente.** Subir modelo novo nao pode exigir deploy do backend.
   O topico e a fronteira estavel; o evento versionado e o contrato.
3. **Replay e fan-out.** Modelo v2 reprocessa o historico a partir do offset 0,
   sem reupload. O AI Gateway da Slice 6 consome o mesmo topico com seu proprio
   offset.

## Alternativas consideradas

- **`@Async`:** descartado de saida - morre com a JVM e nao alcanca o Python.
- **Fila em Postgres:** perde replay e fan-out; acopla schema a dois runtimes.
- **Redis Streams:** a saida se o Kafka virar friccao maior que o valor.

## Consequencias

**Contra, dito antes de perguntarem:** throughput NAO e o argumento - este
projeto nao tem volume. Custa um broker no compose, complexidade operacional e
testes mais lentos (o E2E sobe um container de Kafka de verdade).

**Argumento removido por ser circular:** "fila em Postgres violaria a invariante
de que o ML Service nao acessa o banco principal". A invariante fui eu que
escrevi; um entrevistador derruba isso em dois segundos.
