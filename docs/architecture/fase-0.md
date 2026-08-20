# Fase 0 — Auditoria e decisões

Status: **Parte A e B entregues. D1 aprovado (DOCX) com três condições — ver
"Condições da aprovação de D1". Parte C e D aguardam D2–D6.**
Data: 2026-08-19

---

## Parte A — Auditoria

Repositório vazio: contém apenas `CONTRIBUTING.md`. Sem código,
sem `pom.xml`, sem migrations, sem testes, sem git inicializado. Nada a
reaproveitar, nada a deletar.

Única verificação feita contra a realidade externa: Spring Boot 4.1.0 é GA
desde 10/06/2026 e Java 25 é LTS. A stack de §3 do `CONTRIBUTING.md` é válida.

---

## Parte B — As seis decisões

### D1 — Formato do documento no MVP

| Opção | Parser Java | "Por que não usar X?" | Regras interessantes | Risco |
|---|---|---|---|---|
| HTML | jsoup | **axe-core, Pa11y, Lighthouse.** Resposta fraca. | Baixo — a maioria vira reimplementação de axe | Baixo |
| PDF | Apache PDFBox | **veraPDF** (open source, valida PDF/UA em Java) | Médio — mas PDF sem tags dá score sempre ≈ 0 | Alto |
| DOCX | Apache POI (XWPF) | **Verificador de Acessibilidade do Word** — cobre quase o mesmo conjunto de regras. | Alto | Médio |

**Recomendação: DOCX.**

Três razões, em ordem de peso:

1. **A pergunta "por que não usar a ferramenta que já existe" tem resposta
   preparada.** Em HTML eu teria que argumentar contra o axe-core, e o
   argumento honesto ("axe precisa de DOM vivo, eu faço análise estática de
   fonte") é fraco — bastaria rodar Playwright + axe.

   Em DOCX o concorrente **existe** e é o Verificador de Acessibilidade
   embutido no Word: ele já detecta alt ausente, tabela sem linha de cabeçalho,
   estrutura de títulos, texto de link vago e contraste. É o axe-core do DOCX e
   a pergunta vai vir. A resposta, decorada antes da entrevista:

   - **Não tem API.** Vive dentro do Word, para um humano, um arquivo por vez —
     não dá para colocar em pipeline nem em CI.
   - **Não roda em lote.** Auditar 400 documentos de um portal significa 400
     aberturas manuais.
   - **Não dá score.** Devolve uma lista de itens, sem nota, sem ponderação por
     categoria, sem rastro de cada ponto perdido até uma evidência.
   - **Não cita WCAG.** Diz "adicionar texto alternativo", não
     "1.1.1 Non-text Content, nível A". Sem isso não há relatório de
     conformidade.
   - **Não prioriza nem recomenda.** É onde entram as camadas de ML e IA — o
     Word não infere se um alt existente é ruim, só se ele existe.

   Ou seja: o Word responde "está faltando alt?"; o AccessAI responde "quão
   acessível é este documento, por quê, contra qual critério, e o que fazer
   primeiro". Sobreposição parcial no Rule Engine, produto diferente.
2. **O Rule Engine deixa de ser wrapper.** A regra mais interessante do projeto
   só existe em DOCX: *heading formatado à mão* — parágrafo em negrito, fonte
   maior, sem estilo de Heading aplicado. Visualmente é um título; para o leitor
   de tela não existe. Detectar isso é comparar estilo aplicado contra
   formatação direta no XML. Nenhuma biblioteca faz. Mapeia para WCAG 1.3.1
   (Info and Relationships, nível A).
3. **A categoria "Visual 20%" do score fica computável — sem renderizar nada.**
   Em HTML isso exigiria um motor de layout; em PDF, o graphics state do
   content stream. Em DOCX os valores estão no XML. Sem essa categoria o score
   de §6 fica com um quinto vazio.

   **Mas é a parte mais cara do projeto, e não é perto.** A cor efetiva de um
   run vem de uma cascata: formatação direta → estilo de caractere → estilo de
   parágrafo → `docDefaults` → tema. `themeColor` com `themeTint`/`themeShade`
   ainda exige a aritmética de tint/shade sobre a cor do tema. E o fundo pode
   vir de `w:shd` no run, no parágrafo, na célula da tabela ou no documento —
   quatro níveis a resolver antes de calcular uma única razão de contraste.

   Duas consequências práticas:
   - **Contraste vai para a Slice 2, nunca para a 1.** A Slice 1 leva a regra
     mais barata que existe (alt ausente).
   - **Provavelmente não uso a XWPF do POI para isso.** `.docx` é um zip;
     ler `document.xml`, `styles.xml` e `theme1.xml` direto resolve herança de
     forma explícita. A API de alto nível do POI não foi desenhada para
     cascata de estilo, e brigar com ela custa mais que o parsing direto.
     Decisão final na Slice 2, com um protótipo dos dois lados.

Regras do MVP. Os critérios abaixo são WCAG, mas **a base normativa do
`criteria.json` é o WCAG2ICT** — cada linha carrega a aplicabilidade a
documento não-web e a substituição de termo usada. Ver condição **C-1**, que é
bloqueante: sem essa camada, citar 1.1.1 num `.docx` é analogia não
documentada.

| Regra | Critério | Nível | Slice |
|---|---|---|---|
| Imagem sem texto alternativo | 1.1.1 Non-text Content | A | 1 |
| Heading formatado à mão em vez de estilo | 1.3.1 Info and Relationships | A | 2 |
| Hierarquia de headings quebrada (H1 para H3) | 1.3.1 | A | 2 |
| Tabela sem linha de cabeçalho | 1.3.1 | A | 2 |
| Idioma do documento ausente | 3.1.1 Language of Page | A | 2 |
| Texto de hiperlink não descritivo ("clique aqui") | 2.4.4 Link Purpose | A | 2 |
| Lista simulada com hífen digitado | 1.3.1 | A | 2 |
| Contraste texto/fundo abaixo de 4.5:1 | 1.4.3 Contrast (Minimum) | AA | 2, por último |

**O que a escolha custa:** POI tem API mais áspera que jsoup, e parte do acesso
(alt text no `docPr`, flag de linha de cabeçalho em `tblHeader`) desce ao nível
dos schemas OOXML. Vou validar os acessores exatos na Slice 1 antes de afirmar
qualquer assinatura de método. Custa também um público menor: menos gente se
importa com DOCX do que com HTML. E custa a cascata de estilos descrita acima,
que é o maior sumidouro de tempo do projeto.

**Alternativa mais simples, se você quiser velocidade acima de diferenciação:**
HTML com jsoup. Entrega a Slice 1 em talvez metade do tempo, e todo exemplo de
WCAG na internet é HTML. O preço é a pergunta do axe-core na entrevista.

---


---

### D2 — Procedência do dataset

**Conclusão dura primeiro: o Modelo 2 (severidade contextual) não tem dataset
viável e deve ser cortado.** Não existe corpus público de "quão grave é este
problema neste documento", o rótulo é subjetivo, e o Rule Engine já atribui
severidade deterministicamente. Qualquer dataset que eu gerasse sairia da mesma
heurística que o modelo deveria superar — circular por construção. Um modelo
bem feito vale mais que dois falsos.

**Modelo 1 (qualidade de texto alternativo) é viável.** Classificação em três
classes: `GOOD` / `WEAK` / `INSUFFICIENT`.

**`MISSING` não é classe deste modelo.** Alt ausente é detecção determinística
— é a regra da Slice 1. Colocá-la no classificador seria usar ML onde uma regra
resolve, o que §2 do `CONTRIBUTING.md` proíbe. O modelo só é invocado quando existe
texto alternativo para julgar.

- **Fonte dos exemplos:** atributos `alt` extraídos de HTML público real
  (amostra de Common Crawl e/ou Wikimedia Commons, ambos licenciados).
  São textos alternativos escritos por humanos, incluindo muitos ruins —
  exatamente a distribuição que interessa.
- **Rotulagem:** híbrido declarado. Um LLM pré-rotula o pool; eu reviso e
  corrijo manualmente. Reporto a taxa de correção e o **kappa de Cohen** num
  subconjunto de 150 amostras rotuladas só por mim. O model card diz, em texto
  claro, que os rótulos são LLM-gerados com concordância humana medida.
- **Volume mínimo:** ~600 amostras, alvo de ~200 por classe. Com três classes
  em vez de quatro isso continua tratável; menos que isso e o intervalo de
  confiança da métrica engole a diferença para o baseline.
- **Limitação que vai no model card, em texto claro:** o modelo **não vê a
  imagem**. Ele detecta padrões linguísticos de inadequação (comprimento,
  nome de arquivo, redundância com o texto vizinho, genericidade). Ele **não
  verifica se o alt descreve corretamente o conteúdo da imagem** — um alt
  bem escrito e completamente errado é classificado como `GOOD`. Isso é uma
  fronteira do produto, não um bug, e precisa estar declarada.
- **Domain shift — este é o ponto honesto que ninguém vê de graça:** eu treino
  em `alt` de HTML e aplico em alt text de DOCX. São domínios diferentes.
  Mitigação: montar à mão um conjunto de teste com ~100 alt texts de arquivos
  `.docx` públicos (governo, universidades) e **reportar o gap entre teste
  in-domain e out-of-domain**. Gap medido e publicado é um bom assunto de
  entrevista; gap escondido é uma mentira.
- **Baseline burro que o modelo precisa bater:** (a) classe majoritária e
  (b) heurística — comprimento, `alt` parecido com nome de arquivo
  (`img_01.jpg`), presença de "imagem de"/"foto de", `alt` idêntico ao texto
  vizinho.
- **Métrica de decisão:** macro-F1 (classe desbalanceada — accuracy não serve),
  mais matriz de confusão e recall da classe minoritária. **Se o modelo não
  bater o baseline, isso vai no model card como resultado, não como fracasso
  escondido.**

---

### D3 — Kafka se justifica?

Argumento honesto, e ele é **médio, não forte**:

**A favor:**

1. **Consumidor em outra linguagem.** O backend é Java, o ML Service é Python.
   Uma fila em Postgres transformaria o schema do banco principal em contrato
   compartilhado entre dois runtimes: toda migration passaria a ser uma
   mudança de API para o Python. A alternativa sem fila seria HTTP síncrono
   Java para Python, que troca retry, replay e backpressure por timeout.
2. **Ciclo de deploy independente.** Subir uma versão nova do modelo não pode
   exigir deploy do backend, e vice-versa. O tópico é a fronteira estável entre
   os dois; o schema do evento versionado é o contrato.
3. **Replay.** Quando o modelo v2 sair, eu reprocesso todo o histórico relendo
   o tópico a partir do offset 0, sem reupload. Fila em Postgres faz isso mal —
   a mensagem foi consumida e apagada. Isso também dá fan-out: o AI Gateway da
   Slice 6 consome o mesmo tópico com seu próprio offset, sem tocar no ML.

**Argumento que eu tinha escrito e removi:** "fila em Postgres violaria a
invariante de §5 (ML Service não acessa o banco principal)". É circular — a
invariante fui eu que escrevi. Um entrevistador aponta isso em dois segundos.
Os três acima se sustentam sem citar as próprias regras do projeto.

**Contra, e eu digo isso na entrevista antes de perguntarem:** throughput
**não** é o argumento. Este projeto não tem volume. Kafka custa um broker no
compose, mais complexidade operacional e testes mais lentos.

**Veredito: Kafka fica**, justificado por fan-out + desacoplamento entre
runtimes + replay. Nunca por "é escalável". `@Async` está descartado de saída:
morre com a JVM e não alcança o Python.

---

### D4 — Autenticação e tenancy

**Multiusuário, single-tenant por usuário, isolamento por linha.**

- Toda tabela de domínio tem `owner_id` (FK para `users`).
- Isolamento aplicado no repositório com métodos explícitos
  (`findByIdAndOwnerId`), não com filtro global do Hibernate — filtro global é
  fácil de esquecer e o esquecimento é silencioso.
- **A prova de isolamento é um teste**, não uma afirmação: teste de integração
  em que o usuário A recebe 404 ao pedir a análise do usuário B. Esse teste é o
  entregável desta decisão.
- JWT stateless (Spring Security), sem sessão em servidor.
- **Redis entra em três lugares:** rate limit por usuário (upload é caro:
  parsing + custo de LLM), chave de deduplicação dos consumers Kafka (§5), e
  contador de gasto mensal de LLM (ver D5). Não guarda sessão.

**Cortado por over-engineering:** organizações, RBAC, convites, refresh token
rotation, MFA. Multi-tenant real com hierarquia org→usuário é complexidade sem
público num projeto solo.

---

### D5 — LLM: provider, custo, teto

**Provider: Anthropic Claude API**, atrás da interface `AiProvider` de §5.

Preços atuais (Claude API, por milhão de tokens):

| Modelo | Input | Output |
|---|---|---|
| Claude Haiku 4.5 | US$ 1,00 | US$ 5,00 |
| Claude Sonnet 5 | US$ 3,00 (US$ 2,00 promocional até 31/08/2026) | US$ 15,00 (US$ 10,00 promo) |
| Claude Opus 5 | US$ 5,00 | US$ 25,00 |

**Custo estimado por análise.** Entrada: prompt de sistema + trecho da tabela
WCAG (~1.500 tokens) + resultado real da análise em JSON (~2.500 tokens).
Saída: recomendações estruturadas (~1.200 tokens).

- Haiku 4.5: 4.000 × $1/1M + 1.200 × $5/1M ≈ **US$ 0,010 por análise**
- Sonnet 5: 4.000 × $3/1M + 1.200 × $15/1M ≈ **US$ 0,030 por análise**

**Recomendação: Haiku 4.5 para as recomendações** (a tarefa é explicar
resultados já calculados — não exige raciocínio profundo), **Sonnet 5 para o
copilot conversacional** da Slice 7, onde a qualidade da conversa aparece. Isso
é uma escolha sua sobre custo × qualidade, não minha — os números estão acima.

**Sobre prompt caching, uma ressalva que importa:** o prefixo mínimo cacheável
é de **4.096 tokens no Haiku 4.5** e 1.024 no Sonnet 5. Abaixo do limite a API
**não retorna erro** — processa normalmente e simplesmente não cacheia. Meu
prompt de sistema (~1.500 tokens) não vai cachear no Haiku.

Duas saídas, decidir na Slice 6:

- **Engordar o prefixo estável** movendo a tabela completa de critérios WCAG e
  os few-shots para dentro dele — passa de 4.096 naturalmente e vira leitura a
  0,1× do preço de entrada.
- **Aceitar que não cacheia** e ajustar a estimativa para cima (o custo já é
  ~1 centavo por análise, então isso é defensável).

**Instrumentar `usage.cache_read_input_tokens` desde o primeiro dia**, como
métrica exportada, não como `System.out`. Se esse campo for zero em requisições
com prefixo idêntico, o cache não está funcionando — e o lugar de descobrir
isso é o dashboard, não a fatura.

**Teto: US$ 10/mês** (≈ 1.000 análises no Haiku). Contador incremental no Redis
alimentado pelo campo `usage` de cada resposta da API.

**O que acontece ao bater o teto:** nada quebra. A análise continua funcionando
inteira — Rule Engine e ML são locais e gratuitos. Só a seção de recomendações
retorna `AI_BUDGET_EXHAUSTED`. **A IA é camada de enriquecimento opcional,
nunca caminho crítico** — que é exatamente a arquitetura de §2.

**Testes sem gastar:** `FakeAiProvider` implementando `AiProvider`, devolvendo
fixtures. Zero rede no CI. Teste de contrato contra a API real marcado
`@Tag("live")` e excluído do build padrão. Os testes de guardrail (§7, Slice 6)
rodam contra o fake, com um golden set de perguntas sem base na análise.

---

### D6 — Fora de escopo

Explicitamente **não** será feito:

1. Qualquer formato além de DOCX (nada de PDF, HTML, ODT).
2. Correção automática do documento — não geramos `.docx` corrigido, só
   recomendação textual.
3. **Modelo 2 (severidade contextual por ML)** — cortado em D2 por falta de
   dataset honesto.
4. Multi-tenancy organizacional, RBAC, convites, papéis.
5. Refresh token rotation, login social OAuth, MFA.
6. Deploy em cloud, Kubernetes, pipeline multi-ambiente, alta disponibilidade.
   O alvo é `docker compose up` local.
7. Fine-tuning de LLM, RAG sobre corpus externo, banco vetorial.
8. i18n do produto e das recomendações — uma língua só.
9. Análise de mídia embutida (vídeo, áudio, legendas).
10. Benchmark de carga e tuning de performance (§5: não otimizar antes de medir).
11. Verificação de contraste que exija renderização — só o computável do XML.

---

## Condições da aprovação de D1

D1 aprovado: **DOCX**. Três condições, todas bloqueantes para a Parte C.

### C-1 — A base do `criteria.json` é o WCAG2ICT, não a WCAG direta

**O buraco:** a WCAG foi escrita para conteúdo web. Aplicar 1.1.1 ou 2.4.6 a um
`.docx` é analogia. Analogia não documentada é exatamente o "inventar critério"
que §6 do `CONTRIBUTING.md` proíbe — e eu estava fazendo isso citando WCAG2ICT de
passagem, como nota de rodapé, em vez de tratá-lo como a régua.

**A régua correta** (verificada, não citada de memória):

> *Guidance on Applying WCAG 2 to Non-Web Information and Communications
> Technologies (WCAG2ICT)* — W3C Group Note, **11 de dezembro de 2025**.
> https://www.w3.org/TR/wcag2ict-22/

Cobre WCAG 2.0, 2.1 e 2.2, níveis A e AA, aplicados a documentos e software
não-web. Descreve, critério a critério, quando ele vale sem alteração, quando
exige substituição de termo ("web page" → "document"/"software", "set of web
pages" → "set of documents") e quando não se aplica.

**Consequência 1 — o `criteria.json` ganha a camada ICT.** Cada entrada
registra a aplicabilidade e a substituição usada, para que todo ponto perdido
no score rastreie até uma justificativa publicada. Forma:

```json
{
  "fonte": {
    "wcag": { "versao": "2.2", "url": "https://www.w3.org/TR/WCAG22/" },
    "ict": {
      "documento": "WCAG2ICT",
      "status": "W3C Group Note — informativo, nao normativo",
      "publicadoEm": "2025-12-11",
      "abrangencia": "WCAG 2.0, 2.1 e 2.2, niveis A e AA",
      "url": "https://www.w3.org/TR/wcag2ict-22/"
    }
  },
  "criterios": [
    {
      "id": "1.1.1",
      "titulo": "Non-text Content",
      "nivel": "A",
      "aplicabilidadeIct": "direta",
      "substituicoes": [],
      "notaIct": "O criterio nao contem termo especifico de web, portanto se aplica a documento nao-web sem substituicao."
    }
  ]
}
```

O 1.1.1 é `direta` justamente porque seu texto não usa "web page". Um critério
que use o termo apareceria como `com_substituicao`, com a troca registrada em
`substituicoes` — por exemplo `{ "termo": "web page", "substituto": "documento" }`.

`aplicabilidadeIct` é um enum fechado: `direta` | `com_substituicao` |
`inaplicavel`. Um critério marcado `inaplicavel` **não pode** gerar
violação — no máximo recomendação, conforme §6. Isso vira validação no build,
não convenção.

**Consequência 2 — o disclaimer é obrigatório e literal.** README, relatório
gerado e resposta de entrevista dizem a mesma frase: *o WCAG2ICT é uma Group
Note informativa do W3C, não é norma e não estabelece requisitos de
conformidade.* Omitir isso seria vender rigor que o documento não tem.

Dito o disclaimer, ele vira a melhor resposta ao cético: **a régua não é minha,
é a do W3C, versionada e citável linha a linha.**

**Consequência 3 — citar a regulação que consome essa régua**, para mostrar que
não é exercício acadêmico:

- **EN 301 549** — norma europeia de acessibilidade de TIC usada em compras
  públicas, com requisitos para documentos não-web referenciando WCAG.
- **Section 508** (EUA) — incorpora WCAG 2.0 nível AA por referência, aplicável
  a documentos eletrônicos.
- **LBI — Lei 13.146/2015** (Brasil), que é o gancho local e conversa direto
  com o corpus da condição C-3.

Numeração exata de cláusula e artigo eu verifico contra a fonte na hora de
escrever o README. Não vou chutar cláusula em documento que existe para não
chutar critério.

### C-2 — Timebox do spike de extração, com critério de falha escrito agora

DOCX ganha o mérito na extração e é exatamente onde o projeto encalha. O
critério de aborto tem que existir enquanto ainda é barato abortar.

**Timebox: 3 dias úteis.** Escopo do spike:

1. Abrir o `.docx` como zip e ler `document.xml`.
2. Localizar os `wp:docPr` e extrair `@descr` (o texto alternativo) de forma
   confiável, incluindo imagens inline e ancoradas.
3. Protótipo lado a lado: POI/XWPF × parsing direto do XML, medindo linhas de
   código e clareza para o caso de herança de estilo.

**Sucesso = os três abaixo, juntos:**

- Extrai alt text correto de 10 `.docx` reais e heterogêneos (Word, Google Docs
  exportado, LibreOffice) — inclusive distinguindo alt ausente de alt vazio.
- A abordagem escolhida entre POI e XML direto está decidida com evidência.
- Existe um teste automatizado verde para os 10 arquivos.

**Falha = qualquer um destes:** estourou 3 dias; ou alt text sai errado/instável
em exportadores diferentes; ou nenhuma das duas abordagens fecha limpa.

**Consequência da falha, escrita agora para não ser negociada depois: cai para
HTML com jsoup, sem discussão.** A alternativa HTML já está desenhada em D1 e
custa a pergunta do axe-core — que é um preço conhecido. Duas semanas
investidas num parser que não fecha é um preço desconhecido.

### C-3 — Corpus de `.docx` reais, com procedência e licença

Faltou nas seis decisões e é pré-requisito da Slice 2. Testar as regras contra
documentos que eu mesmo fabriquei tem o mesmo vício do dataset sintético de D2:
eu escrevo o documento que a minha regra acerta.

**Fonte:** setor público brasileiro, que publica `.docx` em volume — editais,
formulários, atas, material de universidade federal e de prefeitura.

**Meta:** ~50 documentos para a Slice 2, heterogêneos em origem e exportador.

**Entregável:** `scripts/fetch-corpus.py` (baixa e cataloga) +
`datasets/README.md` com procedência e licença **por arquivo**, não por lote —
a licença de material público brasileiro varia por órgão e não dá para
generalizar.

**Uma ressalva que o corpus traz junto, e que precisa de decisão sua:**
documento público real contém dado pessoal — nome, CPF, matrícula em ata e
edital. Comprometer os binários no git resolve reprodutibilidade e cria um
problema de privacidade permanente e irreversível no histórico.

**Proposta:** versionar o **manifesto**, não os binários. `datasets/corpus/`
guarda um JSON com URL de origem, hash SHA-256, órgão, data de coleta, licença
e o resultado esperado da análise; o script rebaixa os arquivos sob demanda
para `datasets/corpus/raw/`, que entra no `.gitignore`. Isso preserva
reprodutibilidade (hash), respeita §10 (`datasets/` versionado) e não publica
dado pessoal de terceiro.

**O ganho narrativo é o maior do projeto:** acessibilidade de documento público
não é hipótese de portfólio. É um edital que um candidato cego não consegue ler.

---


---

## Onde isto está over-engineered

Duas coisas, ditas como pedido na seção "Restrições desta entrega":

- **Nove slices é ambicioso para um projeto solo. Se o tempo apertar, corte a
  Slice 7 e proteja a 8.** A 7 (copilot conversacional) é a descartável: a
  Slice 6 já prova o AI Gateway, a fundamentação nos resultados reais e os
  guardrails testados — que é o conteúdo técnico. Chat em cima disso todo mundo
  tem, e não acrescenta argumento de entrevista.

  A 8 não pode sair. Uma ferramenta que audita acessibilidade com uma interface
  inacessível é autogol: a primeira coisa que um entrevistador atento faz é
  navegar por teclado. Melhor um frontend pequeno e realmente acessível do que
  um dashboard grande e quebrado.
- **Kafka é justificável, não necessário.** Se em algum momento ele virar
  fricção maior que o valor, a saída não é `@Async` — é Redis Streams. Mas o
  custo de troca provavelmente não compensa.

---

## Perguntas para o Samuel

1. ~~D1: DOCX ou HTML?~~ **Respondido: DOCX**, com as três condições C-1, C-2 e
   C-3 registradas acima.
2. **Corpus: versionar manifesto em vez de binários (C-3)?** É a única
   contraproposta minha às suas condições. Comprometer `.docx` público real no
   git coloca dado pessoal de terceiro num histórico que não dá para limpar.
3. **Língua do corpus de alt text: inglês ou português?** Inglês tem corpus
   muito maior e todos os exemplos de WCAG. **Mas C-3 mudou o peso desta
   pergunta:** se o corpus de teste é documento público brasileiro, um modelo
   treinado em `alt` em inglês aplicado a `.docx` em português soma um segundo
   domain shift ao que já registrei em D2 — idioma além de formato. Minha
   inclinação agora é português, aceitando corpus menor.
4. **Quanto tempo real por semana você tem para rotular ~600 amostras?** Estimo
   2–3 horas no total. Se for inviável, D2 muda: o modelo vira 100%
   LLM-rotulado com auditoria menor, e o viés aumenta.
5. **Você já tem chave da Anthropic API e aceita o teto de US$ 10/mês?**
6. **Haiku 4.5 em tudo (mais barato) ou Sonnet 5 no copilot (melhor conversa)?**
   Aceito qualquer das duas — só não quero decidir seu orçamento por você.
7. **Confirma o corte do Modelo 2?** É a decisão mais impopular deste documento
   e a que mais reduz escopo.

---

## Estado da Parte C e D

D1 está aprovado e as três condições estão registradas (C-1, C-2, C-3). O que
ainda trava:

| Bloco | Depende de | Estado |
|---|---|---|
| C1 — Arquitetura | D1, D3, D4 | **Liberado** |
| C2 — Contrato Kafka | D3 | **Liberado** |
| C3 — Modelo de dados | D1, D4, C-1 | **Liberado** |
| C6 — Slice 1 detalhada | D1, C-2 | **Liberado** |
| C4 — Plano de ML | Perguntas 3, 4, 7 | Travado |
| C5 — Plano de IA | Perguntas 5, 6 | Travado |
| ADRs | um por decisão já tomada | **Escritos** em `docs/adr/`: 0001 (D1), 0003 (D3), 0004 (D4), 0006 (D6), 0007 (C-1), 0008 (extração). D2 e D5 estão como *proposta*, aguardando as perguntas 3–7. |

Entrego C1, C2, C3 e C6 mais os quatro ADRs no próximo passo — em duas partes,
respeitando o limite de ~300 linhas por revisão de §8. C4 e C5 saem quando as
perguntas 3 a 7 forem respondidas.
