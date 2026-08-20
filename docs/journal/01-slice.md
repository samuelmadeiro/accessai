# Slice 1 — upload → Kafka → uma regra → Postgres → `GET /analyses/{id}`

> **RASCUNHO.** O `CONTRIBUTING.md` §1 diz que esta entrada sou eu quem escreve, com
> minhas palavras — o registro abaixo é factual, montado a partir do que ficou
> no repositório, para eu não ter que reconstruir de memória. As três perguntas
> do contrato (o quê, por quê, o que foi descartado) estão marcadas
> **PARA COMPLETAR**. Enquanto estiverem em branco, a slice não está pronta.

- **Período:** 2026-08-19 (implementação) e 2026-08-19 (correções pós-review)
- **Estado:** funcional, `./mvnw verify` verde com Docker

---

## O que foi construído

| Peça | Arquivo |
|---|---|
| Borda HTTP | `AnaliseController` (`POST /analyses`, `GET /analyses/{id}`) |
| Validação de tipo real | `ValidadorDeDocx` (assinatura zip + partes OOXML + limites de zip bomb) |
| Persistência | `Analise`, `DocumentoBinario`, `Problema`, `EventoProcessado` + `V1__esquema_de_analise.sql` |
| Evento | `AnaliseSolicitadaV1`, `ProdutorDeAnalise`, `ConsumidorDeAnalise` |
| Processamento | `ProcessadorDeAnalise` (política de falha) + `ExecucaoDaAnalise` (transação) + `RegistroDeFalha` |
| Extração | `ExtratorDeImagens` (StAX, sem POI) |
| Regras | `MotorDeRegras`, `RegraImagemSemTextoAlternativo`, `CatalogoWcag` |

Uma regra: `IMAGEM_SEM_TEXTO_ALTERNATIVO`, WCAG 1.1.1 nível A, com
aplicabilidade a documento não-web resolvida via WCAG2ICT (ADR 0007).

## Números

- 46 testes unitários + 7 E2E (Testcontainers: Postgres e Kafka reais)
- 1 regra, 1 critério na tabela WCAG
- Corpus real coletado: 13 URLs semente, 9 utilizáveis, 4 documentos com imagem,
  **zero alt text preenchido**

## Decisões registradas como ADR

- ADR 0001 — DOCX como formato do MVP
- ADR 0003 — Kafka como fronteira entre runtimes
- ADR 0004 — autenticação e isolamento por linha (ainda não implementado)
- ADR 0006 — fora de escopo
- ADR 0007 — WCAG2ICT como base normativa
- ADR 0008 — extração por XML direto, sem Apache POI

ADR 0002 e 0005 estão como **proposta**: dependem de resposta minha.

## O que o code review encontrou (e o que mudou por causa dele)

1. **Falso positivo no extrator.** `wp:docPr` existe em qualquer desenho —
   caixa de texto, autoforma, gráfico. Tudo virava "imagem sem alt". Agora um
   desenho só conta como imagem com `pic:pic`, `a:blip` ou `v:imagedata` na
   subárvore.
2. **Falha silenciosa no consumidor.** Qualquer exceção derrubava a transação e
   a análise voltava para `RECEBIDA` para sempre, sem log de erro. Agora falha
   permanente vira `FALHOU` numa transação nova (`REQUIRES_NEW`), e falha
   transitória sobe para o Kafka reentregar.
3. **Extrator de produção sem teste.** Os 26 testes verdes eram do `spike/`, que
   já tinha divergido do backend. Agora há suíte própria.
4. **Entidade JPA cruzando para a API.** `VisaoDaAnalise` carregava `Analise` e
   `Problema` até o pacote `api`. Virou record puro convertido dentro da
   transação.
5. **`XMLInputFactory` compartilhada** em bean singleton (não é thread-safe).
   Virou `ThreadLocal`.
6. **Manifesto do corpus reescrito do zero** a cada execução do script, e o
   SHA-256 gravado em vez de conferido. Agora o manifesto é mesclado e a
   divergência de hash para a coleta com código de saída 2.

## PARA COMPLETAR — o quê, por quê, o que foi descartado

**O que eu construí, com minhas palavras:**

_(escrever)_

**Por que desta forma e não de outra:**

_(escrever — sugestões do que vale explicar: por que o evento não carrega os
bytes; por que a publicação acontece depois do commit e o que isso custa; por
que a validação lê a assinatura do zip em vez da extensão; por que `descr=""`
não é defeito)_

**Qual alternativa eu descartei e por quê:**

_(escrever — candidatas: Apache POI na extração; `@Async` no lugar do Kafka;
outbox já na Slice 1; guardar o binário fora do Postgres desde já)_

**O que eu ainda não sei defender numa entrevista:**

_(escrever — este campo não está no contrato, mas é o mais útil dos quatro)_

## Dívida consciente que segue aberta

- Sem outbox: se o processo morrer entre o commit e a publicação, a análise fica
  em `RECEBIDA`. Slice 3.
- Sem retry com backoff nem DLT. A política de falha atual é o mínimo. Slice 3.
- Sem score. Slice 2.
- Sem autenticação e sem `owner_id`. ADR 0004.
- Binário em `bytea` no banco principal — não sobrevive à Slice 5.
- Fixtures e corpus do extrator são sintéticos. Validação com exports reais de
  Word, Google Docs e LibreOffice continua pendente.
