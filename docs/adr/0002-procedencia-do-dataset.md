# ADR 0002 - Procedencia do dataset e corte do Modelo 2

- **Status:** PROPOSTA - aguarda decisao de Samuel (perguntas 3, 4 e 7 de `fase-0.md`)
- **Decisao original:** D2 de `docs/architecture/fase-0.md`

> Registrado como proposta, e nao como decisao aceita: nao se escreve ADR para
> decisao que ainda nao foi tomada. Vira `aceita` quando as perguntas abertas
> forem respondidas.

## Contexto

"Crie um dataset realista" e uma armadilha: dataset gerado por LLM e apresentado
como real destroi a credibilidade do projeto numa entrevista.

## Proposta

1. **Cortar o Modelo 2 (severidade contextual).** Nao existe corpus publico de
   "quao grave e este problema neste documento", o rotulo e subjetivo e o Rule
   Engine ja atribui severidade deterministicamente. Qualquer dataset sairia da
   mesma heuristica que o modelo deveria superar - circular por construcao.
2. **Manter o Modelo 1 (qualidade de texto alternativo)**, com tres classes:
   `GOOD` / `WEAK` / `INSUFFICIENT`. `MISSING` nao e classe: alt ausente e
   deteccao deterministica, e usar ML nisso violaria CONTRIBUTING.md secao 2.
3. **Fonte:** `alt` de HTML publico real (Common Crawl e/ou Wikimedia Commons).
4. **Rotulagem hibrida declarada:** LLM pre-rotula, humano revisa; reportar taxa
   de correcao e kappa de Cohen em 150 amostras.
5. **Volume minimo:** ~600 amostras, ~200 por classe.
6. **Baseline a bater:** classe majoritaria e heuristica (comprimento, nome de
   arquivo, "imagem de", igual ao texto vizinho). Metrica: macro-F1.

## Nota de implementacao (Slice 4) — o que foi construido diverge da proposta

Registrado aqui, e nao so no codigo, porque a divergencia toca o argumento
central deste ADR.

**Quem pre-rotula e uma heuristica deterministica, nao um LLM.** A proposta 4
diz "LLM pre-rotula, humano revisa". O que existe e
`dataset.coletor_alt_publico.pre_rotular`, regra a mao. Dois motivos, e nenhum
deles anula a proposta:

1. O provider de LLM esta em PROPOSTA no ADR 0005, aguardando as perguntas 5 e 6
   de `fase-0.md` — nao ha chave nem teto de custo aprovados.
2. Rotular 749 linhas com LLM sem teto definido gastaria orcamento que ninguem
   autorizou.

**O custo dessa troca precisa ficar dito:** este ADR corta o Modelo 2 porque
"qualquer dataset que eu gerasse sairia da mesma heuristica que o modelo deveria
superar — circular por construcao". Pre-rotular o Modelo 1 com heuristica
reintroduz a mesma circularidade. A revisao humana da secao 4 e o que a desfaz —
e enquanto ela nao acontecer, `rotulo` continua nulo e o treino so roda com
`--rotulo-de-trabalho provisorio`, que carimba a ressalva no relatorio e recusa
exportar artefato.

**Alvo de ~200 por classe nao foi atingido em INSUFFICIENT:** 478 / 216 / 50, e
44 das 50 sao sinteticas presas ao treino. Sobra 1 amostra real da classe em
validacao e 1 em teste — abaixo do que mede qualquer coisa.

**O conjunto de teste de ~100 alt texts de `.docx` publicos nao existe.** O
corpus `.docx` coletado tem 5 imagens, todas sem alt. Sem ele, o gap
in-domain x out-of-domain que esta proposta promete publicar segue sem medida.

Os numeros e a leitura deles estao em `docs/ml/model-card-alt-quality.md`.

## Consequencias

**Assumidas e declaradas no model card.** O modelo nao ve a imagem: detecta
padrao linguistico de inadequacao, nao verifica se o alt descreve a imagem. Alt
bem escrito e completamente errado sai como `GOOD`.

**Domain shift.** Treino em `alt` de HTML, aplicacao em DOCX. Mitigacao: conjunto
de teste com ~100 alt texts de `.docx` publicos e o gap in-domain x
out-of-domain publicado.

**Risco aberto.** A coleta de 2026-08-19 achou 5 imagens em 9 documentos, todas
sem alt utilizavel: o corpus real ainda nao sustenta o Modelo 1. (O numero e o
de `ml-service/data/relatorio.json`, `imagens: 5` / `imagens_sem_alt: 5`; a
primeira versao deste paragrafo dizia 4.)
