# Slice 3 — Outbox, retry com backoff, DLT e correlationId

> **Procedência desta entrada.** O texto foi rascunhado em par com o Claude, a
> partir dos ADRs e do código, e **revisado e adotado por mim** — as posições
> aqui são as minhas. Registrado porque o `CONTRIBUTING.md` §1 pede a entrada
> com as minhas palavras, e omitir como ela foi escrita seria o mesmo tipo de
> silêncio que o §1 existe para impedir.

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

## As perguntas do contrato

> As quatro perguntas que o §1 exige: o que foi construído, por que dessa
> forma, qual alternativa foi descartada e por quê, e o que eu ainda não sei
> defender.

### O que eu construí

Fechei o buraco entre "o banco confirmou" e "o evento foi publicado".

Antes desta slice, `RegistroDeAnalise` gravava a análise e publicava no Kafka em
seguida. Duas operações, dois sistemas, nenhuma transação em volta: se o
processo morresse entre uma e outra, ficava uma análise no banco que ninguém
nunca ia processar — e sem nada no log dizendo isso. Agora análise, binário e
evento entram na **mesma transação**, numa tabela `outbox_evento`, e um worker
agendado publica de lá. A ordem é publica, **espera a confirmação do broker**,
depois marca `publicado_em`.

Do lado do consumo, a política de entrega saiu do domínio: backoff exponencial e
DLT ficam no `DefaultErrorHandler` do `KafkaConfig`, e falha permanente vai
direto para a DLT via `addNotRetryableExceptions`. A transição para `FALHOU`
mora num lugar só — o consumidor da DLT, depois de o retry ter se esgotado.

E o `correlationId` passou a nascer na borda HTTP e atravessar tudo: MDC, coluna,
payload do evento e cabeçalho do registro Kafka.

### Por que outbox e não Kafka transactions

Porque exactly-once entre Postgres e Kafka custa uma transação distribuída, e o
problema real não exige isso.

Kafka transactions pediriam coordenador transacional no broker, consumidores em
`read_committed` e uma camada de coordenação que eu teria que entender inteira
para defender. O outbox resolve o mesmo problema — não perder evento cujo dado
foi commitado — com uma tabela e um worker, dentro de uma transação de banco que
eu já tenho.

O que eu **não** ganho é exatidão: continua sendo **at-least-once**. Duplicata é
esperada, e é por isso que `evento_processado` existe. Trocar duplicata tratada
por complexidade distribuída seria pagar caro numa moeda errada.

A ordem "publica → confirma → marca" é a parte que costuma ser perguntada: o
inverso — marcar antes de confirmar — transformaria falha de rede em evento
perdido em silêncio, que é exatamente o defeito que o outbox existe para
eliminar. Publicar duas vezes é recuperável; não publicar não é.

### Qual alternativa eu descartei e por quê

| Alternativa | Por que não |
|---|---|
| **Publicar dentro da transação** | Troca "evento perdido" por "evento cedo demais": o consumidor lê antes do commit e não acha a linha. |
| **Kafka transactions** | Transação distribuída e coordenador no broker. Complexidade grande para projeto solo, e o outbox resolve o problema real. |
| **Debezium / CDC** | Tira o polling e adiciona Kafka Connect ao compose. O alvo é `docker compose up` local (ADR 0006). |
| **`@TransactionalEventListener(AFTER_COMMIT)`** | Continua sendo publicação pós-commit **em memória**: processo morre depois do commit e o evento some do mesmo jeito. Parece outbox e não é. |
| **Manter `FALHOU` dentro do consumidor** | Espalha política de entrega pelo domínio, e não havia como distinguir "vai tentar de novo" de "desistiu". |
| **Intervalo fixo de retry** | A falha típica é banco ou broker sob pressão; repetir na mesma cadência piora o que já está ruim. |

### O que eu ainda não sei defender numa entrevista

Candidatos objetivos — os buracos são reais, escolher quais são os meus é o que
falta:

1. **`FOR UPDATE SKIP LOCKED` com duas instâncias.** Sei o que ele evita (as
   duas lerem o mesmo lote), não sei explicar bem o comportamento sob contenção
   nem o que acontece com a ordem dos eventos quando dois workers competem.
2. **A ordem de entrega não é garantida entre partições.** O outbox publica na
   ordem em que lê, mas nada segura ordem global — e eu não escolhi chave de
   partição pensando nisso.
3. **`outbox_evento` cresce para sempre.** Sei que falta expurgo; a pergunta
   seguinte — "como você expurga sem apagar o que ainda não publicou?" — eu não
   ensaiei.
4. **Por que dois `KafkaTemplate`.** O payload vai como bytes sem reserializar,
   e isso exigiu declarar o template padrão à mão porque o
   `@ConditionalOnMissingBean` do Boot desiste assim que qualquer bean de
   `KafkaTemplate` aparece. Sei o que fiz; a explicação ainda soa como acidente
   de framework, não como decisão.
5. **A DLT não tem reprocessador.** Mensagem parada lá exige ação humana, e não
   sei defender o critério para reprocessar sem duplicar efeito.

## Dívida consciente que segue aberta

- **At-least-once, não exactly-once.** Duplicata é esperada e tratada por
  `evento_processado`.
- **`outbox_evento` cresce para sempre** — sem rotina de expurgo.
- **A DLT não tem reprocessador**: mensagem parada lá exige ação humana.
- **Sem métrica exportada.** Fila do outbox e contagem de DLT são consultas SQL,
  não gauges — observabilidade é a Slice 9.
- Contraste (1.4.3), autenticação e `owner_id` seguem pendentes.
