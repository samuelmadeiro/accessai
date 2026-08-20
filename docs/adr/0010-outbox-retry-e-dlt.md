# ADR 0010 - Outbox transacional, retry com backoff e DLT

- **Status:** aceita (Slice 3)
- **Substitui:** a nota "sem outbox" registrada como divida na Slice 1

## Contexto

Ate a Slice 2 o `POST /analyses` gravava a analise, commitava e SO ENTAO
publicava no Kafka. Duas coisas ruins moravam ai:

1. **Evento perdido.** Morrer entre o commit e a publicacao deixava a analise em
   RECEBIDA para sempre — gravada no banco e invisivel para o consumidor. Nao
   havia como descobrir isso sem varrer a tabela a mao.
2. **Falha sem politica.** Qualquer excecao no consumidor caia no
   `DefaultErrorHandler` padrao: dez tentativas em milissegundos, offset
   commitado, mensagem descartada. A Slice 1 mitigou marcando FALHOU dentro do
   proprio consumidor, o que colocava politica de entrega no meio do dominio.

## Decisao

**Outbox transacional.** Analise, binario e evento vao para o banco na mesma
transacao (`RegistroDeAnalise`). Um worker agendado (`PublicadorDeOutbox`) le a
tabela e publica. Ordem: publica, ESPERA o broker confirmar, depois marca
`publicado_em`.

**Retry com backoff exponencial e DLT**, configurados no `DefaultErrorHandler`
(`KafkaConfig`), nao em `try/catch` do consumidor. Falha permanente entra em
`addNotRetryableExceptions` e vai direto para a DLT.

**A transicao para FALHOU mora no consumidor da DLT** (`ConsumidorDaDlt` ->
`RegistroDeFalha`), num lugar so, depois de o retry ter se esgotado.

**correlationId nasce na borda HTTP** (`FiltroDeCorrelacao`), vai para o MDC,
para a coluna, para o payload e para o cabecalho do registro Kafka.

## Alternativas consideradas

| Alternativa | Por que nao |
|---|---|
| Publicar dentro da transacao | O consumidor pode ler o evento antes do commit e nao achar a linha. Troca "evento perdido" por "evento cedo demais". |
| Kafka transactions (exactly-once) | Exige transacao distribuida entre Postgres e Kafka, coordenador transacional no broker e consumidores em `read_committed`. Complexidade grande para um projeto solo, e o outbox ja resolve o problema real. |
| Debezium / CDC no lugar do worker | Tira o polling e adiciona Kafka Connect ao compose. O alvo e `docker compose up` local (ADR 0006). |
| `@TransactionalEventListener(AFTER_COMMIT)` | Continua sendo publicacao pos-commit em memoria: se o processo morre depois do commit, o evento some do mesmo jeito. |
| Manter FALHOU dentro do consumidor | Politica de entrega espalhada pelo dominio; e nao havia como distinguir "vai tentar de novo" de "desistiu". |
| Intervalo fixo de retry | A falha tipica e banco ou broker sob pressao; repetir na mesma cadencia piora o que ja esta ruim. |

## Consequencias

**Boas.** Nenhum evento se perde entre o commit e a publicacao. `outbox_evento`
com `tentativas` e `ultimo_erro` transforma "o broker esta fora" numa consulta.
`evento_em_dlt` guarda a causa original — o cabecalho do Spring Kafka traz
`ListenerExecutionFailedException`, que nao diz nada, entao o consumidor grava a
CAUSA.

**Ruins, e assumidas.**

- **Entrega at-least-once.** Morrer entre publicar e marcar republica o evento.
  E por isso que `evento_processado` existe desde a Slice 1: duplicata
  detectavel e melhor que evento perdido.
- **Latencia extra.** Ate 500 ms entre o `201` e o inicio do processamento,
  pelo intervalo do worker.
- **Polling.** Um `SELECT` por ciclo, mesmo sem trabalho. O indice parcial cobre
  so pendentes, mas o custo nao e zero.
- **A tabela cresce para sempre.** Nao ha limpeza de eventos publicados. Quando
  incomodar, entra uma rotina de expurgo — nao antes, por CONTRIBUTING.md secao 5
  (nao otimizar antes de medir).
- **A DLT nao tem reprocessador.** Mensagem parada la exige acao humana. Um
  endpoint de reprocessamento e trabalho de outra slice.
