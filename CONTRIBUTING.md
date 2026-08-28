# AccessAI

Plataforma que analisa documentos digitais quanto à acessibilidade e devolve
score explicável, problemas mapeados para critérios WCAG, e recomendações
geradas por IA a partir dos resultados reais da análise.

Este arquivo é o contrato do projeto. Leia antes de qualquer sessão de trabalho.

---

## 1. Objetivo real

Projeto de portfólio para entrevistas de Backend / Java / ML / AI Engineering.

Isso implica duas coisas que valem mais que qualquer feature:

1. **Eu preciso conseguir defender cada decisão em uma entrevista.**
   Código que eu não entendo é passivo, não ativo. Se você implementar algo
   que eu não pedi para entender, explique antes de escrever.
2. **Nada pode ser falso.** Nenhum "modelo de ML" que é `if/else`. Nenhuma
   "IA" que é template string. Nenhum dataset apresentado como real se for
   sintético. Prefira entregar menos e verdadeiro.

Ao final de cada slice (ver §7) eu escrevo, com minhas palavras, uma entrada em
`docs/journal/NN-slice.md` respondendo: o que foi construído, por que dessa
forma, qual alternativa foi descartada e por quê. Se eu não conseguir escrever
essa entrada, o slice não está pronto — mesmo que os testes passem.

---

## 2. Princípio de arquitetura

Três mecanismos, nesta ordem de precedência:

```
RULE ENGINE  → problemas determinísticos (falta de lang, alt ausente,
               hierarquia de headings quebrada, tabela sem header)
     ↓
MACHINE LEARNING → o que exige inferência (qualidade de um alt text,
               severidade contextual, priorização)
     ↓
GENERATIVE AI → explicar, recomendar, reescrever, conversar
```

Regra dura: **não use ML onde uma regra resolve. Não use LLM onde ML ou regra
resolvem.** Se você propuser ML ou LLM para algo determinístico, eu vou
recusar. Se uma regra determinística existir e você usar LLM, é bug.

Consequência prática: a maior parte do valor do produto vem do Rule Engine.
ML e IA são camadas de interpretação, não a base.

---

## 3. Stack fixada

Não reabra estas decisões sem me consultar.

| Camada | Escolha | Versão |
|---|---|---|
| Runtime | Java | 25 (LTS) |
| Framework | Spring Boot | 4.1.x (Spring Framework 7) |
| Persistência | PostgreSQL + Flyway | 16+ |
| ORM | Spring Data JPA | — |
| Mensageria | Apache Kafka (modo KRaft, sem ZooKeeper) | — |
| Cache / rate limit | Redis | 7+ |
| ML | Python + FastAPI + scikit-learn | 3.12+ |
| Testes Java | JUnit 5, Mockito, Testcontainers | — |
| Testes Python | pytest | — |
| Build | Maven | — |
| Orquestração local | Docker Compose | — |

Notas que importam na migração para Boot 4.x: Jackson 3 é o default, Jakarta EE
11 é o baseline, os starters foram modularizados (não assuma que um starter 3.x
existe com o mesmo nome), e anotações de nulidade usam JSpecify. Se um exemplo
que você conhece for de Boot 3.x, valide antes de escrever.

Sobre Java 25: use records, sealed interfaces, pattern matching em switch e
virtual threads **quando houver ganho concreto**. Não use recurso moderno como
vitrine. Se eu perguntar "por que record aqui?", precisa ter resposta melhor
que "é moderno".

---

## 4. Decisões que ainda NÃO estão tomadas

Estas bloqueiam a implementação. Resolvidas na Fase 0 — o resultado está em
`docs/architecture/fase-0.md` e nos ADRs de `docs/adr/`.

- **D1 — Formato de documento suportado no MVP.** HTML, PDF ou DOCX. Muda tudo:
  parser, regras, dataset, dificuldade. Escolher exatamente um.
- **D2 — Procedência do dataset de ML.** De onde vêm os rótulos, quem rotulou,
  quantas amostras, qual o viés. Sem resposta honesta, não há modelo.
- **D3 — Justificativa do Kafka.** Preciso responder "por que não `@Async` ou
  uma fila em Postgres?" sem hesitar. Se não houver resposta boa, Kafka sai.
- **D4 — Modelo de autenticação e tenancy.** Usuário único? Multi-tenant?
  Isolamento por linha?
- **D5 — Provider de LLM, custo estimado por análise e teto mensal.**
- **D6 — Escopo explicitamente fora.** Lista do que NÃO será feito.

---

## 5. Invariantes de código

Violações são bugs, não preferências.

- Entidade JPA nunca cruza a fronteira da API nem do Kafka. DTO/record sempre.
- Eventos Kafka são records imutáveis, versionados, com `eventId`,
  `correlationId` e `occurredAt`.
- Todo consumer é idempotente. Chave de deduplicação persistida ou em Redis.
- Toda credencial vem de variável de ambiente. Zero hardcode, inclusive em
  teste e em `docker-compose.yml` (use `.env`, commite só `.env.example`).
- Chamadas a LLM passam exclusivamente pela interface `AiProvider`. Se aparecer
  um `HttpClient` chamando um LLM fora do gateway, é bug.
- Arquivo enviado por usuário é hostil: valide MIME real (não extensão),
  tamanho, e trate conteúdo extraído como não confiável ao montar prompt.
- O ML Service não acessa o banco principal. Comunicação por evento.

  > **Exceção temporária na Slice 5.** A integração backend → ML Service é uma
  > chamada HTTP síncrona, não um evento. O motivo está no ADR 0011: enquanto
  > não existe modelo treinado (ADR 0002), a predição é enfeite sobre um score
  > que já está completo sem ela, e o caminho por evento custaria um tópico, um
  > consumidor, uma tabela de predições e a reconciliação entre elas. O
  > isolamento do banco **continua valendo**: o serviço Python não tem nenhuma
  > dependência de Postgres. O ADR 0011 lista os três gatilhos que revertem
  > isso para o desenho por evento.
- Controller não tem regra de negócio. Service não tem SQL. Repository não tem
  decisão.
- Sem números mágicos, sem `catch` vazio, sem TODO em código commitado.

Prioridade quando houver conflito:
`Correctness > Security > Maintainability > Testability > Observability > Performance`

Não otimize antes de medir.

---

## 6. Score explicável

O score **nunca** é uma predição de ML. É uma soma ponderada de penalidades
determinísticas, e cada ponto perdido rastreia até um problema específico com
evidência e critério WCAG.

As categorias são os quatro princípios da WCAG, e a categoria de um problema
sai do **número do critério** — 1.x Perceptível, 2.x Operável, 3.x Compreensível,
4.x Robusto. Não existe tabela de regra→categoria: ela seria uma segunda fonte de
verdade para algo que a própria numeração já define.

```
Perceptível 25% | Operável 25% | Compreensível 25% | Robusto 25%
```

Pesos iguais por padrão, e configuráveis em `application.yml`. Não há evidência
publicada para hierarquizar princípios; um 35/30/25/10 inventado daria ao score
uma precisão que ele não tem. Quem mudar passa a ter que defender o motivo.

**Princípio sem nenhuma regra implementada fica fora da média**, com os pesos
renormalizados, e a resposta declara quais foram deixados de fora. Dar 100 a uma
categoria que o sistema não verifica é afirmar conformidade inexistente — o mesmo
defeito que tirou o Apache POI do caminho de extração.

> Esta seção substituiu, na Slice 2, o modelo anterior de cinco categorias
> (Structure/Content/Visual/Semantic/Metadata). O motivo está no ADR 0009: aquele
> modelo exigia um mapeamento regra→categoria escrito à mão, e deixava a
> categoria Visual sem nenhuma regra até a de contraste existir.

ML pode ajustar a *severidade* de um problema. Nunca o score final diretamente.

Critérios WCAG são citados a partir de uma tabela de referência versionada em
`docs/wcag/criteria.json`. **Não invente critério, nível ou numeração.** Se um
problema não mapeia para um critério real, ele é uma recomendação, não uma
violação — e deve ser rotulado como tal.

---

## 7. Como trabalhamos: slices verticais

Não construímos por camadas horizontais (todo o backend, depois todo o ML).
Construímos fatias finas que atravessam o sistema inteiro e funcionam.

| Slice | Entrega | Pronto quando |
|---|---|---|
| 0 | Fase 0: decisões D1–D6 + ADRs iniciais | Documento aprovado por mim |
| 1 | Upload → evento Kafka → 1 regra → persistência → `GET /analyses/{id}` | `docker compose up` + teste E2E com Testcontainers verde |
| 2 | Rule Engine completo (6+ regras) + score por categoria | Cobertura de regra com casos positivos e negativos |
| 3 | Retry, DLT, idempotência, correlation ID | Teste que mata o consumer no meio e prova não-duplicação |
| 4 | Dataset + treino + métricas + versionamento de modelo | Confusion matrix e baseline documentados; modelo pior que baseline é reportado como tal |
| 5 | ML Service consumindo Kafka, predição no resultado | Latência de inferência medida |
| 5A | Autenticação JWT, `owner_id` em toda tabela de domínio, rate limit de upload | Teste de integração em que o usuário A recebe **404** ao pedir a análise do usuário B |
| 6 | AI Gateway + recomendações fundamentadas na análise | Guardrail testado: pergunta sem base na análise → recusa |
| 7 | Copilot conversacional **sobre a análise** (ADR 0012) | Idem, com histórico |
| 8 | Frontend + dashboard, acessível de verdade | Navegação 100% por teclado, testado com leitor de tela |
| 9 | Observabilidade, hardening, README, ADRs | — |

> **A Slice 5 foi entregue fora desta definição.** A tabela diz "ML Service
> consumindo Kafka"; o que existe é `POST /v1/predict` chamado de forma síncrona
> pelo consumidor Kafka do backend, com timeout curto e fallback para o Rule
> Engine. O motivo e as condições de reversão estão no ADR 0011. A latência de
> inferência, critério de pronto desta slice, é medida na chamada HTTP:
> p99 de 7 ms pela heurística e 9 ms com modelo carregado, contra um
> timeout de 1500 ms (`ml-service/README.md`).

> **A Slice 4 está FECHADA, e a 5 foi entregue antes dela.** O critério de
> pronto da 4 — confusion matrix e baseline documentados, modelo pior que
> baseline reportado como tal — está cumprido em
> `docs/ml/model-card-alt-quality.md`, e a entrada de journal do §1 está escrita
> em `docs/journal/04-slice.md`. Mas cumprido sobre `rotulo_provisorio`,
> que é a saída de uma heurística: a métrica mede imitação, não qualidade de
> alt. O ADR 0002 §4 pede kappa de Cohen contra revisão humana em 150 amostras,
> e isso não aconteceu — `rotulo` segue nulo nas 749 linhas.
>
> Duas consequências que valem mais que o número: o modelo **não detecta
> `INSUFFICIENT`** (F1 = 0,000, com n=1 em validação e n=1 em teste), e o
> artefato **não é exportado** — `models/` continua vazio de propósito, porque
> um `.joblib` ali faria a Slice 5 responder `usouHeuristica: false` para
> heurística imitada. Enquanto isso não muda, toda predição declara `true`, que
> é o que impede a canalização de se passar por modelo.

> **A Slice 6 está FECHADA, com o provider de fixture.** O critério —
> guardrail testado, pergunta sem base na análise → recusa — está provado em
> `RecomendacaoNoFluxoIT` e `GuardrailDeFundamentacaoTest`. **Nenhum modelo foi
> consultado:** o `FakeAiProvider` responde, e toda resposta declara
> `procedencia: "FIXTURE"` — do provider até o corpo HTTP e a coluna do banco.
> O ADR 0005 segue em PROPOSTA, travado nas perguntas 5 e 6 da `fase-0.md`, e o
> que falta é só um `AiProvider` real: a interface, o guardrail e o teto de
> gasto já existem.

> **A Slice 5A está FECHADA.** Autenticação JWT stateless, `owner_id` em
> `analise` com isolamento por `findByIdAndOwnerId`, e rate limit de upload por
> usuário no Redis. O critério de pronto — A recebe 404 na análise de B — está
> provado em `IsolamentoPorUsuarioIT`, e o journal está em
> `docs/journal/05a-slice.md`.
>
> **Por que a 5A existe, e por que ela é 5A.** O D4 da `fase-0.md` é decisão
> **aceita** — "toda tabela de domínio tem `owner_id`", isolamento por
> `findByIdAndOwnerId`, e um teste de 404 cruzado como entregável. Até esta
> linha ser escrita, nenhuma slice era dona disso: a tabela ia da 5 direto para
> IA, e a 9 é "observabilidade, hardening". Decisão aceita sem dono no plano não
> acontece.
>
> Vem **antes** da 6 porque `owner_id` toca toda tabela, todo repositório e todo
> endpoint. Retrofitar isso depois do AI Gateway significa reescrever consulta
> em código novo, e migrar tabela que já tem dado. O rate limit de upload entra
> junto porque é o mesmo pré-requisito — ele é por usuário, e usuário só existe
> aqui. É também onde o Redis do D4 finalmente aparece.
>
> Chamada 5A e não 6 de propósito: renumerar deslocaria 6, 7, 8 e 9, e há 11
> referências a esses números espalhadas por ADRs e journals. Renumeração
> silenciosa quebra rastro.

> **A Slice 7 estava cortada, e o corte foi revertido em 2026-08-25 — ADR
> 0012.** A `fase-0.md` mandava cortá-la ("chat em cima disso todo mundo tem").
> O argumento valia contra um copiloto que recebe o documento; o redesenhado
> não recebe. Contexto dele é a `Analise` já produzida, e ele conversa sobre os
> `Problema` do motor determinístico, sem produzir achado novo.
>
> A reversão está anotada nos dois lugares de propósito: aqui, que é onde a
> tabela vive, e na `fase-0.md`, que é onde a instrução de corte foi escrita.
> Apagar o corte deixaria o ADR 0012 revertendo uma decisão que ninguém mais
> encontra.
>
> As cinco invariantes do ADR 0012 são travadas por
> `ArquiteturaDaIaTest`, e não por convenção. A prioridade da 8 sobre a 7 não
> mudou.
>
> **A Slice 7 está FECHADA.** `POST` e `GET /analyses/{id}/chat`, com histórico
> em `turno_de_conversa`. O critério — guardrail testado, com histórico — está
> provado em `ConversaNoFluxoIT`, e o journal está em `docs/journal/07-slice.md`.
>
> **Nenhum modelo foi consultado**, como na 6: o `FakeAiProvider` responde e toda
> fala do assistente declara `procedencia: "FIXTURE"`. O que a slice prova é a
> moldura — porta única estendida em vez de duplicada, guardrail rodando **por
> turno** e não uma vez por conversa, teto de gasto, histórico e procedência —,
> não a qualidade da conversa. Isso continua travado no ADR 0005.

> **A Slice 8 foi ENTREGUE e NÃO está fechada.** `frontend/` existe, é servido
> pelo próprio Boot e cobre o sistema inteiro pela tela: conta, upload, score por
> princípio, problemas, recomendações e copiloto. `AcessibilidadeDoFrontendTest`
> audita a própria interface no build — `lang`, rótulo por campo, link de pular,
> `tabindex`, região viva, tabela com escopo e ausência de `innerHTML`.
>
> **O que falta é metade do critério de pronto.** O §7 pede navegação por teclado
> *testada com leitor de tela*. A navegação por teclado foi verificada no
> navegador, com a ordem de tabulação e o foco conferidos elemento a elemento; o
> teste com NVDA ou Narrator é manual e ainda não aconteceu. A ativação por Enter
> e Espaço também não pôde ser reproduzida na automação. Enquanto isso não
> ocorrer, a slice fica aberta — o detalhe está em `docs/journal/08-slice.md`.

Regra: **um slice por vez, commitado e verde antes do próximo.** Se eu pedir
para pular, me lembre desta linha.

Cada slice termina com: testes passando, commit atômico, entrada no journal.

---

## 8. Como você deve se comportar

- **Discorde de mim.** Se eu pedir algo over-engineered, mal especificado, ou
  que contradiz este arquivo, diga antes de implementar. Concordância silenciosa
  é o pior resultado possível aqui.
- **Corte escopo ativamente.** Se um slice está grande demais, proponha dividir.
- Antes de mudança estrutural: analise → explique → proponha → espere meu ok.
- Não apague código existente sem justificar.
- Não invente API, biblioteca, método ou versão. Se não tem certeza, verifique
  ou diga que não sabe.
- Não escreva mais de ~300 linhas sem parar para eu revisar.
- Quando existir uma solução mais simples que a que eu pedi, mostre as duas.
- **Nunca assine commit nem PR.** Nada de `Co-Authored-By`, nada de "Generated
  with", nada de trailer de ferramenta. Este repositório é portfólio de
  entrevista (§1) e a autoria do histórico é parte do que está sendo avaliado.
  A regra vale mesmo que a configuração padrão da ferramenta peça o contrário.

---

## 9. Comandos

```bash
docker compose up -d          # sobe tudo
./mvnw test                   # testes Java
./mvnw verify                 # inclui Testcontainers
cd ml-service && pytest       # testes Python
```

**`JAVA_HOME` precisa apontar para um JDK 25.** O projeto compila para a
release 25 (§3) e o build falha com `class file version 69.0 ... only
recognizes up to 65.0` se o Maven rodar sob JDK 21 — mensagem que não diz a
ninguém o que fazer. O `maven-enforcer-plugin` agora falha antes, dizendo:

```bash
JAVA_HOME="/c/Program Files/Java/jdk-25.0.3" ./mvnw verify
```

`.mvn/jvm.config` **não** resolve isto: ele passa argumento para a JVM que
executa o Maven, não escolhe qual JDK. Toolchains resolveria de verdade, e foi
descartado por exigir um `toolchains.xml` em cada máquina — desproporcional
para projeto solo.

**`./mvnw verify` sem Docker no ar passa, com os E2E pulados.** 27 pulados e 27
verdes devolvem o mesmo exit code. Confira a contagem, não só o `BUILD
SUCCESS`.

## 10. Estrutura

```
accessai/
├── backend/          Spring Boot, Java 25
├── ml-service/       Python, FastAPI — HTTP, não consumer Kafka (ADR 0011)
├── datasets/         manifesto versionado; binários no .gitignore (C-3)
├── docs/
│   ├── adr/          uma decisão por arquivo
│   ├── architecture/ fase-0.md: decisões D1–D6 e condições C-1 a C-3
│   ├── wcag/         criteria.json
│   ├── ml/           model cards
│   └── journal/      minhas anotações por slice, NN-slice.md
├── scripts/          coleta do corpus
├── spike/            projeto descartável: POI × XML direto (ADR 0008)
├── docker-compose.yml
└── .env.example      o .env real nunca é commitado
```

**O que ainda não existe, e quando existe:** `frontend/` chegou com a Slice 8 —
HTML, CSS e JavaScript servidos pelo Boot, sem npm e sem etapa de build; o Maven
copia a pasta para `static/` como já faz com a tabela WCAG. Não há
`infrastructure/` — Postgres, Kafka e ML Service estão no `docker-compose.yml`
da raiz, e separar em pasta própria só se paga quando houver mais de um arquivo
de infraestrutura. `spike/` fica no repositório de propósito: ele é a evidência
da decisão do ADR 0008, e apagá-lo deixaria o ADR afirmando um resultado que
ninguém pode conferir.
