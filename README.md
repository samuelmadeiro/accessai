# AccessAI

Analisa documentos `.docx` quanto à acessibilidade e devolve problemas mapeados
para critérios WCAG, com evidência rastreável até o ponto exato do documento.

---

## Estado atual: Slice 3 de 9

Este README descreve **o que existe e roda hoje**, não o que está planejado.
O projeto é construído em fatias verticais finas (`CONTRIBUTING.md` §7), uma por vez.

| Slice | Entrega | Estado |
|---|---|---|
| 0 | Decisões D1–D6 e ADRs | [documento](docs/architecture/fase-0.md) — D1 aprovado, D2–D6 abertos |
| 1 | Upload → Kafka → 1 regra → Postgres → `GET /analyses/{id}` | **Pronta** |
| 2 | Rule Engine completo (6 regras) e score por categoria | **Pronta** |
| 3 | Outbox, retry com backoff, DLT, correlation ID | **Pronta** |
| 4–5 | Dataset, treino e ML Service | não iniciada |
| 6–7 | AI Gateway e copilot | não iniciada |
| 8–9 | Frontend acessível, observabilidade | não iniciada |

**Não existe ML nem IA neste repositório ainda.** Não há `ml-service/`, não há
modelo treinado, não há chamada a LLM. Tudo aqui é determinístico: seis regras
que leem XML. Quando ML e IA entrarem, entram como camadas de interpretação
sobre o Rule Engine — nunca como substituto dele (`CONTRIBUTING.md` §2).

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
cp .env.example .env    # ajuste a senha do Postgres
docker compose up -d
```

Sobem três serviços: Postgres 16, Kafka 4.3 em modo KRaft (sem ZooKeeper) e o
backend. Espere `backend=healthy`:

```bash
docker compose ps
```

### Enviar um documento

```bash
curl -X POST -F "file=@seu-documento.docx" http://localhost:8080/analyses
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

Onze casos ponta a ponta, em duas suítes. **Fluxo** (6): documento que viola
cinco regras com o score exato conferido, documento acessível que tira 100 sem
falso positivo, imagem só no cabeçalho, documento que quebra no parsing, conteúdo
que não é DOCX (`422`) e análise inexistente (`404`). **Resiliência** (5): o
outbox marcado como publicado só após a confirmação do broker, falha transitória
reentregue com backoff até concluir, falha permanente desviada para a DLT com a
análise em `FALHOU` e a causa registrada, evento duplicado que não duplica
problema, e o `X-Correlation-ID` do cliente atravessando HTTP, banco e evento.

A falha transitória é forçada com um espião sobre `ExecucaoDaAnalise`: derrubar o
Postgres no meio do teste produziria a mesma exceção com um teste lento e
instável.

Além deles, **163 testes unitários** que não precisam de Docker e rodam em
segundos: extrator e os sete coletores, uma suíte por regra (cada uma com o caso
de conformidade que ela precisa **não** marcar), calculadora de score, catálogo
WCAG, motor de regras, validador de upload e a política de falha do consumidor.

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

## Arquitetura da Slice 1

```
POST /analyses            X-Correlation-ID entra (ou é gerado) e vai para o MDC
      │
      ├─ valida o tipo REAL do conteúdo (assinatura zip + partes OOXML)
      └─ MESMA transação: analise + binário + outbox_evento ── commit
                    │
      PublicadorDeOutbox (a cada 500ms, FOR UPDATE SKIP LOCKED)
      publica ──► espera o broker confirmar ──► marca publicado_em
                    │
         accessai.analise.solicitada.v1  (Kafka, KRaft)
                    │
      ┌─────────────┘
      │  consumidor idempotente (chave = eventId)
      ├─ UMA passagem pelo pacote alimenta 7 coletores
      │     imagens · tabelas · títulos · links · idioma · dc:title · rels
      ├─ executa as 6 regras sobre os fatos extraídos
      └─ grava problemas ──► GET /analyses/{id} + score calculado na leitura
            │
            └─ exceção ──► retry 500ms, 1s, 2s, 4s ──► .DLT ──► situacao = FALHOU
```

**O evento não se perde mais.** Gravar a análise e gravar a intenção de publicar
são a mesma transação. Se o processo morrer antes de publicar, a linha continua
pendente no `outbox_evento` e o próximo ciclo do publicador a leva. O preço está
declarado: entrega **at-least-once** — morrer entre publicar e marcar republica o
evento, e `evento_processado` deduplica pelo `eventId`.

**Falha permanente × transitória.** Pacote que não abre, binário ausente, linha
inexistente: reprocessar dá o mesmo resultado. Essas exceções estão em
`addNotRetryableExceptions` e vão direto para a DLT, sem gastar tentativa.
Qualquer outra sobe e é reentregue com backoff exponencial — banco fora do ar não
é defeito do documento do usuário. Esgotadas as tentativas, a mensagem vai para
`accessai.analise.solicitada.v1.DLT`, e é o consumidor da DLT que marca a análise
como `FALHOU` e grava a causa em `evento_em_dlt`.

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
backend/     Spring Boot 4.1, Java 25 — API, Rule Engine, extração, Kafka
spike/       projeto descartável: POI × parsing XML direto (decisão registrada)
datasets/    manifesto do corpus real; binários fora do git
docs/
  adr/                     uma decisão por arquivo (0001–0010)
  architecture/fase-0.md   decisões D1–D6 e condições C-1 a C-3
  wcag/criteria.json       tabela versionada de critérios
  journal/01-slice.md      diário da Slice 1
scripts/     coleta do corpus
```

---

## Stack

| Camada | Escolha |
|---|---|
| Runtime | Java 25 (LTS) |
| Framework | Spring Boot 4.1.0 |
| Persistência | PostgreSQL 16 + Flyway 12 |
| Mensageria | Apache Kafka 4.3 (KRaft) |
| Testes | JUnit 6, Testcontainers 2 |
| Build | Maven |

Nada de Redis ainda: ele só ganha função a partir da Slice 3.

---

## Observabilidade

`/actuator/health` está exposto, **sem autenticação**, junto com `/actuator/info`
— nada mais. É o que o healthcheck do `docker compose` consulta. Numa slice com
autenticação (ADR 0004) esse endpoint entra na configuração de segurança; hoje
qualquer um que alcance a porta 8080 lê o estado do banco e do broker.

**Correlation ID.** Todo `POST`/`GET` aceita `X-Correlation-ID` e **devolve** o
valor no cabeçalho da resposta — sem isso, quem chamou não tem como citar a
requisição ao relatar um problema. O id atravessa quatro contextos: MDC da
requisição, coluna `correlation_id`, payload do evento e cabeçalho do registro
Kafka. O consumidor lê o **cabeçalho**, não o corpo, para que o log já esteja
correlacionado mesmo quando a desserialização do payload falha.

O valor recebido é **validado, não saneado**: só `[A-Za-z0-9-]{1,64}` passa, e
qualquer outra coisa é trocada por um id gerado. Um cabeçalho com quebra de linha
injeta linha falsa no log, e log falsificado é pior que log ausente.

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
- **Sem autenticação e sem isolamento por usuário.** A migration que trouxer
  autenticação adiciona `owner_id` e o teste que prova o isolamento.
- **Binário em `bytea` no banco principal** não sobrevive à Slice 5, quando o
  ML Service for outro processo — ele não pode ler este banco (`CONTRIBUTING.md` §5).
- **Seis regras.** O corpus real mostrou 4 imagens em 9 documentos, todas sem
  alt utilizável. Amostra pequena para conclusão forte sobre qualquer regra.
- **Os pacotes de teste são sintéticos.** Montados em memória, modelados no que
  Word, Google Docs e LibreOffice produzem — mas não são exports reais. A
  validação contra exports reais dos três programas continua pendente, e é a
  mesma crítica que este projeto faz ao dataset sintético em D2.
