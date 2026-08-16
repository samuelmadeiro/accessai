# Fase 0 — Auditoria e decisões

Leia `CLAUDE.md` primeiro. Ele é o contrato; este arquivo é a primeira tarefa.

**Não escreva código de aplicação nesta fase.** A única saída é o documento
descrito abaixo, em `docs/architecture/fase-0.md`, mais os ADRs.

---

## Parte A — Auditoria do repositório atual

Se o repositório já tem código, inventarie:

- stack e versões reais (leia `pom.xml`, `requirements.txt`, `Dockerfile`)
- arquitetura de fato (não a pretendida)
- schema de banco existente e migrations
- testes existentes e o que eles realmente cobrem
- o que é reaproveitável, o que precisa ser reescrito, o que deve ser deletado

Se o repositório está vazio, diga isso em uma linha e vá para a Parte B.

Formato: tabela ou lista. Máximo 1 página. Sem prosa motivacional.

---

## Parte B — As seis decisões bloqueantes

Para **cada uma**, entregue: opções consideradas, trade-offs concretos,
recomendação sua, e o que a escolha custa. Se você não tiver informação
suficiente para recomendar, pergunte em vez de chutar.

### D1 — Formato de documento no MVP

Escolher **um**: HTML, PDF ou DOCX.

Considere e responda explicitamente:
- Qual biblioteca Java faz o parsing, e ela expõe o que as regras precisam?
- Para HTML: como responder "por que não usar axe-core, que já faz isso?"
- Para PDF: tagged PDF é raro na prática. Documentos sem tags produzem que
  tipo de análise? Isso ainda é um produto útil?
- Qual formato dá as regras mais interessantes de implementar sem virar
  um wrapper de biblioteca de terceiros?

### D2 — Procedência do dataset

O prompt original pedia "crie um dataset realista". Isso é uma armadilha:
um dataset gerado por LLM e apresentado como real destrói a credibilidade do
projeto em uma entrevista.

Responda com honestidade:
- De onde vêm os exemplos? Corpus público, coleta própria, geração sintética?
- Quem rotula, com que critério, e qual o acordo entre rotuladores?
- Quantas amostras são o mínimo viável para os dois modelos?
- Se for sintético: isso será declarado no model card e no README? Qual o
  viés introduzido e como será medido?
- Qual o **baseline burro** (maioria, regex, heurística) que o modelo precisa
  bater para justificar sua existência?

Se a conclusão honesta for "não há dataset viável para o modelo 2", diga isso.
Reduzir para um modelo bem feito é melhor que dois modelos falsos.

### D3 — Kafka se justifica?

Preciso responder isto em entrevista sem hesitar: *"por que Kafka e não
`@Async`, ou uma fila em Postgres com `SELECT FOR UPDATE SKIP LOCKED`?"*

Construa o argumento honesto. Se ele for fraco, diga que é fraco. As respostas
aceitáveis envolvem desacoplamento entre serviços em linguagens diferentes,
replay, e backpressure — não "é escalável".

### D4 — Autenticação e tenancy

Usuário único, multiusuário, ou multi-tenant? Como o isolamento é garantido no
banco? JWT stateless ou sessão? Onde entra o Redis nisso, se entrar.

### D5 — LLM: provider, custo, teto

Qual provider, qual modelo, custo estimado por análise, teto mensal, e o que
acontece quando o teto é atingido. Como os testes rodam sem gastar (fake
provider atrás da interface `AiProvider`).

### D6 — Fora de escopo

Lista explícita do que **não** será feito. Mínimo 5 itens. Esta lista é tão
importante quanto a de features.

---

## Parte C — Desenho técnico

Só depois de D1–D6 respondidas.

### C1 — Arquitetura

Diagrama Mermaid + 1 parágrafo por componente explicando por que ele existe.
Se um componente não tiver justificativa forte, remova-o do desenho.

### C2 — Contrato Kafka

Tabela com uma linha por tópico:

| Tópico | Producer | Consumer group | Partições | Chave | Payload (record) | Retry | DLT |

Mais: como funciona a idempotência (chave, onde é armazenada, TTL), e como o
`correlationId` atravessa o pipeline até os logs.

### C3 — Modelo de dados

DDL ou diagrama ER. Para cada uso de JSONB, justifique por que a coluna é
naturalmente semiestruturada. Índices e constraints explícitos.

### C4 — Plano de ML

Features, algoritmos candidatos, métrica de decisão e por que ela (accuracy em
classe desbalanceada não serve), estratégia de split, conteúdo do model card.

### C5 — Plano de IA

Estrutura do contexto enviado ao LLM, formato de saída estruturada, e os
guardrails: como o modelo é impedido de inventar resultado, e como ele recusa
quando a análise não contém a informação. Como testar isso automaticamente.

### C6 — Slice 1 detalhado

O caminho mais fino possível: upload → evento → uma regra → persistência →
`GET`. Liste os arquivos que serão criados e o critério de pronto.

---

## Parte D — ADRs

Escreva em `docs/adr/` apenas os ADRs cujas decisões você acabou de tomar.
Formato: contexto, decisão, alternativas consideradas, consequências
(incluindo as ruins). Um arquivo por decisão, numerado.

Não escreva ADR para decisão que ainda não foi tomada.

---

## Restrições desta entrega

- Máximo ~1500 palavras de prosa. Tabelas e diagramas não contam.
- Sem repetir o que já está em `CLAUDE.md`.
- Termine com uma seção **"Perguntas para o Samuel"**: tudo que você precisou
  assumir. Prefiro perguntar agora a descobrir no slice 4.
- Se em qualquer ponto sua conclusão honesta for "isto está over-engineered
  para um projeto solo", diga. Isso não é falha na entrega, é a entrega.
