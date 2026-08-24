# Model Card — Classificador de qualidade de texto alternativo (Modelo 1)

- **Versão:** 0.1.0
- **Status:** **treinado e medido, NÃO exportado, NÃO servido**
- **Artefato:** nenhum. `ml-service/models/` está vazio de propósito — ver
  "Por que não há artefato"
- **Relatório completo:** `ml-service/data/training_report.json` (não versionado;
  reproduzir com o comando abaixo)

```bash
accessai-treinar --dataset data/alt_texts.jsonl --rotulo-de-trabalho provisorio
```

> **A ressalva que precede qualquer número deste documento.** O rótulo usado no
> treino **não é revisado por humano**. Ele é a saída da heurística
> `dataset.coletor_alt_publico.pre_rotular`. Portanto tudo aqui mede se um
> TF-IDF + regressão logística consegue **imitar aquela heurística** — não se
> ele detecta alt ruim. O ADR 0002 §4 pede kappa de Cohen contra revisão humana
> em 150 amostras, e isso ainda não aconteceu.

---

## O que o modelo faz

Recebe o texto de um atributo `alt` e devolve uma de três classes:

| Classe | Significado |
|---|---|
| `GOOD` | descreve a imagem de forma útil |
| `WEAK` | genérico ou incompleto |
| `INSUFFICIENT` | não descreve nada |

`MISSING` **não é classe**. Alt ausente é detecção determinística do Rule Engine;
usar ML nisso violaria o `CONTRIBUTING.md` §2.

**Arquitetura:** `TfidfVectorizer` word(1,2) + char_wb(3,5), `sublinear_tf`,
`min_df=2` → `LogisticRegression(C=1.0, class_weight="balanced", max_iter=2000)`.
Nada de transformer: com ~600 amostras de alvo no ADR 0002, é a ferramenta
errada para o tamanho do dado.

## Uso pretendido e fora de escopo

**Pretendido:** enriquecer o resultado de uma análise já completa, como camada
opcional. A predição fica em tabela própria e **nunca** entra no score (§6).

**Fora de escopo, explicitamente:**

- Decidir conformidade WCAG. A predição não tem critério, não tem evidência e
  não é auditável linha a linha como uma regra.
- Substituir revisão humana de acessibilidade.
- Qualquer uso onde errar tenha custo — ver as métricas abaixo.

## Dados

749 linhas no dataset; 744 treináveis (as 5 restantes são imagens `.docx`
governamentais **sem alt**, que são caso do Rule Engine, não amostra de ML).

| Origem | Linhas | Papel |
|---|---|---|
| Wikimedia Commons | 700 | treino + validação + teste |
| Sintético de fallback | 44 | **somente treino** |
| `.docx` governamentais | 5 | nenhum (sem alt) |

**Divisão:** treino 516, validação 115, teste 113. O agrupamento é por alt
normalizado, não por documento — logo repetido em várias páginas cai inteiro de
um lado só. A mesma chave de agrupamento é reusada na validação cruzada
(`StratifiedGroupKFold`).

**As 44 sintéticas nunca são avaliadas.** Sem essa remoção a macro-F1 da
validação cruzada sobe de `0.508` para `0.709` — o modelo passaria a ser medido
sobre strings escritas neste repositório.

## Métricas

Macro-F1 é a métrica principal. Não acurácia: com classes desbalanceadas,
acurácia premia quem ignora a classe rara — e a classe rara aqui é justamente o
alt ruim, que é o que o produto precisa detectar.

### Validação (n=115)

| | macro-F1 |
|---|---|
| **Modelo** | **0,508** |
| Baseline heurístico | 0,370 |
| Baseline classe majoritária | 0,271 |

Matriz de confusão — linhas = verdadeiro, colunas = previsto:

|  | GOOD | WEAK | INSUFFICIENT |
|---|---|---|---|
| **GOOD** | 64 | 15 | 0 |
| **WEAK** | 8 | 26 | 1 |
| **INSUFFICIENT** | 0 | 1 | 0 |

| Classe | Precision | Recall | F1 | n |
|---|---|---|---|---|
| GOOD | 0,889 | 0,810 | 0,848 | 79 |
| WEAK | 0,619 | 0,743 | 0,675 | 35 |
| **INSUFFICIENT** | **0,000** | **0,000** | **0,000** | **1** |

**Recall da classe minoritária** (`INSUFFICIENT`): 0,000 — e marcado
`avaliavel: false` no relatório, porque 1 amostra está abaixo do mínimo de 5.

### Teste (n=113) — intocado durante a escolha

| | macro-F1 |
|---|---|
| **Modelo** | **0,493** |
| Baseline heurístico | 0,452 |
| Baseline classe majoritária | 0,264 |

|  | GOOD | WEAK | INSUFFICIENT |
|---|---|---|---|
| **GOOD** | 62 | 12 | 0 |
| **WEAK** | 12 | 24 | 2 |
| **INSUFFICIENT** | 0 | 1 | 0 |

| Classe | Precision | Recall | F1 | n |
|---|---|---|---|---|
| GOOD | 0,838 | 0,838 | 0,838 | 74 |
| WEAK | 0,649 | 0,632 | 0,640 | 38 |
| **INSUFFICIENT** | **0,000** | **0,000** | **0,000** | **1** |

### Validação cruzada (5 pastas, treino + validação, teste de fora)

| | média | desvio | mín | máx |
|---|---|---|---|---|
| Modelo | 0,508 | 0,098 | 0,423 | 0,672 |
| Baseline heurístico | 0,387 | 0,029 | 0,351 | 0,424 |
| Baseline majoritário | 0,272 | 0,001 | 0,271 | 0,273 |

O desvio de 0,098 é o número que importa mais que a média: com poucas centenas
de amostras, trocar a semente move a macro-F1 em quase dez pontos.

## O resultado que este model card existe para não deixar passar

**O modelo não detecta `INSUFFICIENT`. F1 = 0,000, nas duas partes.**

E o motivo é o corpus, não o classificador. Das 50 amostras `INSUFFICIENT`, 44
são sintéticas e estão **presas ao treino**. Sobram 6 reais, e a divisão colocou
**uma** em validação e **uma** em teste. Uma amostra não mede classe nenhuma:
esse `0,000` significa "o modelo errou a única que existia", não "o modelo tem
recall zero em condição realista".

Consequências diretas:

1. A macro-F1 de 0,508 é uma média de `0,848`, `0,675` e um zero medido em n=1.
   Ela **não** resume o desempenho — ela esconde que um terço dela é ruído.
2. O ganho de +0,138 sobre o baseline heurístico vem inteiramente de `GOOD` e
   `WEAK`.
3. A classe que o produto mais precisa detectar é a que o corpus menos sustenta.
   Isso é o mesmo achado do ADR 0002 — `INSUFFICIENT` é rara em acervo curado, o
   Commons devolveu 6 em 900 — aparecendo agora do lado da métrica.

## Conformidade com D2 (`docs/architecture/fase-0.md`)

D2 é a decisão que define este modelo. Item a item:

| Requisito de D2 | Estado |
|---|---|
| Três classes `GOOD`/`WEAK`/`INSUFFICIENT` | **cumprido** |
| `MISSING` não é classe do modelo | **cumprido** — alt ausente é regra do Rule Engine |
| Fonte: `alt` de HTML público real (Common Crawl e/ou Wikimedia Commons) | **cumprido** — Wikimedia Commons, licença por arquivo |
| **Rotulagem: um LLM pré-rotula o pool** | **DIVERGE** — quem pré-rotula é uma heurística determinística, não um LLM. Ver abaixo |
| Revisão humana + kappa de Cohen em 150 amostras | **pendente** — fila pronta, `rotulo` nulo nas 749 linhas |
| Volume ~600 amostras | **cumprido** — 744 treináveis |
| **Alvo de ~200 por classe** | **NÃO cumprido** — 478 / 216 / **50**, e 44 das 50 são sintéticas presas ao treino |
| Baseline classe majoritária | **cumprido** |
| Baseline heurístico: comprimento, nome de arquivo, "imagem de" | **cumprido** |
| Baseline heurístico: `alt` idêntico ao texto vizinho | **não implementado** — o contexto vizinho não chega ao treino (`Amostra.contexto_disponivel` é `False`) |
| Métrica: macro-F1 | **cumprido** |
| Métrica: matriz de confusão | **cumprido** |
| Métrica: **recall da classe minoritária** | **cumprido** — reportado dentro do veredito, com marca de não-avaliável abaixo de 5 amostras de suporte |
| Domain shift: ~100 alt texts de `.docx` públicos, gap publicado | **NÃO cumprido** — o corpus `.docx` tem 5 imagens, todas sem alt |
| Model card com a limitação "não vê a imagem" | **cumprido** — este documento |
| Modelo que não bate o baseline vai no model card como resultado | **cumprido** — `metricas.veredito` produz a frase, e o artefato não é exportado |

### A divergência que mais importa: quem pré-rotula

D2 pede **LLM pré-rotula, humano revisa**. O que existe é
`coletor_alt_publico.pre_rotular`, uma heurística determinística.

Isso não é detalhe de implementação. D2 cortou o Modelo 2 exatamente por esse
defeito: *"qualquer dataset que eu gerasse sairia da mesma heurística que o
modelo deveria superar — circular por construção"*. Pré-rotular o Modelo 1 com
heurística reintroduz a mesma circularidade, um degrau abaixo.

O que salva o número de ser totalmente circular é acidente, não desenho: a
heurística que **rotula** (`pre_rotular`) e a que serve de **baseline**
(`modelo.BaselineHeuristico`) são conjuntos de regras diferentes, com limiares
diferentes. Então o ganho de +0,138 significa "o TF-IDF imita a heurística A
melhor do que a heurística B a imita" — não "o modelo detecta alt ruim melhor
que uma regra".

Enquanto a revisão humana não acontecer, é isso que o número quer dizer. Com ela,
`rotulo` deixa de vir de qualquer heurística e a circularidade some — que é
precisamente por que o ADR 0002 §4 existe.

## Limitações assumidas

- **O modelo não vê a imagem.** Detecta padrão linguístico de inadequação. Alt
  bem escrito e completamente errado sai como `GOOD`. Não há como ele saber.
- **Domain shift não medido.** Treino em `alt` de HTML do Commons, aplicação em
  `.docx`. O ADR 0002 prevê um conjunto de teste com ~100 alt texts de `.docx`
  públicos e a publicação do gap in-domain × out-of-domain. Esse conjunto não
  existe: o corpus `.docx` coletado tem 5 imagens, todas sem alt.
- **O rótulo é heurística.** Já dito no topo, e vale repetir aqui porque é a
  limitação que contamina todas as outras.
- **Sem calibração.** `confianca` não foi calibrada; o serviço de inferência
  devolve `null` nesse campo.
- **Português e inglês misturados.** O corpus do Commons é majoritariamente em
  inglês; a aplicação alvo é em português. A pergunta 3 da `fase-0.md` — língua
  do corpus — segue sem resposta.

## Por que não há artefato

`accessai-treinar` **recusa exportar** modelo treinado sobre pré-rótulo, a menos
que se passe `--exportar-sem-revisao`. O motivo é operacional: o ML Service
carrega `ml-service/models/` na subida, e um `.joblib` ali faria toda a Slice 5
passar a responder `usouHeuristica: false` — anunciando predição de modelo onde
há heurística imitada. A recusa vem **antes** da comparação com baseline: modelo
que supera o baseline e foi treinado sobre pré-rótulo é o caso mais perigoso dos
dois, porque passa no critério numérico.

Enquanto isso, toda predição servida vem do `BaselineHeuristico`, e cada resposta
declara isso.

## O que muda este documento

1. **Revisão humana das 150 amostras** (`accessai-revisar`), com kappa ≥ 0,60.
   Então `--rotulo-de-trabalho humano` passa a ser o caminho, e os números aqui
   viram medida de qualidade em vez de medida de imitação.
2. **Mais `INSUFFICIENT` real coletado** (`accessai-coletar-web`), até a classe
   ter amostra suficiente em validação e teste para a métrica dizer algo.
3. **Conjunto de teste `.docx`** para medir o domain shift.
4. **ADR 0002 sair de PROPOSTA.**

## Reprodutibilidade

- Semente: 42, em todo o caminho (divisão, pipeline, validação cruzada)
- Python 3.14.3, scikit-learn 1.9.0, joblib 1.5.3
- Integridade do dataset conferida por `accessai-auditar-slices` antes do treino
