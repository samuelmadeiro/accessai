# AccessAI

Analisa documentos `.docx` quanto à acessibilidade e devolve problemas mapeados
para critérios WCAG, com evidência rastreável até o ponto exato do documento.

---

## Estado atual: Slice 1 de 9

Este README descreve **o que existe e roda hoje**, não o que está planejado.
O projeto é construído em fatias verticais finas (`CLAUDE.md` §7), uma por vez.

| Slice | Entrega | Estado |
|---|---|---|
| 0 | Decisões D1–D6 e ADRs | [documento](docs/architecture/fase-0.md) — D1 aprovado, D2–D6 abertos |
| 1 | Upload → Kafka → 1 regra → Postgres → `GET /analyses/{id}` | **Pronta** |
| 2 | Rule Engine completo (6+ regras) e score por categoria | não iniciada |
| 3 | Retry, DLT, idempotência completa, correlation ID | não iniciada |
| 4–5 | Dataset, treino e ML Service | não iniciada |
| 6–7 | AI Gateway e copilot | não iniciada |
| 8–9 | Frontend acessível, observabilidade | não iniciada |

**Não existe ML nem IA neste repositório ainda.** Não há `ml-service/`, não há
modelo treinado, não há chamada a LLM. A Slice 1 é 100% determinística: uma
regra que lê um atributo XML. Quando ML e IA entrarem, entram como camadas de
interpretação sobre o Rule Engine — nunca como substituto dele (`CLAUDE.md` §2).

### A única regra implementada

`IMAGEM_SEM_TEXTO_ALTERNATIVO` — WCAG **1.1.1 Non-text Content**, nível A.

Distingue três estados, e a distinção é o ponto da regra:

| Estado no XML | Significado | É problema? |
|---|---|---|
| `descr` ausente | texto alternativo faltando | **sim** |
| `descr=""` ou só espaços | imagem declarada como decorativa | não |
| `descr="Gráfico de..."` | texto alternativo presente | não |

Tratar `descr=""` como defeito produziria falso positivo em todo documento bem
marcado — exatamente o contrário do objetivo.

**E nem todo desenho é imagem.** `wp:docPr` — o elemento que carrega o alt text —
existe em qualquer desenho: caixa de texto, autoforma, gráfico, SmartArt. Contar
todos como imagem transformava toda caixa de texto de edital em "imagem sem alt".
Um desenho só entra na regra quando a própria subárvore tem `pic:pic`, `a:blip`
ou `v:imagedata` — a marca de que há bitmap ali.

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
  "nomeArquivo": "Modelo-de-Edital-Fomento-a-Execucao-de-Acoes-Culturais.docx",
  "tipoMimeDetectado": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  "tamanhoBytes": 381243,
  "totalDeProblemas": 1,
  "problemas": [
    {
      "regraId": "IMAGEM_SEM_TEXTO_ALTERNATIVO",
      "criterioWcag": "1.1.1",
      "nivelWcag": "A",
      "severidade": "ALTA",
      "partePacote": "word/header1.xml",
      "evidencia": "imagem 'Imagem 8' nao tem atributo de texto alternativo (descr)"
    }
  ]
}
```

Repare em `partePacote`: a imagem está no **cabeçalho**, não no corpo. Metade
das imagens do corpus real está fora de `word/document.xml`.

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

O wrapper e o POM agregador da raiz existem só para isso: `CLAUDE.md` §9 manda
rodar da raiz. O `backend/` continua sendo um módulo independente
(`cd backend && ./mvnw verify` faz o mesmo).

O teste ponta a ponta sobe Postgres e Kafka reais via Testcontainers e percorre
upload → evento → regra → persistência → `GET`. **Não há mock de
infraestrutura**: um teste que troca o broker por um mock não prova que o
contrato do tópico funciona, que é justamente o que esta slice precisa
demonstrar.

Sete casos ponta a ponta: documento com problema, documento acessível (zero
falso positivo), imagem só no cabeçalho, caixa de texto sem alt (que **não** pode
virar problema), documento que quebra no parsing e termina em `FALHOU`, conteúdo
que não é DOCX (`422`) e análise inexistente (`404`).

Além deles, **46 testes unitários** que não precisam de Docker e rodam em
segundos — extrator (`mc:Fallback`, VML, cabeçalho e rodapé, partes de
configuração, XML hostil), validador (assinatura, limites de zip bomb), catálogo
WCAG, motor de regras e a política de falha do consumidor. Antes eles não
existiam: a suíte verde do `spike/` testava o código do spike, que já tinha
divergido do extrator de produção.

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
POST /analyses
      │
      ├─ valida o tipo REAL do conteúdo (assinatura zip + partes OOXML)
      ├─ grava analise + binário no Postgres  ── commit ──┐
      │                                                    │
      └─ publica AnaliseSolicitadaV1 ─────────────────────┘
                    │
         accessai.analise.solicitada.v1  (Kafka, KRaft)
                    │
      ┌─────────────┘
      │  consumidor idempotente (chave = eventId)
      ├─ extrai imagens de TODAS as partes do pacote
      ├─ executa o Rule Engine
      └─ grava problemas ──► GET /analyses/{id}
            │
            └─ falha permanente ──► situacao = FALHOU
```

**Falha permanente × transitória.** Pacote que não abre, binário ausente, linha
inexistente: reprocessar dá o mesmo resultado, então a análise vai para `FALHOU`
numa transação nova (`REQUIRES_NEW` — a transação original já está condenada ao
rollback) e a mensagem morre ali. Qualquer outra exceção sobe para o Kafka
reentregar: banco fora do ar não é defeito do documento do usuário. Retry com
backoff e DLT continuam sendo a Slice 3.

### Decisões que valem explicar

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
  adr/                     uma decisão por arquivo (0001–0008)
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

O `correlationId` entra no MDC no consumidor, então dá para seguir uma jornada do
upload até o último log atravessando a fronteira do tópico.

## Limitações conhecidas

Registradas aqui porque estão no código como dívida consciente, não como
descuido:

- **Sem outbox.** O evento é publicado depois do commit. Se o processo morrer
  entre commit e publicação, a análise fica em `RECEBIDA` para sempre. Slice 3.
- **Sem retry com backoff e sem DLT.** Falha permanente já vira `FALHOU` com log
  de erro; falha transitória é reentregue pelo Kafka com o comportamento padrão
  do `DefaultErrorHandler`, que não é política, é default. Slice 3.
- **Sem score.** Score por categoria é a Slice 2.
- **Sem autenticação e sem isolamento por usuário.** A migration que trouxer
  autenticação adiciona `owner_id` e o teste que prova o isolamento.
- **Binário em `bytea` no banco principal** não sobrevive à Slice 5, quando o
  ML Service for outro processo — ele não pode ler este banco (`CLAUDE.md` §5).
- **Uma única regra.** O corpus real mostrou 4 imagens em 9 documentos, todas
  sem alt utilizável. Amostra pequena para conclusão forte.
- **Fixtures e corpus são sintéticos.** Os pacotes de teste são montados em
  memória (`DocxDeTeste`), modelados no que Word, Google Docs e LibreOffice
  produzem — mas não são exports reais. A validação contra exports reais dos três
  programas continua pendente, e é a mesma crítica que este projeto faz ao
  dataset sintético em D2.
