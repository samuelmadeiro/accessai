# Slice 4 — Dataset, divisão sem vazamento, coleta e treino (ml-service)

> **Procedência desta entrada.** O texto foi rascunhado em par com o Claude, a
> partir dos ADRs e do código, e **revisado e adotado por mim** — as posições
> aqui são as minhas. Registrado porque o `CONTRIBUTING.md` §1 pede a entrada
> com as minhas palavras, e omitir como ela foi escrita seria o mesmo tipo de
> silêncio que o §1 existe para impedir.

- **Estado:** `pytest` verde — 389 testes; `ruff` e `mypy --strict` limpos.
  `./mvnw verify` verde — 178 unitários e 15 E2E
- **Critério de pronto do §7:** confusion matrix e baseline documentados; modelo
  pior que baseline reportado como tal — **cumprido**, ver
  [o model card](../ml/model-card-alt-quality.md)
- **Ressalva que não pode sumir:** `models/` está vazio e **nenhuma das 749
  linhas do dataset tem `rotulo`**. A métrica que fecha o critério foi medida
  sobre `rotulo_provisorio`, que é uma heurística: ela mede imitação, não
  qualidade de alt. O kappa do ADR 0002 §4 continua por medir.

---

## O que foi construído

| Peça | Arquivo |
|---|---|
| Extração OOXML | `dataset/ooxml.py`, `dataset/corpus.py` |
| Montagem do JSONL | `dataset/montagem.py`, `dataset/cli.py` (`accessai-dataset`) |
| Divisão sem vazamento | `dataset/divisao.py` |
| Coleta do Commons | `dataset/coletor_alt_publico.py` (`accessai-coletar`) |
| Coleta web mirando `INSUFFICIENT` | `dataset/coletor_web.py` (`accessai-coletar-web`) |
| Fallback sintético | `dataset/gerador_insufficient.py` |
| Revisão humana e kappa | `dataset/revisao.py`, `dataset/cli_revisao.py` (`accessai-revisar`) |
| Treino e relatório | `training/train.py` (`accessai-treinar`), `training/dados.py`, `training/modelo.py`, `training/metricas.py` |
| Validação cruzada | `training/validacao.py` |
| Auditoria de integridade | `auditoria/auditar_slices.py` (`accessai-auditar-slices`) |
| Decisão registrada | `docs/adr/0002-procedencia-do-dataset.md` (**PROPOSTA**), `docs/adr/0008-extracao-por-xml-direto.md` |

## Estado do corpus

749 linhas, e a composição importa mais que o total:

| Slice de origem | Linhas | Papel no treino |
|---|---|---|
| `.docx` governamentais | 5 | nenhum — **todas sem alt** |
| Wikimedia Commons | 700 | treino + validação + teste |
| Sintético de fallback | 44 | **somente treino** |

Pré-rótulos: 478 `GOOD`, 216 `WEAK`, 50 `INSUFFICIENT`. Rótulos humanos: **zero**.

## Decisões que valem explicar

1. **A varredura é por elemento de imagem, não por parágrafo.** `pic:pic` e
   `v:imagedata` direto; varrer `w:p` duplicava imagem ancorada em caixa de
   texto (`w:txbxContent`).
2. **Sem lista branca de partes XML.** `PARTES_COM_CONTEUDO` divergiria em
   silêncio do motor Java — partes como `commentsDocument.xml` sumiriam de um
   lado só. A lista é de exclusão, alinhada com o Java.
3. **Teto de descompressão de 32 MB por parte.** Arquivo enviado por usuário é
   hostil (§5); zip bomb é o caso fácil de esquecer.
4. **Agrupamento por alt normalizado, não por documento.** Logo de rodapé
   aparece em toda página: dividir por arquivo deixaria o mesmo texto dos dois
   lados e inflaria a métrica sem o modelo ter aprendido nada.
5. **O pré-rótulo vai para `rotulo_provisorio`, e `rotulo` fica nulo.** Gravar o
   palpite da heurística em `rotulo` faria o treino aprender a heurística que
   ele deveria superar — circular por construção, que é o que o ADR 0002 recusa.
6. **A amostra sintética entra só no treino, nunca na avaliação.** Sem essa
   remoção, a macro-F1 sobe de `0.508` para `0.709` neste corpus — e o número
   passa a dizer que o modelo decorou uma lista que está no repositório.
7. **O treino recusa dataset sem rótulo, em vez de treinar do mesmo jeito.**
   Pipeline que segue em frente com zero amostra produz um `.joblib` que parece
   modelo, reporta métrica de nada e mente para quem achar o arquivo depois.
8. **Modelo pior que baseline não é exportado** — só com
   `--exportar-pior-que-baseline`. Artefato ruim parado numa pasta acaba servido
   em produção por quem só viu o nome do arquivo.

## O critério do §7, medido

*Confusion matrix e baseline documentados; modelo pior que baseline reportado
como tal.* Isso agora existe, e está em
[`docs/ml/model-card-alt-quality.md`](../ml/model-card-alt-quality.md).

O caminho foi `--rotulo-de-trabalho provisorio`: treinar sobre `rotulo_provisorio`
com a procedência carimbada no relatório e no artefato. **Não é a métrica do ADR
0002** — o rótulo é a saída de `pre_rotular`, então o número mede se o
classificador imita aquela heurística, não se ele detecta alt ruim.

Validação (n=115), teste (n=113), 5 pastas de validação cruzada:

| | validação | teste | validação cruzada |
|---|---|---|---|
| **Modelo** | **0,508** | **0,493** | **0,508 ± 0,098** |
| Baseline heurístico | 0,370 | 0,452 | 0,387 ± 0,029 |
| Baseline majoritário | 0,271 | 0,264 | 0,272 ± 0,001 |

`MODELO SUPERA OS BASELINES: macro-F1 0.508 contra 0.370 da heuristica (+0.138).`

### E o resultado que importa mais que esse

**O modelo não detecta `INSUFFICIENT`. F1 = 0,000, em validação e em teste.**

Das 50 amostras `INSUFFICIENT`, 44 são sintéticas e estão presas ao treino.
Sobram 6 reais, e a divisão pôs **uma** em validação e **uma** em teste. O zero
não significa "recall zero em condição realista" — significa que o modelo errou
a única amostra que existia, e que **um terço da macro-F1 é ruído de n=1**.

A classe que o produto mais precisa detectar é a que o corpus menos sustenta.
É o achado do ADR 0002 — `INSUFFICIENT` é rara em acervo curado — reaparecendo
do lado da métrica.

### O artefato não foi exportado, de propósito

`accessai-treinar` recusa gravar o `.joblib` quando o rótulo não é revisado, a
menos que se passe `--exportar-sem-revisao`. O ML Service carrega `models/` na
subida: um artefato ali faria toda a Slice 5 responder `usouHeuristica: false`
para uma predição que é heurística imitada. A recusa vem **antes** da comparação
com baseline — modelo que supera o baseline e foi treinado sobre pré-rótulo é o
caso mais perigoso dos dois, porque passa no critério numérico.

## O que continua aberto

O §7 não menciona kappa, mas o ADR 0002 §4 sim, e é ele que separa "o pipeline
funciona" de "o modelo vale alguma coisa":

1. **ADR 0002 sai de PROPOSTA.** Perguntas 3, 4 e 7 de
   `docs/architecture/fase-0.md` — língua do corpus, tempo real por semana para
   rotular, confirmação do corte do Modelo 2. Decisões minhas, não de código.
2. **Revisão humana das 150 amostras** (`accessai-revisar`), kappa ≥ 0,60. A
   fila está pronta e auditada: 50 por classe, nenhuma faltando.
3. **Mais `INSUFFICIENT` real** (`accessai-coletar-web`), até a classe ter
   amostra suficiente fora do treino para a métrica dizer algo.
4. **`--rotulo-de-trabalho humano`** passa a ser o caminho, e os números viram
   medida de qualidade em vez de medida de imitação.

Só depois disso o `usouHeuristica: true` da Slice 5 pode virar `false`.

## O que a auditoria de slices garante antes do passo 2

`accessai-auditar-slices` roda cinco eixos sobre o dataset e sai com código 1 em
qualquer divergência. Hoje: 29 PASS, 1 AVISO, 0 FAIL. Ela existe porque revisar
150 amostras à mão em cima de um arquivo que ninguém conferiu gasta o tempo do
revisor num arquivo que ainda vai mudar.

## As perguntas do contrato

> As quatro perguntas que o §1 exige: o que foi construído, por que dessa forma,
> qual alternativa foi descartada e por quê, e o que eu ainda não sei defender.

### O que eu construí

Um dataset que sabe dizer de onde cada linha veio, e um treino que se recusa a
rodar sem rótulo.

A extração lê OOXML por XML direto, do mesmo jeito que o backend Java (ADR
0008), e o corpus `.docx` devolveu 5 imagens — **todas sem alt**. Isso não é
falha da extração, é o achado: o corpus real não sustentava o Modelo 1. Daí a
coleta do Wikimedia Commons (700 linhas com licença declarada), a coleta web
mirando a classe rara, e as 44 sintéticas de fallback marcadas linha a linha.

A parte que eu defendo com mais convicção é a divisão. Ela agrupa por alt
normalizado, não por documento, e esse agrupamento sobrevive até a validação
cruzada — `StratifiedGroupKFold`, não `StratifiedKFold`. Junto com a remoção das
sintéticas do lado avaliado, é o que impede a métrica de medir memorização.

O que eu **não** construí é o modelo. E a razão de ele não existir não é técnica.

### Por que o dataset não é sintético, sendo que 44 linhas são

Porque as 44 estão declaradas, contadas e presas ao treino — e porque elas são
6% de um corpus cuja outra ponta é real, licenciada e rastreável até a URL.

O ADR 0002 abre dizendo que dado gerado apresentado como real destrói a
credibilidade do projeto. Três coisas separam o `gerador_insufficient` disso, e
nenhuma é opcional: `origem_do_dado: "sintetico_fallback"` em cada linha, a
contagem no relatório, e a entrada **apenas** no treino. Métrica medida sobre
string escrita neste repositório não mede detecção de alt ruim no mundo — mede
se o modelo decorou uma lista.

Elas existem por um motivo estreito: `INSUFFICIENT` é rara em acervo curado — o
Commons devolveu 6 em 900 —, e sem a classe rara o classificador não aprende a
detectar exatamente o que o produto precisa detectar.

### Qual alternativa eu descartei e por quê

| Alternativa | Por que não |
|---|---|
| **Varredura por parágrafo (`w:p`)** | Duplicava imagem ancorada em caixa de texto. |
| **Lista branca de partes XML** | Divergência silenciosa com o motor Java. Duas fontes de verdade para "o que é conteúdo". |
| **Divisão por arquivo/documento** | Texto idêntico repetido (logotipo) cairia dos dois lados. Vazamento pela porta dos fundos. |
| **`train_test_split` no `dados.py`** | Jogaria fora o agrupamento já calculado e devolveria o mesmo vazamento. A coluna `divisao` é reaproveitada de propósito. |
| **Transformer fine-tunado** | Com as ~600 amostras de alvo do ADR 0002, é a ferramenta errada para o tamanho do dado — e reabriria decisão fechada no §3. |
| **LLM rotulando tudo, sem revisão humana** | O ADR 0002 §4 pede rotulagem híbrida **declarada**, com kappa medido. Rótulo de LLM sem revisão é o dataset sintético de novo, com outro nome. |
| **Treinar assim mesmo, com pré-rótulo como rótulo** | Produz número. O número mede a heurística contra ela mesma, e o baseline heurístico ficaria empatado com o modelo por construção. |

### O que eu ainda não sei defender numa entrevista

Candidatos objetivos — os buracos são reais, escolher quais são os meus é o que
falta:

1. **"Qual a acurácia do seu modelo?"** Não tenho modelo. Tenho `0.508 ± 0.098`
   de macro-F1 medida sobre `rotulo_provisorio`, que é a própria heurística.
   Sei por que esse número está aí; não ensaiei dizer isso sem parecer que estou
   desconversando.
2. **Por que TF-IDF word + char_wb, e não embeddings.** A resposta é tamanho do
   dado e custo de defesa. Dita em voz alta ainda soa como desculpa.
3. **O modelo não vê a imagem.** Ele detecta padrão linguístico de inadequação;
   alt bem escrito e completamente errado sai como `GOOD`. Está declarado, mas
   é a limitação que mais parece defeito para quem ouve.
4. **Domain shift.** Treino em `alt` de HTML, aplicação em `.docx`. O ADR prevê
   publicar o gap in-domain × out-of-domain, e esse número não existe.
5. **Por que 44 sintéticas e não 200.** O teto de 50 do gerador tem argumento
   ("dez variações ensinam o que cinquenta ensinariam"), mas não tem medida.
6. **O kappa não diz que o rótulo está certo.** Revisor e heurística podem estar
   consistentemente errados juntos. Sei a frase; não sei o que responder quando
   perguntarem o que eu faria a respeito.

## Dívida consciente que segue aberta

- **Não há modelo, e a slice não fecha sem ele.** `models/` vazio, D2 em
  PROPOSTA. Bloqueio de decisão, não de código.
- **Zero rótulo humano.** As 150 da revisão dependem de tempo real meu — a
  pergunta 4 da `fase-0.md` é exatamente sobre isso, e continua sem resposta.
- **`origem_do_dado` não existe em 705 das 749 linhas.** O campo nasceu com o
  `coletor_web`; Commons e `.docx` se identificam por `fonte`. A auditoria
  deriva a origem e registra a ausência como AVISO.
- **Domain shift não medido.** Falta o conjunto de teste com ~100 alt texts de
  `.docx` públicos que o ADR 0002 prevê.
- **O corpus `.docx` não sustenta nada sozinho.** 5 imagens, todas sem alt — que
  é detecção determinística do Rule Engine, não amostra de ML.
