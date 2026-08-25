# AccessAI

Analisa documentos `.docx` quanto à acessibilidade e devolve problemas mapeados
para critérios WCAG, com evidência rastreável até o ponto exato do documento.

---

## Estado atual: Slices 0 a 7 fechadas

Este README descreve **o que existe e roda hoje**, não o que está planejado.
O projeto é construído em fatias verticais finas (`CONTRIBUTING.md` §7), uma por vez.

| Slice | Entrega | Estado |
|---|---|---|
| 0 | Decisões D1–D6 e ADRs | [documento](docs/architecture/fase-0.md) — D1 e D3, D4, D6 aceitos; **D2 e D5 seguem em proposta** |
| 1 | Upload → Kafka → 1 regra → Postgres → `GET /analyses/{id}` | **Pronta** |
| 2 | Rule Engine completo (6 regras) e score por princípio | **Pronta** |
| 3 | Outbox, retry com backoff, DLT, correlation ID | **Pronta** |
| 4 | Dataset, treino e métricas | **Pronta** — sobre pré-rótulo, sem modelo exportado ([model card](docs/ml/model-card-alt-quality.md)) |
| 5 | ML Service (FastAPI), cliente Java e predição no resultado | **Pronta** — HTTP síncrono (ADR 0011), p99 de 7 ms |
| 5A | Autenticação JWT, `owner_id` e rate limit | **Pronta** — criada porque o D4 estava aceito sem dono no plano |
| 6 | AI Gateway e recomendações fundamentadas | **Pronta** — com provider de fixture |
| 7–9 | Copilot, frontend acessível, observabilidade | não iniciadas |

### As três coisas que este projeto se recusa a fingir

**Não existe modelo de ML treinado.** `ml-service/models/` está vazio, e isso é
uma trava, não um esquecimento: `accessai-treinar` **recusa exportar** artefato
treinado sobre rótulo não revisado, porque um `.joblib` naquela pasta faria o
serviço inteiro passar a responder `usouHeuristica: false`. Toda predição de
qualidade de alt hoje vem de regra, e cada resposta declara isso — com
`confianca: null`, porque regra não tem probabilidade.

**Nenhum modelo de linguagem é consultado.** O AI Gateway existe, com guardrail e
teto de gasto, mas o provider ativo é o `FakeAiProvider`. Toda recomendação sai
com `procedencia: "FIXTURE"`, do provider até a coluna do banco. O ADR 0005 segue
em proposta: não há chave, e a escolha de custo × qualidade é do dono do
orçamento.

**A métrica do Modelo 1 mede imitação, não qualidade.** A macro-F1 de
`0,508 ± 0,098` foi medida sobre `rotulo_provisorio` — a saída de uma heurística.
Ela diz que o classificador imita aquela heurística melhor que outra heurística
imita, e nada além disso. O kappa de Cohen contra revisão humana, que o ADR 0002
§4 exige, ainda não foi medido.

Isso é o `CONTRIBUTING.md` §1 no código: *"nada pode ser falso — nenhum modelo de
ML que é if/else, nenhuma IA que é template string"*. A saída é declarar a
procedência em toda camada, não esconder o degrau.

### As seis regras implementadas

| Regra | Critério WCAG | Nível | Severidade | O que detecta |
|---|---|---|---|---|
| `IMAGEM_SEM_TEXTO_ALTERNATIVO` | 1.1.1 Non-text Content | A | ALTA | `wp:docPr` sem `descr` numa imagem |
| `TABELA_SEM_CABECALHO` | 1.3.1 Info and Relationships | A | ALTA | primeira linha sem `w:tblHeader` |
| `ORDEM_HIERARQUICA_CABECALHOS` | 1.3.1 Info and Relationships | A | MEDIA | H1 direto para H3, ou documento que começa em H2 |
| `TITULO_AUSENTE` | 2.4.2 Page Titled | A | MEDIA | `dc:title` ausente ou em branco em `docProps/core.xml` |
| `LINK_SEM_TEXTO_DESCRITIVO` | 2.4.4 Link Purpose | A | MEDIA | "clique aqui", "saiba mais", ou a URL como texto |
| `IDIOMA_NAO_DECLARADO` | 3.1.1 Language of Page | A | ALTA | nenhum `w:lang` no padrão do documento |

### Onde o falso positivo mora, e o que cada regra recusa fazer

O trabalho de uma regra determinística não é achar problema: é achar problema
sem inventar. Cada uma tem um caso que ela **se recusa** a marcar.

- **`descr=""` não é defeito.** É a forma prevista de declarar imagem
  decorativa, e o próprio 1.1.1 admite conteúdo que a tecnologia assistiva pode
  ignorar. Tratar vazio como defeito produziria falso positivo em todo documento
  bem marcado.
- **Nem todo desenho é imagem.** `wp:docPr` existe em qualquer desenho: caixa de
  texto, autoforma, gráfico, SmartArt. Um desenho só vira imagem quando a
  subárvore tem `pic:pic`, `a:blip` ou `v:imagedata`.
- **Tabela vazia não precisa de cabeçalho.** Tabela sem linha nenhuma aparece em
  documento real como recurso de diagramação.
- **Subir de nível não é salto.** Voltar de H3 para H1 é fim de seção, não erro.
- **Link sem texto não é problema de 2.4.4.** Ele costuma envolver uma imagem, e
  quem responde é a 1.1.1 — marcar os dois viraria um defeito em dois problemas.
- **Texto curto não é texto ruim.** "Portaria 145" descreve o destino melhor que
  muita frase longa; a lista de expressões genéricas é fechada, sem heurística
  de tamanho.

### Score explicável

Nota de 0 a 100 por princípio WCAG, e o princípio sai do número do critério —
1.x Perceptível, 2.x Operável, 3.x Compreensível, 4.x Robusto. Não existe tabela
de regra→categoria em lugar nenhum.

```
penalidade = soma por severidade (CRITICA 25, ALTA 15, MEDIA 8, BAIXA 3)
categoria  = max(0, 100 - penalidade)
global     = média ponderada — só dos princípios que têm regra
```

Pesos e penalidades ficam em `application.yml`, não no código: eles são
**escolha**, não medida. A WCAG não pontua nada e não hierarquiza princípios, e
por isso os pesos são iguais por padrão — um 35/30/25/10 inventado daria ao
número uma precisão que ele não tem.

**Princípio sem regra fica fora da média.** Hoje nenhuma regra verifica 4.x, e
`naoAvaliados: ["ROBUSTO"]` vem na resposta. Dar 100 a uma categoria que o
sistema não verifica seria afirmar conformidade inexistente — o mesmo defeito
que tirou o Apache POI do caminho de extração.

O score **não é gravado**: é função pura dos problemas persistidos mais a
configuração, calculada na leitura. A contrapartida está em Limitações.

---

## Como rodar

Pré-requisitos: Docker, JDK 25.

```bash
cp .env.example .env    # ajuste a senha do Postgres e o segredo do JWT
docker compose up -d
```

`ACCESSAI_JWT_SECRET` precisa de pelo menos 32 bytes — **a aplicação recusa
subir com menos**, e não há valor padrão. Gere o seu:

```bash
openssl rand -base64 48
```

Sobem cinco serviços: Postgres 16, Kafka 4.3 em modo KRaft (sem ZooKeeper),
Redis 8, o backend e o ML Service. Espere `backend=healthy`:

```bash
docker compose ps
```

### Enviar um documento

Desde a Slice 5A toda rota de análise exige autenticação. Crie a conta e guarde
o token:

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/auth/registrar \
  -H 'Content-Type: application/json' \
  -d '{"email":"voce@exemplo.com","senha":"uma-frase-longa-o-bastante"}' \
  | python -c 'import sys,json; print(json.load(sys.stdin)["token"])')
```

```bash
curl -X POST -F "file=@seu-documento.docx" \
  -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/analyses
```

```json
{
  "analiseId": "31702e03-f1c3-47c2-a613-aeb8044a598e",
  "correlationId": "4b980737-4fbe-4917-9e1b-a51205172b8a",
  "situacao": "RECEBIDA"
}
```

O processamento é assíncrono: o `POST` responde `201` e publica um evento; o
consumidor faz o trabalho.

### Consultar o resultado

```bash
curl http://localhost:8080/analyses/31702e03-f1c3-47c2-a613-aeb8044a598e
```

Resposta real, de um edital publicado por uma prefeitura brasileira:

```json
{
  "situacao": "CONCLUIDA",
  "nomeArquivo": "edital-com-problemas.docx",
  "tipoMimeDetectado": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  "totalDeProblemas": 6,
  "score": {
    "global": 77,
    "categorias": [
      { "principio": "PERCEPTIVEL",   "titulo": "Perceptivel",   "score": 62, "peso": 25, "problemas": 3, "penalidade": 38 },
      { "principio": "OPERAVEL",      "titulo": "Operavel",      "score": 84, "peso": 25, "problemas": 2, "penalidade": 16 },
      { "principio": "COMPREENSIVEL", "titulo": "Compreensivel", "score": 85, "peso": 25, "problemas": 1, "penalidade": 15 }
    ],
    "naoAvaliados": ["ROBUSTO"]
  },
  "problemas": [
    {
      "regraId": "IMAGEM_SEM_TEXTO_ALTERNATIVO",
      "criterioWcag": "1.1.1",
      "nivelWcag": "A",
      "severidade": "ALTA",
      "partePacote": "word/header1.xml",
      "evidencia": "imagem 'Imagem 8' nao tem atributo de texto alternativo (descr)"
    },
    {
      "regraId": "TABELA_SEM_CABECALHO",
      "criterioWcag": "1.3.1",
      "nivelWcag": "A",
      "severidade": "ALTA",
      "partePacote": "word/document.xml",
      "evidencia": "tabela 1 (4 linhas) nao marca a primeira linha como cabecalho (w:tblHeader)"
    },
    {
      "regraId": "IDIOMA_NAO_DECLARADO",
      "criterioWcag": "3.1.1",
      "nivelWcag": "A",
      "severidade": "ALTA",
      "partePacote": "word/styles.xml",
      "evidencia": "o documento nao declara idioma em lugar nenhum (w:lang ausente)"
    }
  ]
}
```

Três coisas para reparar. **`partePacote`**: a imagem está no cabeçalho, não no
corpo — metade das imagens do corpus real está fora de `word/document.xml`.
**`penalidade` e `problemas` por categoria**: cada ponto perdido rastreia até os
problemas que o causaram. **`naoAvaliados`**: 77 quer dizer "77 no que foi
medido", e o que não foi medido está dito na própria resposta.

### Derrubar

```bash
docker compose down
```

Use `docker compose down -v` para apagar também os volumes.

---

## Testes

```bash
./mvnw verify
```

O wrapper e o POM agregador da raiz existem só para isso: `CONTRIBUTING.md` §9 manda
rodar da raiz. O `backend/` continua sendo um módulo independente
(`cd backend && ./mvnw verify` faz o mesmo).

O teste ponta a ponta sobe Postgres e Kafka reais via Testcontainers e percorre
upload → evento → regra → persistência → `GET`. **Não há mock de
infraestrutura**: um teste que troca o broker por um mock não prova que o
contrato do tópico funciona, que é justamente o que esta slice precisa
demonstrar.

**27 casos ponta a ponta, em cinco suítes**, uma por slice que precisou provar
algo com infraestrutura real:

- **Fluxo** (6) — documento que viola cinco regras com o score exato conferido,
  documento acessível que tira 100 sem falso positivo, imagem só no cabeçalho,
  documento que quebra no parsing, conteúdo que não é DOCX (`422`) e análise
  inexistente (`404`).
- **Resiliência** (6) — outbox marcado como publicado só após a confirmação do
  broker, falha transitória reentregue com backoff até concluir, falha permanente
  desviada para a DLT com a análise em `FALHOU`, evento duplicado que não duplica
  problema, mensagem ilegível que não trava o consumidor, e o `X-Correlation-ID`
  atravessando HTTP, banco e evento.
- **Predição** (3) — imagem com alt vira predição fora do score, imagem sem alt
  gera **zero** chamadas ao ML (usar ML onde regra resolve viola o §2), e ML fora
  do ar responde pela heurística local, declarada como regra.
- **Isolamento** (3) — o entregável do D4: A recebe **404** na análise de B, sem
  token é `401`, e token assinado com outra chave também é `401`.
- **Rate limit** (2) — passar do teto responde `429` com `Retry-After`, e o teto
  é por conta: uma conta estourada não bloqueia as outras.
- **Recomendações** (7) — recomendação citando a regra encontrada e declarando
  `procedencia: FIXTURE`, o **critério de pronto da Slice 6** (pergunta sem base
  na análise → recusa), documento limpo que não rende recomendação, geração
  idempotente, leitura sem chamar a IA, e recomendação de outro usuário em `404`.
- **Conversa** (7) — o **critério de pronto da Slice 7**: pergunta sem base
  recusada **no segundo turno**, com o primeiro já respondido, e histórico
  acumulando as duas pontas em ordem. Mais: turno recusado que não grava nem a
  pergunta, documento limpo que não rende conversa, turno sem pergunta em `422`,
  e conversa de outro usuário em `404` no POST e no GET.

A falha transitória é forçada com um espião sobre `ExecucaoDaAnalise`: derrubar o
Postgres no meio do teste produziria a mesma exceção com um teste lento e
instável.

O teste da mensagem ilegível publica bytes que nunca virão um evento e depois
envia um documento válido. O que ele afirma não é o destino do lixo, e sim que o
documento seguinte ainda é processado: sem `ErrorHandlingDeserializer` a falha
acontece dentro do `poll()` e o container repolla o mesmo offset para sempre. O
tópico roda com uma partição só nesse cenário — com três, o lixo e o documento
cairiam em partições diferentes e o teste passaria sem provar nada.

Além deles, **248 testes unitários** que não precisam de Docker e rodam em
segundos: extrator e os sete coletores, uma suíte por regra (cada uma com o caso
de conformidade que ela precisa **não** marcar), calculadora de score, catálogo
WCAG, cliente do ML Service, o guardrail de IA (na recomendação e na conversa), a
sanitização de prompt, as regras de arquitetura do ADR 0012 e o corpus de
contrato da heurística.

> **Cuidado ao ler o `BUILD SUCCESS`.** Sem Docker no ar, os testes ponta a ponta
> são **pulados** e o build passa assim mesmo — `Skipped: 34` e `Failures: 0` dão
> o mesmo exit code de 34 verdes. Confira a contagem
> ([issue #1](https://github.com/samuelmadeiro/accessai/issues/1)). No `ml-service/`, **404 testes** de pytest.

**O segredo do JWT nos testes é gerado, não escrito.** O §5 diz "zero hardcode,
inclusive em teste" — segredo literal num `application.yml` de teste é segredo
publicado, e o passo seguinte previsível é alguém copiar para o ambiente real.

**Nenhum `.docx` binário no repositório.** Os pacotes de teste são montados em
memória por `DocxDeTeste`, com o XML à vista ao lado da asserção — um zip
commitado é um arquivo que ninguém revisa em pull request.

**O E2E exige Docker.** Sem ele a suíte é *pulada*, não quebrada — o build
falharia com um stack trace de "Could not find a valid Docker environment", que
parece defeito de código e não ausência de pré-requisito. A contrapartida é
real: teste pulado não protege ninguém, então o CI precisa garantir Docker e
tratar suíte pulada como falha.

Os testes do `spike/` não precisam de Docker:

```bash
cd spike && mvn test
```

26 testes, 1 pulado — a amostra real é material de terceiro e só roda com
`-Dspike.amostraReal=/caminho/arquivo.docx`.

---

## Arquitetura

Três mecanismos, nesta ordem de precedência (`CONTRIBUTING.md` §2): **Rule
Engine** para o determinístico, **ML** para o que exige inferência, **IA
generativa** para explicar. A regra dura é que ML nunca substitui regra, e IA
nunca substitui nenhum dos dois — as duas camadas de cima são interpretação
sobre um resultado que já está completo sem elas.

```
POST /analyses                      Authorization: Bearer <JWT>  (Slice 5A)
      │                             X-Correlation-ID entra ou é gerado → MDC
      ├─ rate limit por usuário (Redis, janela fixa) ──► 429 + Retry-After
      ├─ valida o tipo REAL do conteúdo (assinatura zip + partes OOXML)
      └─ MESMA transação: analise(owner_id) + binário + outbox_evento ── commit
                    │
      PublicadorDeOutbox (500 ms, FOR UPDATE SKIP LOCKED)
      publica ──► espera o broker confirmar ──► marca publicado_em
                    │
         accessai.analise.solicitada.v1  (Kafka, KRaft)
                    │
      ┌─────────────┘  consumidor idempotente (chave = eventId)
      │
      ├─ 1. EXTRACAO   uma passagem pelo pacote alimenta 7 coletores
      │                imagens · tabelas · títulos · links · idioma · dc:title · rels
      │
      ├─ 2. RULE ENGINE  6 regras determinísticas sobre os fatos extraídos
      │                  ──► problema (evidência + critério WCAG)
      │
      ├─ 3. ML          POST /v1/predict:batch ──► ml-service (FastAPI)
      │                  o documento inteiro numa chamada; sem resposta,
      │                  HeuristicaDeAltLocal responde e se declara regra
      │                  ──► predicao_de_alt   (FORA do score)
      │
      └─ exceção ──► retry 500ms, 1s, 2s, 4s ──► .DLT ──► situacao = FALHOU

GET /analyses/{id}        findByIdAndOwnerId ──► 404 se não for sua
      └─ score calculado na leitura + problemas + predicoesDeAlt

POST /analyses/{id}/recommendations                            (Slice 6)
      └─ AI Gateway ─ guardrail de entrada ─ teto de gasto ─ provider ─
                                             guardrail de saída ──► recomendacao

POST /analyses/{id}/chat                                       (Slice 7)
      └─ mesmo gateway, mesmas quatro etapas, A CADA TURNO ──► turno_de_conversa
GET  /analyses/{id}/chat
      └─ histórico das duas pontas, com procedencia em cada fala do assistente
```

### A fronteira entre os dois runtimes

O backend é Java, o ML Service é Python. A fronteira existe por três razões, e
throughput não é nenhuma delas (D3): **runtime diferente**, **ciclo de deploy
independente** e **replay** — quando existir um modelo v2, o histórico é
reprocessado relendo o tópico, sem reupload.

A Slice 5 **contrariou o próprio contrato** aqui. O `CONTRIBUTING.md` §5 pedia
comunicação por evento; o que existe é `POST /v1/predict:batch` síncrono. A
divergência foi apontada antes de escrever o código e virou o
[ADR 0011](docs/adr/0011-integracao-com-o-ml-service-por-http.md), que lista os
três gatilhos que revertem para o desenho por evento. O isolamento do banco
continua valendo: **o serviço Python não tem uma linha de Postgres**.

O que sustenta a escolha é a precedência do §2. Se a predição fosse essencial,
acoplar a análise à disponibilidade de outro processo seria indefensável. Como
ela é enfeite sobre um score já completo, indisponibilidade custa **uma
informação a menos, não uma análise a menos**.

### Degradação em três degraus

O caminho da predição de qualidade de alt tem três níveis, e cada um se declara:

| Degrau | Quando | O que a resposta diz |
|---|---|---|
| Modelo | há `.joblib` carregado | `usouHeuristica: false`, com `confianca` |
| Heurística do serviço | Python de pé, sem modelo | `usouHeuristica: true`, `confianca: null` |
| Heurística local do Java | Python fora do ar | idem — regra local, declarada como regra |

O terceiro degrau duplica a regra em duas linguagens, o que é um risco real e
conhecido. O que o torna administrável é
[`docs/ml/heuristica-alt.golden.json`](docs/ml/heuristica-alt.golden.json): 61
casos gerados a partir da implementação Python, que **os dois lados reproduzem
em teste**. Mexer numa regra de um lado quebra o build do outro.

### O AI Gateway, e por que ele recusa

`AiProvider` é a **única** porta de saída para LLM (§5). O gateway faz quatro
coisas nesta ordem, e a ordem é a decisão inteira dele:

1. **Guardrail de entrada** — pergunta que cita critério que a análise não
   verificou é recusada com `422`. Recusar custa zero; recusar depois de chamar
   seria pagar para descobrir que a pergunta não tinha base.
2. **Teto de gasto** — contador mensal no Redis (D5). Falha **fechado**: sem
   conseguir conferir, a chamada paga não acontece.
3. **O provider** — a única linha do sistema que falaria com um modelo.
4. **Guardrail de saída** — recomendação que cita regra ausente da análise é
   descartada antes de chegar ao usuário.

O guardrail de saída parece redundante com uma fixture, que não tem como
inventar. Ele existe porque o provider de amanhã é generativo, e alucinar
critério WCAG plausível é o modo de falha dele. **Guardrail escrito depois que o
modelo chega é guardrail escrito com pressa.**

A fundamentação não é uma promessa: `AiProvider.Fundamento` só aceita achados
reais, então não existe caminho no sistema que mande texto livre ao provider.
Recomendação fundamentada na análise virou contrato de compilador.

### Conteúdo de terceiro é hostil

O texto extraído do `.docx` e a pergunta digitada acabam num prompt, e nenhum dos
dois foi escrito pelo dono do sistema (§5). Quatro camadas, nenhuma suficiente
sozinha:

1. **Sanitização por construção**, no construtor compacto do `Fundamento` — todo
   provider recebe conteúdo tratado, inclusive o que ainda não foi escrito.
2. **Tirar o poder de formatar**: quebra de linha, caractere de controle e
   marcador de papel (`System:`, `<|im_start|>`, `[INST]`, cerca de código) viram
   `[removido]`. Não é lista negra de frases — o que transforma conteúdo em
   instrução é a formatação, não o vocabulário.
3. **Envelope com nonce** sorteado por chamada: o texto não fecha um bloco cujo
   delimitador ele não conhece.
4. **A instrução desarma o bloco**, dizendo que o conteúdo delimitado é dado a
   analisar e que pedido para ignorar as regras é o próprio problema.

A montagem do prompt mora num lugar só (`MontadorDePrompt`, usado pelo gateway),
porque é ali que conteúdo não confiável encosta na instrução.

### Autenticação e isolamento por linha

JWT stateless com segredo simétrico (HS256) vindo do ambiente — **sem
`ACCESSAI_JWT_SECRET` a aplicação não sobe**, porque um padrão de
desenvolvimento aqui viraria segredo publicado. Nenhuma biblioteca de JWT de
terceiro: o `oauth2-resource-server` já traz o Nimbus, e uma dependência a menos
no caminho de autenticação é uma superfície de CVE a menos.

O isolamento é `findByIdAndOwnerId`, **método explícito e não filtro global do
Hibernate**: filtro global é fácil de esquecer de ligar, e o esquecimento é
silencioso — não quebra teste, só vaza a análise de outra pessoa.

E responde **404, não 403**. Dizer "existe, mas não é sua" já entrega a
existência do recurso. O entregável dessa decisão é um teste, não uma afirmação:
`IsolamentoPorUsuarioIT`.

### O evento não se perde

Gravar a análise e gravar a intenção de publicar são a mesma transação. Se o
processo morrer antes de publicar, a linha continua pendente no `outbox_evento` e
o próximo ciclo do publicador a leva. O preço está declarado: entrega
**at-least-once** — morrer entre publicar e marcar republica o evento, e
`evento_processado` deduplica pelo `eventId`.

**Falha permanente × transitória.** Pacote que não abre, binário ausente, linha
inexistente: reprocessar dá o mesmo resultado. Essas exceções estão em
`addNotRetryableExceptions` e vão direto para a DLT, sem gastar tentativa.
Qualquer outra sobe e é reentregue com backoff exponencial — banco fora do ar não
é defeito do documento do usuário. Esgotadas as tentativas, é o consumidor da DLT
que marca a análise como `FALHOU` e grava a causa em `evento_em_dlt`.

A política inteira mora em `KafkaConfig`, não em `try/catch` do consumidor: mudar
de 4 para 6 tentativas não pode exigir tocar em código de domínio.

### Decisões que valem explicar

**A extração faz uma passagem só.** `ZipInputStream` é sequencial: reabrir o
pacote por regra multiplicaria a leitura por seis. Cada coletor declara em
`aceita(parte)` o que lhe interessa, e parte que ninguém quer nem é parseada.
Duas responsabilidades ficam no varredor, e não nos coletores, porque valem para
todos: a profundidade do elemento e o descarte da subárvore `mc:Fallback`.

**A extração não usa Apache POI.** O POI não enxerga desenho dentro de
`mc:AlternateContent` e devolve **zero imagens** nesse caso — falso negativo
silencioso, que num produto de score significa afirmar conformidade
inexistente. O caso apareceu num edital real de prefeitura, não só em
laboratório. Comparação medida em [spike/RESULTADO.md](spike/RESULTADO.md).

**Extensão de arquivo não é prova de nada.** A validação lê assinatura de zip e
exige `[Content_Types].xml` e `word/document.xml`. Na coleta do corpus real,
duas URLs terminadas em `.docx` responderam **HTTP 200 servindo HTML**.

**A seleção de partes é por exclusão, não por lista branca.** Um documento real
trazia `word/commentsDocument.xml`, e não `word/comments.xml` — nome que
nenhuma lista escrita à mão teria previsto.

**O evento não carrega os bytes do documento**, só `analiseId` e `sha256`.

**Nenhum critério WCAG é escrito em código.** As regras declaram só o
identificador; nível e título vêm de [docs/wcag/criteria.json](docs/wcag/criteria.json).
Se uma regra citar critério inexistente, a aplicação **não sobe**.

---

## WCAG aplicado a documento: a régua é o WCAG2ICT

A WCAG foi escrita para conteúdo web. Aplicar 1.1.1 a um `.docx` sem
justificativa seria analogia, não citação.

A base normativa deste projeto é o
[WCAG2ICT](https://www.w3.org/TR/wcag2ict-22/) — *Guidance on Applying WCAG 2 to
Non-Web Information and Communications Technologies*, W3C Group Note de
**11/12/2025**, que cobre WCAG 2.0/2.1/2.2 níveis A e AA e descreve, critério a
critério, quando ele se aplica a documento não-web e quais substituições de
termo são necessárias.

> **O WCAG2ICT é informativo, não normativo.** É uma Group Note do W3C: não é
> norma e não estabelece requisitos de conformidade. Este projeto não emite
> atestado de conformidade legal.

Cada entrada do `criteria.json` registra a aplicabilidade (`direta`,
`com_substituicao` ou `inaplicavel`) e a substituição usada. Critério marcado
como `inaplicavel` **não pode gerar violação** — no máximo recomendação.

Regulação que consome essa régua: **EN 301 549** (compras públicas na Europa),
**Section 508** (EUA) e, no Brasil, a **LBI — Lei 13.146/2015**.

---

## Corpus de teste

Dois corpora, com propósitos diferentes e honestidade sobre cada um:

| Corpus | Origem | Versionado? |
|---|---|---|
| `spike/src/test/resources/corpus/` | **sintético**, escrito para exercitar armadilhas conhecidas | sim |
| `datasets/corpus/` | **real**: documentos públicos brasileiros | só o manifesto |

Os binários reais **não entram no git**. Documento público real contém dado
pessoal (nome, CPF, matrícula), e comitá-lo criaria um problema de privacidade
irreversível no histórico. O manifesto guarda URL, SHA-256, órgão e licença;
o script rebaixa os arquivos:

```bash
python scripts/fetch-corpus.py
```

O script **confere** o SHA-256 contra o manifesto em vez de só gravar o que
baixou: se a origem trocou o arquivo, ele para com código de saída 2 e não
sobrescreve nada (`--aceitar-mudanca-de-origem` para aceitar conscientemente).
O manifesto é mesclado por URL — rodar com `--limite` não apaga a procedência do
que não foi baixado desta vez.

Detalhes e pendências em [datasets/README.md](datasets/README.md).

---

## Estrutura

```
backend/     Spring Boot 4.1, Java 25
  analise/      API, Rule Engine, extração OOXML, outbox, score
  autenticacao/ JWT, owner_id, rate limit de upload
  integracao/ml/ cliente do ML Service + heurística local de fallback
  ia/           AI Gateway, guardrail, provider, sanitização de prompt
ml-service/  Python 3.14 — dataset, treino, auditoria e inferência FastAPI
spike/       projeto descartável: POI × parsing XML direto (decisão registrada)
datasets/    manifesto do corpus real; binários fora do git
docs/
  adr/                     uma decisão por arquivo (0001–0011)
  architecture/fase-0.md   decisões D1–D6 e condições C-1 a C-3
  wcag/criteria.json       tabela versionada de critérios
  ml/                      model card e corpus de contrato da heurística
  journal/NN-slice.md      diário por slice (01 a 06)
scripts/     coleta do corpus e geração do golden da heurística
```

---

## Stack

| Camada | Escolha |
|---|---|
| Runtime | Java 25 (LTS) |
| Framework | Spring Boot 4.1.0 |
| Persistência | PostgreSQL 16 + Flyway 12 |
| Mensageria | Apache Kafka 4.3 (KRaft) |
| Cache / rate limit / teto de gasto | Redis 8 |
| Autenticação | Spring Security + OAuth2 Resource Server (JWT HS256) |
| ML | Python 3.14, FastAPI, scikit-learn |
| Testes | JUnit 6, Testcontainers 2, pytest |
| Build | Maven, setuptools |

**O Redis entrou na Slice 5A**, quando passou a ter função: rate limit *por
usuário* exige que usuário exista. A deduplicação de consumer ficou no Postgres
(`evento_processado`), que o §5 permite — "chave persistida **ou** em Redis". O
terceiro uso do D4, o teto de gasto de IA, chegou com a Slice 6.

Nenhuma biblioteca de JWT de terceiro e nenhum SDK de LLM: a superfície de
dependência do caminho de autenticação e do caminho de IA é a menor possível.

---

## Observabilidade

**Só `/actuator/health` é público**, porque é o que o healthcheck do
`docker compose` consulta e a orquestração não tem como se autenticar.
`/actuator/**` inteiro **não** é liberado: `metrics` e `env` expõem configuração.
Todo o resto exige token — a cadeia de segurança termina em
`anyRequest().authenticated()`, e rota nova nasce fechada.

**Correlation ID.** Todo `POST`/`GET` aceita `X-Correlation-ID` e **devolve** o
valor no cabeçalho da resposta — sem isso, quem chamou não tem como citar a
requisição ao relatar um problema. O id atravessa quatro contextos: MDC da
requisição, coluna `correlation_id`, payload do evento e cabeçalho do registro
Kafka. O consumidor lê o **cabeçalho**, não o corpo, para que o log já esteja
correlacionado mesmo quando a desserialização do payload falha.

O valor recebido é **validado, não saneado**: só `[A-Za-z0-9-]{1,64}` passa, e
qualquer outra coisa é trocada por um id gerado. Um cabeçalho com quebra de linha
injeta linha falsa no log, e log falsificado é pior que log ausente.

Um cliente pode mandar `abc123`, que passa na validação e não é UUID. O log fica
com o valor literal e o banco recebe um UUID derivado dele, estável — e **uma
linha por jornada liga os dois**. Sem ela, quem chega pelo banco, que é como
começa toda investigação, não acha linha de log nenhuma: ninguém calcula
`nameUUIDFromBytes` de cabeça no meio de um incidente.

Toda linha de log carrega o id:

```
14:32:07.118 INFO  [7c9e6679-7425-40de-944b-e07fc1f90ae7] d.a.a.o.PublicadorDeOutbox - evento publicado ...
```

**Diagnóstico por consulta, não por log.** `outbox_evento` guarda `tentativas` e
`ultimo_erro`; `evento_em_dlt` guarda a exceção original de tudo que esgotou o
retry. Métrica exportada é a Slice 9 — hoje são duas consultas SQL.

## Limitações conhecidas

Registradas aqui porque estão no código como dívida consciente, não como
descuido:

- **Entrega at-least-once, não exactly-once.** Morrer entre publicar e marcar
  republica o evento. É deliberado: `evento_processado` deduplica pelo `eventId`,
  e duplicata detectável é melhor que evento perdido.
- **`outbox_evento` cresce para sempre.** Não há expurgo de eventos publicados.
  Entra quando incomodar — não antes (`CONTRIBUTING.md` §5).
- **A DLT não tem reprocessador.** Mensagem parada lá exige ação humana; não há
  endpoint que a devolva ao tópico principal.
- **Polling de 500 ms.** O `201` volta na hora, mas o processamento começa em até
  meio segundo. Trocar polling por CDC (Debezium) custaria Kafka Connect no
  compose, contra o alvo de `docker compose up` (ADR 0006).
- **O score não é persistido.** É recalculado a cada leitura a partir dos
  problemas gravados. Mudar um peso em `application.yml` muda a nota de análises
  antigas. Uma coluna de score com versão da configuração entra quando existir
  listagem ou histórico — antes disso seria estado duplicado que diverge.
- **100 significa "sem problema no que foi medido"**, não "documento acessível".
  Seis regras cobrem uma fração da WCAG; o campo `naoAvaliados` existe para que
  ninguém leia o número sozinho.
- **Contraste (1.4.3) não entrou.** É a regra mais cara do projeto — cascata de
  cor com `themeTint`/`themeShade` — e segue como a última da fila.
- **Link em campo `HYPERLINK`** (`w:instrText`, forma legada) não é visto pelo
  coletor de links.
- **Nenhum modelo de ML treinado.** `models/` está vazio, e o treino recusa
  exportar artefato sobre rótulo não revisado. O ADR 0002 segue em proposta.
- **`INSUFFICIENT` não é detectável.** Das 50 amostras da classe, 44 são
  sintéticas e presas ao treino; sobra **uma** em validação e uma em teste. A
  F1 de 0,000 ali significa "errou a única que existia", não "não detecta" — e
  a classe que o produto mais precisa é a que o corpus menos sustenta.
- **Nenhum LLM é chamado.** O provider é fixture, declarado como tal — na
  recomendação e também no copiloto conversacional.
- **O copiloto não conversa sobre documento limpo.** Sem achado não há
  fundamento, e o guardrail recusa a conversa inteira. Intencional (ADR 0012):
  sobre resultado limpo só sairia conselho genérico apresentado como análise
  deste documento.
- **`/chat` não tem rate limit.** O limitador da Slice 5A é por upload. Um turno
  é gratuito enquanto o provider for fixture; deixa de ser no dia do ADR 0005.
- **O teto de gasto foi estimado sobre chamada única.** Multi-turno reenvia
  contexto a cada turno, e a conta precisa ser refeita com provider real.
- **O guardrail de entrada é sintático.** Ele casa número de critério; "por que
  as cores estão ruins?" passa e é o guardrail de saída que segura.
- **A heurística existe em duas linguagens.** Risco real, pago com um corpus de
  contrato que os dois lados reproduzem em teste.
- **Sem revogação de token.** Oito horas de validade e nenhuma lista de bloqueio.
- **Rate limit com janela fixa**, que permite um pico no limite entre janelas.
- **Binário em `bytea` no banco principal.** O ML Service não lê este banco
  (§5), então o binário nunca cruzou a fronteira — mas ele continua pesando a
  tabela principal.
- **Seis regras.** O corpus real mostrou 5 imagens em 9 documentos, todas sem
  alt utilizável. Amostra pequena para conclusão forte sobre qualquer regra.
- **Os pacotes de teste são sintéticos.** Montados em memória, modelados no que
  Word, Google Docs e LibreOffice produzem — mas não são exports reais. A
  validação contra exports reais dos três programas continua pendente, e é a
  mesma crítica que este projeto faz ao dataset sintético em D2.
