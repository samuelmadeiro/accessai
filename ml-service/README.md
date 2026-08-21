# ml-service

Módulo Python do AccessAI: dataset, treino e inferência.

Ele **não acessa o banco principal** (CONTRIBUTING.md §5). A única entrada de
dados hoje é o corpus em `datasets/corpus/`, lido por arquivo.

## Estado

| Parte | Situação |
|---|---|
| `dataset/` | Funcional. Monta o dataset de texto alternativo a partir do corpus real. |
| `training/` | Vazio. Bloqueado pela decisão D2 — ver abaixo. |
| `inference/` | Vazio. É a entrega da Slice 5. |

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
