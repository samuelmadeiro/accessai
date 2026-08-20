# Slice 3 — Outbox, retry com backoff, DLT e correlationId

> **RASCUNHO.** O `CONTRIBUTING.md` §1 diz que esta entrada é escrita com as
> palavras do autor. O registro factual está montado; as perguntas do contrato
> estão marcadas **PARA COMPLETAR**.

- **Estado:** `./mvnw verify` verde — 163 testes unitários e 11 E2E

---

## O que foi construído

| Peça | Arquivo |
|---|---|
| Tabelas | `V2__outbox_e_correlacao.sql` (`outbox_evento`, `evento_em_dlt`) |
| Outbox | `EventoDeOutbox`, `EventoDeOutboxRepository`, `PublicadorDeOutbox` |
| Escrita transacional | `RegistroDeAnalise` grava análise + binário + evento juntos |
| Correlação | `Correlacao`, `FiltroDeCorrelacao` |
| Política de entrega | `KafkaConfig` (backoff exponencial, DLT, não-retentáveis) |
| Ciclo da falha | `ConsumidorDaDlt` → `RegistroDeFalha` |

## Decisões que valem explicar

1. **Publica, espera a confirmação, marca.** A ordem inversa transformaria falha
   de rede em evento perdido em silêncio — exatamente o defeito que o outbox
   existe para eliminar.
2. **`FOR UPDATE SKIP LOCKED` na leitura de pendentes.** Com duas instâncias, as
   duas leriam o mesmo lote e publicariam o mesmo evento em duplicidade.
3. **O payload vai como bytes, sem reserializar.** O que está no banco e o que
   foi publicado não podem divergir quando o formato do evento mudar. Isso
   exigiu um segundo `KafkaTemplate` — e declarar o padrão à mão, porque o
   `@ConditionalOnMissingBean` do Boot desiste de criar o template assim que
   qualquer bean de `KafkaTemplate` aparece.
4. **Política de retry saiu do consumidor.** Mudar de 4 para 6 tentativas não
   pode exigir tocar em código de domínio. `ProcessadorDeAnalise` foi removido:
   a classificação permanente/transitória virou `addNotRetryableExceptions`.
5. **FALHOU acontece no consumidor da DLT.** Uma transição de estado, um lugar.
6. **O consumidor da DLT não relança.** Se ele falhasse e a mensagem voltasse, a
   DLT precisaria de uma DLT.
7. **A causa, não o wrapper.** O Spring Kafka põe `ListenerExecutionFailedException`
   no cabeçalho — nome que não diz nada. O `DLT_EXCEPTION_CAUSE_FQCN` é o que
   vale, e é o que vai para a tabela. Isso foi descoberto porque o teste falhou.
8. **`X-Correlation-ID` do cliente é validado, não saneado.** Valor com quebra
   de linha injeta linha falsa no log, e log falsificado é pior que log ausente.

## PARA COMPLETAR

**O que eu construí, com minhas palavras:** _(escrever)_

**Por que outbox e não Kafka transactions:** _(escrever — o ADR 0010 tem o
argumento; a resposta de entrevista é sua)_

**Qual alternativa eu descartei e por quê:** _(escrever — candidatas: Debezium,
`@TransactionalEventListener`, manter FALHOU dentro do consumidor)_

**O que eu ainda não sei defender numa entrevista:** _(escrever)_

## Dívida consciente que segue aberta

- **At-least-once, não exactly-once.** Duplicata é esperada e tratada por
  `evento_processado`.
- **`outbox_evento` cresce para sempre** — sem rotina de expurgo.
- **A DLT não tem reprocessador**: mensagem parada lá exige ação humana.
- **Sem métrica exportada.** Fila do outbox e contagem de DLT são consultas SQL,
  não gauges — observabilidade é a Slice 9.
- Contraste (1.4.3), autenticação e `owner_id` seguem pendentes.
