# ml-service

Módulo Python do AccessAI: dataset, treino e inferência.

Ele **não acessa o banco principal** (CONTRIBUTING.md §5). A única entrada de
dados hoje é o corpus em `datasets/corpus/`, lido por arquivo.

## Estado

| Parte | Situação |
|---|---|
| `dataset/` | Funcional. Monta o dataset de texto alternativo a partir do corpus real. |
| `training/` | Implementado e testado. **Sem dado para rodar** — ver D2 abaixo. |
| `inference/` | API FastAPI funcionando. Sobe **sem modelo** e responde pela heurística, declarando isso. |

## Setup

```bash
py -3.14 -m venv .venv
```

Depois, com o ambiente ativo:

```bash
pip install -e ".[dev]"
```

## Montar o dataset

```bash
python -m accessai_ml.dataset.cli --corpus ../datasets/corpus
```

O caminho do corpus vem de `--corpus` ou de `ACCESSAI_CORPUS`; a saída, de
`--saida` ou de `ACCESSAI_DATASET_SAIDA` (padrão `./data`). Não há caminho
derivado da posição do arquivo no disco: aquilo funciona em instalação editável
e aponta para dentro do `site-packages` quando o pacote é instalado de verdade —
que é exatamente como o console script `accessai-dataset` roda.

Escreve `data/alt_texts.jsonl` e `data/relatorio.json`. Nada disso vai para o
git: o derivado é função do corpus (já rastreado por
`datasets/corpus/manifest.json`) mais o código desta pasta, e o texto extraído
de documento público real carrega dado pessoal que não deve entrar no histórico.

Códigos de saída: `0` sucesso, `2` corpus inválido (sha256 divergente do
manifesto), `3` nenhum alt distinto, `4` corpus não informado. O `3` existe para
que um pipeline não siga em frente treinando em nada e reportando métrica de
nada.

## Treinar o modelo

```bash
python -m accessai_ml.training.train --dataset data/alt_texts.jsonl
```

**Hoje isso sai com código 3 e não escreve nada**, porque o dataset tem zero
amostras rotuladas. É o comportamento correto: um pipeline que segue em frente
com zero amostra grava um `.joblib` que parece modelo e reporta métrica de nada.

Códigos de saída: `0` sucesso, `3` dataset inválido ou sem rótulo, `5` o modelo
não superou os baselines (o artefato **não** é exportado).

### Baselines não são enfeite

O `CONTRIBUTING.md` §7 define a Slice 4 como pronta quando há "confusion matrix e
baseline documentados; modelo pior que baseline é reportado como tal". Por isso
o treino sempre ajusta dois baselines nos mesmos dados, do ADR 0002:

| Baseline | O que é |
|---|---|
| Classe majoritária | Piso absoluto. Não bater isso significa não ter aprendido nada. |
| Heurística | Comprimento, expressão genérica, nome de arquivo. Se regras alcançam o modelo, use as regras (§2). |

O artefato **não é exportado** quando o modelo não supera os dois. Um `.joblib`
pior que o baseline parado numa pasta acaba servido em produção por alguém que
só viu o nome do arquivo. `--exportar-pior-que-baseline` libera, para inspeção.

### Modelo

TF-IDF de palavra `(1,2)` somado a TF-IDF de caractere `char_wb (3,5)`, seguido
de `LogisticRegression` com `class_weight="balanced"`.

Regressão logística e não `LinearSVC` porque a Slice 6 usa a confiança da
predição para ajustar severidade, e SVM linear não entrega probabilidade
calibrável sem um envelope extra. Com algumas centenas de amostras curtas, a
diferença de acurácia entre os dois é ruído.

O char n-gram existe para pegar nome de arquivo e ruído tipográfico
(`IMG_0421.jpg`, `image1`) sem depender de tokenização — alt text tem poucas
palavras, e é em sub-palavra que mora o sinal.

A métrica principal é **macro-F1**, não acurácia: com classes desbalanceadas a
acurácia premia quem ignora a classe rara, e a classe rara aqui é justamente o
alt ruim, que é o que o produto precisa detectar.

### Artefato

`models/accessibility_classifier.joblib` carrega o pipeline mais versão do
modelo, rótulos, hiperparâmetros, contagem por divisão, estratégia de divisão,
veredito e as versões de Python, scikit-learn e joblib usadas na serialização.
Binário sem procedência é pior que nenhum.

## Servir a inferência

```bash
uvicorn accessai_ml.inference.main:app --port 8000
```

`ACCESSAI_MODELOS` aponta a pasta do artefato (padrão `models`).

| Endpoint | O que faz |
|---|---|
| `GET /health` | Sempre 200 enquanto o processo responde. `modeloCarregado` diz se há artefato, com `motivo` ao lado. |
| `POST /v1/predict` | Classifica um lote de alt texts. |

`/health` **não** devolve 503 sem modelo: o serviço continua útil pela
heurística, e 503 faria a orquestração reiniciar um container saudável em loop.

### O que `usouHeuristica` significa

Hoje não há artefato em `models/`, então **toda** predicação vem da heurística e
sai marcada `usouHeuristica: true`, com `confianca: null`.

Marcar é obrigatório, não cortesia. Um serviço chamado `/predict` que devolve o
resultado de um punhado de regras sem dizer que são regras faz o consumidor
acreditar que existe um modelo — o "ML que é if/else" que a §1 proíbe. E
`confianca` fica nulo porque regra não tem probabilidade; um `1.0` ali faria o
Java tratar heurística como modelo confiante.

O serviço degrada em vez de cair: artefato ausente, ilegível, sem as chaves
obrigatórias, ou que exploda na predição — todos caem para a heurística com o
motivo registrado em `/health`.

## Latência de inferência

Critério de pronto da Slice 5 (`CONTRIBUTING.md` §7). Medida com
`bench/medir_latencia.py`, 1000 iterações após 50 de aquecimento, cinco alt
texts de tamanhos diferentes.

```bash
python -m bench.medir_latencia --modo processo
python -m bench.medir_latencia --modo http --url http://127.0.0.1:8000
```

| Camada | Origem | p50 | p95 | p99 | máx |
|---|---|---|---|---|---|
| Em processo | heurística | 0,005 ms | 0,005 ms | 0,006 ms | 0,03 ms |
| Em processo | modelo | 1,59 ms | 4,34 ms | 8,68 ms | 30,6 ms |
| Cliente Java → HTTP | heurística | 2,26 ms | 4,45 ms | **7,11 ms** | 13,6 ms |
| Cliente Java → HTTP | modelo | 4,19 ms | 6,76 ms | **9,22 ms** | 14,3 ms |
| Cliente Java → serviço fora do ar | fallback | 1,44 ms | 4,24 ms | 6,46 ms | 6,7 ms |

**O caminho real de hoje é a terceira linha:** `models/` está vazio, então toda
predição vem da heurística. p99 de 7 ms.

Três leituras que os números dão:

1. **O timeout de 1500 ms tem folga de duas ordens de grandeza.** Mesmo com
   modelo carregado, o p99 é 9 ms — 160× abaixo do teto. O timeout não está
   apertado; está protegendo contra serviço travado, não contra serviço lento.
2. **O modelo custa ~330× mais que a heurística** em processo (1,59 ms contra
   0,005 ms). TF-IDF de palavra somado ao de caractere não é barato. Em termos
   absolutos continua irrelevante perto do transporte.
3. **Reuso de conexão importa mais que a predição.** O medidor Python via
   `urllib` deu p99 de 30 ms; o cliente Java, 9 ms — mesmo serviço, mesma
   máquina. `urllib.urlopen` abre conexão nova a cada chamada, o `HttpClient` do
   JDK reusa. A diferença é maior que o custo de inferir.

**O que estes números não dizem.** Tudo é loopback na mesma máquina: sem salto
de rede, sem contenção, sem TLS. Em dois containers do compose sobe; entre hosts
sobe mais. O valor aqui é o piso e a proporção, não o absoluto.

Um detalhe do fallback: 1,4 ms é o custo de *conexão recusada em loopback*, que
é imediata. Em rede real, host inalcançável gasta até o `connect-timeout-ms` de
500 ms antes de desistir — o pior caso do fallback é 500 ms, não 1,4 ms.

## Testes e qualidade

```bash
pytest
```

```bash
ruff check . && mypy
```

## O que o dataset é — e o que não é

O JSONL traz **os alt texts que existem nos documentos**, com contexto e
procedência. O campo `rotulo` é sempre `null`: ninguém rotulou. Um valor default
ali viraria rótulo de mentira no treino.

Imagem sem alt também sai, marcada. Ela não vira amostra — alt ausente é
detecção determinística e já é regra no Rule Engine (CONTRIBUTING.md §2) — mas
entra na contagem, porque a razão entre imagens com e sem alt é o número que diz
se o corpus sustenta um modelo.

## Resultado da coleta atual

```
9 documentos | 5 imagens | 0 com alt | 0 amostras rotuláveis
```

O corpus real de `.docx` públicos brasileiros **não sustenta o Modelo 1**. Isso
já estava previsto como risco aberto no ADR 0002 e está agora medido de forma
reproduzível, não estimada.

## Decisões que travam o treino

`docs/adr/0002-procedencia-do-dataset.md` está com status **PROPOSTA**: D2 —
procedência do dataset — não foi decidida. O ADR propõe treinar com `alt` de
HTML público (Common Crawl / Wikimedia Commons) e usar `.docx` apenas como
conjunto de teste fora de domínio. Enquanto isso não for aceito, `training/`
fica vazio de propósito.

## Divisão treino/validação/teste

Por **alt text normalizado**, não por documento nem por amostra. Amostra sem alt
cai no grupo do próprio documento.

A versão anterior agrupava por documento. Isso resolve vazamento de vocabulário
entre documentos, mas não resolve o vazamento que de fato ameaça este modelo:
texto idêntico nos dois lados da divisão. Na sondagem da fonte escolhida no
ADR 0002, 93 alts não vazios tinham **10 distintos** — logo de site repetido em
toda página. Com agrupamento por documento, `"Escoteiros do Brasil"` apareceria
no treino e no teste, e a macro-F1 subiria sem o modelo ter aprendido nada.

A troca é consciente e tem custo: **documento deixou de ser atômico**. Duas
imagens do mesmo edital podem cair em partes diferentes quando têm alts
diferentes. Por isso o relatório traz `divisoes` no plural por documento — um
campo singular esconderia isso de quem audita. Para um classificador de texto
curto, onde a amostra *é* o alt, duplicata exata é o risco dominante e a
repetição de vocabulário é de segunda ordem.

A normalização ignora caixa, espaços repetidos e acentuação: `"BRASÃO  da
República"` e `"brasao da republica"` são o mesmo grupo.

Determinística, derivada de um hash da própria chave — sem semente global e sem
embaralhamento. Chave nova cai onde o próprio hash mandar, sem remexer as que já
estavam, e a métrica de duas execuções continua comparável.

O veredito conta alts **distintos**, não ocorrências: 600 cópias da mesma frase
não são 600 amostras.
