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

## Coletar alt text público (Wikimedia Commons)

```bash
python -m accessai_ml.dataset.coletor_alt_publico --limite 1000
```

Existe porque o corpus `.docx` tem **zero** imagens com alt: sem matéria-prima
não há o que rotular. O Commons é a fonte do ADR 0002 §3, com licença declarada
por arquivo e API oficial — nada de raspar HTML.

Duas fontes de texto, e a diferença importa:

| `--fonte` | Campo da API | O que é |
|---|---|---|
| `legenda` | `entityterms.label` | *caption* estruturada, uma linha. O mais parecido com alt de verdade. Existe em poucos arquivos. |
| `descricao` | `extmetadata.ImageDescription` | prosa de catálogo, com HTML dentro. Existe em quase todo arquivo, e é mais longa que um alt típico. |
| `ambos` (padrão) | os dois | legenda preferida quando existe; `origem_do_alt` grava de onde saiu cada linha. |

**O rótulo não vem preenchido.** A pré-classificação determinística vai em
`rotulo_provisorio` e `rotulo` fica `null`, porque o ADR 0002 §4 exige rotulagem
híbrida declarada — LLM pré-rotula, humano revisa, com taxa de correção e kappa
de Cohen em 150 amostras. `--rotular-com-heurística` promove o pré-rótulo para
`rotulo` e grava `origem_do_rotulo: "heuristica"` na linha; com ele o treino
roda, mas mede concordância com uma regra, não qualidade de alt text.

Etiqueta de API: `User-Agent` identificando a ferramenta, `maxlag=5`, pausa de
0,5 s entre pedidos (`--pausa`) e recuo exponencial com jitter em 429 e 5xx,
respeitando `Retry-After`. 4xx que não seja 429 **não** é retentado — pedido
malformado não melhora sozinho, e insistir nele é o que faz a fundação bloquear
o IP.

Códigos de saída: `0` sucesso, `4` nenhuma amostra sobreviveu aos filtros,
`6` a saída já existe (use `--sobrescrever`).

## Completar a classe `INSUFFICIENT` (`accessai-coletar-web`)

```bash
accessai-coletar-web --cota 50 --url https://exemplo.org/pagina
```

A coleta do Commons trouxe **6 `INSUFFICIENT` em 900**. Descrição de catálogo é
escrita por quem se importa com o catálogo; alt ruim mora em HTML comum, onde
ninguém revisou. Sem essa classe o classificador não aprende a detectar
justamente o que o produto precisa detectar.

**O filtro tem polaridade oposta à do coletor do Commons.** Lá, nome de arquivo é
ruído a descartar; aqui, nome de arquivo **é** a amostra. Os dois módulos não
compartilham o filtro de propósito.

| Motivo | Exemplo |
|---|---|
| `nome_de_arquivo` | `IMG_0001.jpg`, `foto (3).jpeg` |
| `hash` | `3f2a9c1e8b7d`, `a1b2c3d4e5f6.png` |
| `termo_generico_solto` | `imagem`, `banner`, `sem titulo` |
| `sem_letra` | `12345`, `...` |
| `curto_demais` | `abc` |

Termo genérico só conta **sozinho**: "banner de divulgação do edital de 2025"
descreve e passa.

**Etiqueta de robô.** `robots.txt` consultado uma vez por host, host que proíbe é
pulado sem tentativa, e **não há flag para ignorar** — coletor com botão de
desligar a etiqueta é coletor que vai ser usado com o botão desligado. Além
disso: `User-Agent` identificado, pausa de 1 s, recuo exponencial em 429/5xx,
`Retry-After` respeitado, timeout em toda leitura, teto de 5 MB por página. Não
segue link nem descobre página sozinho: lê exatamente as URLs informadas.

### Fallback sintético

Sem URL, ou quando a coleta não fecha a cota, `gerador_insufficient` completa com
até 50 variações determinísticas (nome de câmera, hash de CDN, GIF de
espaçamento, placeholder, termo genérico), intercaladas para que `--cota 8` não
entregue só nome de arquivo.

Três coisas impedem isso de virar o dataset fabricado que o ADR 0002 proíbe:

1. `origem_do_dado: "sintetico_fallback"` em cada linha, e a contagem em
   `data/relatorio_coleta_web.json`.
2. **Só entram no treino.** A parte é forçada, não sorteada pelo hash — e a
   validação cruzada as remove da metade avaliada de cada pasta.
3. São fallback: com URL disponível, o coletor coleta e elas ficam de fora
   (`--sem-fallback` desliga de vez).

O ponto 2 não é decorativo. Antes dele, a macro-F1 da validação cruzada dava
**0,709**; com as sintéticas fora do lado avaliado, **0,508** — o mesmo número da
validação retida. Os 0,2 de diferença eram o modelo sendo medido sobre strings
escritas neste repositório.

A mesclagem no `data/alt_texts.jsonl` **preserva tudo que já existe** e deduplica
por alt normalizado — a mesma chave de `dataset.divisao`, para o vazamento não
voltar pela porta dos fundos.

Códigos de saída: `0` cota atingida, `2` sem URL e sem fallback, `3` entrada
inválida, `4` cota não atingida.

## Revisar os rótulos (`accessai-revisar`)

```bash
accessai-revisar --dataset data/alt_texts.jsonl
```

A outra metade do ADR 0002 §4: o coletor pré-rotula, esta CLI é onde o humano
revisa. Mostra um alt por vez e espera uma tecla.

```
[1|g] GOOD    [2|w] WEAK    [3|i] INSUFFICIENT    [s] pular    [q] sair
```

**O pré-rótulo fica escondido por padrão.** Quem vê o palpite da heurística antes
de julgar tende a concordar com ele, e a concordância medida deixa de medir
concordância — mede ancoragem. `--mostrar-pre-rotulo` existe para depuração e
invalida o kappa da sessão para efeito de ADR.

A fila é **balanceada e embaralhada**: 50 por classe (`--por-classe`), misturadas
no fim. Cinquenta `GOOD` seguidos ensinam a sequência ao revisor, e a
concordância vira artefato da ordem de apresentação. Classe com menos que a cota
entrega o que tem, e a falta sai em `faltando_por_classe`.

O progresso é gravado ao sair por qualquer caminho — `q`, fim da fila, Ctrl-C,
EOF ou exceção inesperada. O JSONL é reescrito por arquivo temporário na mesma
pasta e `os.replace`; cada linha revisada ganha `rotulo`, `origem_do_rotulo:
"humano"` e `data_revisao`.

### `data/relatorio_revisao.json`

Conta o **acumulado do arquivo**, não a sessão: 150 amostras não cabem numa
sentada, e um relatório que contasse só a última rodada mandaria refazer
trabalho já feito.

| Campo | O que é |
|---|---|
| `total_revisado` | linhas com `origem_do_rotulo: "humano"` |
| `taxa_correcao` | divergências ÷ total |
| `kappa_cohen` | concordância pré-rótulo × humano, descontado o acaso |
| `matriz_de_confusao` | linhas = pré-rótulo, colunas = humano |
| `atende_adr0002` | `true` se `total ≥ 150` **e** `kappa ≥ 0,60` |

**Por que kappa e não acurácia.** Com 70% das amostras em `GOOD`, um revisor que
carimbasse `GOOD` em tudo acertaria 70% e não teria revisado nada — o kappa
devolve 0 nesse caso.

**O que passar autoriza.** Kappa alto sustenta promover o pré-rótulo no resto do
dataset. Não diz que o rótulo está certo: revisor e heurística podem estar
consistentemente errados juntos, e o kappa não vê isso.

Códigos de saída: `0` sucesso, `3` dataset inválido, `4` nada pendente e nada
revisado.

## Auditar os slices antes da revisão (`accessai-auditar-slices`)

```bash
accessai-auditar-slices --dataset data/alt_texts.jsonl
```

Varredura de integridade sobre o dataset, os contratos do pipeline e a fila de
revisão, para que ninguém gaste 150 julgamentos humanos em cima de um arquivo
que ainda vai mudar. Cinco eixos:

| Eixo | O que confere |
|---|---|
| A | contagem por slice (5 governamentais sem alt, 700 do Commons, 44 sintéticas, 749 no total), `id` e alt normalizado sem duplicata, distribuição de classe e de comprimento |
| B | a sintética é marcada, fica presa ao treino, e `training.validacao` de fato a remove do lado avaliado de cada pasta |
| C | `amostrar_balanceado` entrega as 150 do ADR, embaralhadas, sem vazar o pré-rótulo para a tela |
| D | enum `GOOD`/`WEAK`/`INSUFFICIENT` (nenhum `SUFFICIENT` sobrevivente) e vocabulário de `origem_do_rotulo` |
| E | a saída de terminal reconfigura UTF-8 com `errors="replace"` |

O Eixo B é **dinâmico**: as pastas do `StratifiedGroupKFold` são recalculadas
pelo auditor com a mesma semente, e o que `validar` reporta ter avaliado é
comparado com o que ele deveria ter avaliado. Confiar no número que o módulo
auditado imprime não auditaria nada — se o filtro sumisse, ele reportaria
"0 sintéticas removidas" com a mesma sinceridade. Neste corpus a macro-F1 sai
`0.508 +/- 0.098` com o filtro no lugar e `0.709` sem ele; bater no segundo
valor é FAIL.

**O rótulo de trabalho é o `rotulo_provisorio`.** Enquanto `rotulo` for nulo em
todas as linhas, a macro-F1 do Eixo B mede a heurística contra ela mesma. O
relatório declara isso em `rotulo_de_trabalho` e conta os rótulos humanos — não
é validação do pré-rótulo, e é exatamente por isso que a revisão existe.

### `data/relatorio_auditoria_slices.json`

Quebra por slice (totais, distribuição, papel no treino, `hash_dos_ids`), uma
entrada por invariante com esperado/obtido, o `sha256` do dataset auditado e o
booleano `pronto_para_revisao_humana`.

Códigos de saída: `0` tudo íntegro, `1` qualquer divergência de contagem,
vazamento de sintética para a validação, ou quebra de contrato de esquema.

## Treinar o modelo

```bash
python -m accessai_ml.training.train --dataset data/alt_texts.jsonl
```

**Hoje isso sai com código 3 e não escreve nada**, porque o dataset tem zero
amostras com `rotulo`. É o comportamento correto: um pipeline que segue em
frente com zero amostra grava um `.joblib` que parece modelo e reporta métrica
de nada.

Códigos de saída: `0` sucesso, `3` dataset inválido ou sem rótulo, `5` o modelo
não superou os baselines (o artefato **não** é exportado).

### `--rotulo-de-trabalho`: de que coluna sai o rótulo

```bash
accessai-treinar --dataset data/alt_texts.jsonl --rotulo-de-trabalho provisorio
```

| Valor | Lê | O que a métrica significa |
|---|---|---|
| `humano` (padrão) | `rotulo` | qualidade de alt — **a métrica do ADR 0002** |
| `provisorio` | `rotulo_provisorio` | se o classificador **imita** a heurística `pre_rotular` |

O caminho `provisorio` existe para exercer o pipeline inteiro — treino,
baselines, matriz de confusão, veredito — antes de a revisão humana acontecer.
O rótulo **é** a heurística, então o número não diz nada sobre detecção de alt
ruim. A procedência viaja para o `training_report.json` e para dentro do
`.joblib`, em `rotulo_de_trabalho`, com a ressalva junto.

**Modelo treinado sobre pré-rótulo não é exportado.** O ML Service carrega
`models/` na subida, e um artefato ali faria toda a Slice 5 responder
`usouHeuristica: false` para uma predição que é heurística imitada. A recusa vem
**antes** da comparação com baseline — modelo que supera o baseline e foi
treinado sobre pré-rótulo é o caso mais perigoso, porque passa no critério
numérico. `--exportar-sem-revisao` força, para inspeção.

Os resultados desse caminho, com a leitura do que eles significam e do que não
significam, estão em [`docs/ml/model-card-alt-quality.md`](../docs/ml/model-card-alt-quality.md).

### Validação cruzada

`StratifiedGroupKFold` sobre treino + validação, com o teste de fora. O grupo é
o mesmo de `dataset.divisao` — alt normalizado — porque `StratifiedKFold`
sozinho partiria o grupo e inflaria a métrica, e `GroupKFold` sozinho deixaria
uma pasta sem a classe rara.

O que importa no resultado é o **desvio**: ele diz se o ganho sobre o baseline
sobrevive à reamostragem ou se foi sorte de uma divisão específica. `--pastas`
ajusta o número de pastas; o coletor reduz sozinho quando a classe mais rara ou
o número de grupos não sustenta o valor pedido, e registra a redução no
relatório. Pasta que falha ao ajustar entra em `pastas_com_falha` sem derrubar o
treino: validação cruzada é diagnóstico, não critério de exportação.

`--min-df` controla o corte de frequência do TF-IDF (padrão `2`): termo que
aparece numa amostra só costuma ser nome próprio ou erro de digitação, e vira
atalho que o modelo memoriza.

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

### `POST /v1/predict:batch`

Os textos alternativos de um documento numa chamada só, com **um resultado por
item, na mesma ordem do pedido** — a ordem é o contrato, porque o pedido não
carrega identificador. Teto de 200 itens, no schema e não num `if`: pedido fora
do contrato é recusado com 422 antes de encostar no modelo.

O ganho não é só evitar `n` conexões. `predict_proba` sobre a lista vetoriza os
`n` textos de uma vez, e o TF-IDF de palavra somado ao de caractere é a parte
cara. E no cenário degradado, que era a preocupação real: vinte imagens passam a
pagar **um** timeout de 1,5 s, não vinte.

A degradação é do lote inteiro, não de um item — sem identificador no pedido, um
resultado a menos torna impossível saber qual imagem ficou de fora, e associar
por posição daria predição trocada.


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
