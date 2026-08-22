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
| 6 | AI Gateway + recomendações fundamentadas na análise | Guardrail testado: pergunta sem base na análise → recusa |
| 7 | Copilot conversacional | Idem, com histórico |
| 8 | Frontend + dashboard, acessível de verdade | Navegação 100% por teclado, testado com leitor de tela |
| 9 | Observabilidade, hardening, README, ADRs | — |

> **A Slice 5 foi entregue fora desta definição.** A tabela diz "ML Service
> consumindo Kafka"; o que existe é `POST /v1/predict` chamado de forma síncrona
> pelo consumidor Kafka do backend, com timeout curto e fallback para o Rule
> Engine. O motivo e as condições de reversão estão no ADR 0011. A latência de
> inferência, critério de pronto desta slice, é medida na chamada HTTP:
> p99 de 7 ms pela heurística e 9 ms com modelo carregado, contra um
> timeout de 1500 ms (`ml-service/README.md`).

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

---

## 9. Comandos

```bash
docker compose up -d          # sobe tudo
./mvnw test                   # testes Java
./mvnw verify                 # inclui Testcontainers
cd ml-service && pytest       # testes Python
```

## 10. Estrutura

```
accessai/
├── backend/          Spring Boot, Java 25
├── ml-service/       Python, FastAPI, consumer Kafka
├── frontend/
├── infrastructure/   docker, kafka, postgres
├── datasets/         versionado, nunca no código
├── docs/
│   ├── adr/          uma decisão por arquivo
│   ├── architecture/ diagramas Mermaid
│   ├── wcag/         criteria.json
│   ├── ml/           model cards
│   └── journal/      minhas anotações por slice
├── scripts/
├── docker-compose.yml
└── .env.example
```
