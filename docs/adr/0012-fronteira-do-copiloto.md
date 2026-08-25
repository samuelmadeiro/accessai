# ADR 0012 - Fronteira do copiloto: conversa sobre a analise, nunca sobre o documento

- **Status:** aceita (preparacao da Slice 7)
- **Reverte:** a instrucao de cortar a Slice 7 registrada em
  `docs/architecture/fase-0.md`, secao "Onde isto esta over-engineered"
- **Depende de:** ADR 0004 (isolamento por linha), ADR 0005 (provider de LLM,
  em PROPOSTA), ADR 0009 (score por principio)

## Contexto

A Slice 7 estava cortada por escrito. O argumento da `fase-0.md` era bom: a
Slice 6 ja prova o AI Gateway, a fundamentacao nos resultados reais e o
guardrail testado, e "chat em cima disso todo mundo tem".

O argumento vale contra um copiloto **que recebe o documento**. Esse copiloto
seria um segundo analisador — um caminho paralelo em que o LLM olha o `.docx` e
opina sobre acessibilidade, sem regra, sem criterio versionado e sem evidencia.
Ele reabriria a fronteira do `CONTRIBUTING.md` secao 2 pelo lado mais caro: o
produto passaria a ter duas fontes de achado, uma deterministica e uma
generativa, e nada no sistema diria ao usuario qual delas produziu o que ele
esta lendo.

O redesenho tira exatamente isso. **O copiloto nao recebe o documento. Recebe a
`Analise` ja produzida.** O contexto dele sao os `Problema` que o motor
deterministico gerou, com os `Fundamento` que a Slice 6 ja construiu. Ele
conversa SOBRE o resultado; nao produz achado novo.

Com a objecao original removida, a slice deixa de ser "chat em cima do que a 6
ja provou" e passa a ser o lugar onde a fronteira entre regra e LLM fica travada
por teste em vez de por disciplina.

Este ADR e escrito **antes** de existir codigo do copiloto, de proposito.
Fronteira decidida depois que o codigo existe e fronteira negociada com o codigo
que ja a violou.

## Decisao

Cinco invariantes. Elas nao sao estilo: violacao e bug (`CONTRIBUTING.md`
secao 5).

### I1 - O copiloto nao produz achado

Nao cria `Problema`, nao altera score, nao introduz criterio WCAG. O score
continua sendo soma ponderada de penalidades deterministicas (ADR 0009), e a
lista de problemas continua saindo do `MotorDeRegras`.

Consequencia pratica: nenhuma classe do copiloto conhece `Problema`,
`ProblemaRepository`, `MotorDeRegras` ou `CalculadoraDeScore`.

`ScoreDaAnalise` **e** permitido. Ele viaja dentro de `VisaoDaAnalise` e ler o
score e legitimo — o que I1 proibe e calcular e gravar, nao ler. Proibir o tipo
de leitura faria a guarda passar a ser sobre outra coisa.

### I2 - O contexto e a `Analise`, nunca o documento

Nada de `DocumentoExtraido`, `VarredorDoPacote`, coletor ou pacote OOXML. O tipo
de entrada do copiloto e `VisaoDaAnalise`, que e record de leitura e ja e o que
o `ServicoDeRecomendacoes` usa.

Isto e o que impede o copiloto de virar um segundo analisador. Sem acesso ao
documento, ele **nao tem como** opinar sobre o que a regra nao mediu: a
impossibilidade e estrutural, e nao uma instrucao no prompt que o modelo pode
ignorar.

### I3 - Pergunta fora do escopo da analise e recusada

Vale o guardrail que ja existe (`GuardrailDeFundamentacao`), nas duas pontas.
Palpite plausivel sobre problema que ninguem mediu e o modo de falha que o
`CONTRIBUTING.md` secao 1 proibe.

**Consequencia que precisa estar escrita agora:**
`conferirEntrada` lanca quando a analise nao tem achado nenhum. Em multi-turno
isso significa que **o copiloto nao conversa sobre documento limpo** — a conversa
inteira e recusada, e nao so a primeira pergunta.

E intencional. Documento sem problema nenhum nao rende conversa fundamentada:
renderia conselho generico de acessibilidade apresentado como analise deste
documento. Registrado aqui porque, sem registro, isso parece defeito e alguem
"conserta" na Slice 7.

### I4 - `AiProvider` continua porta unica

A Slice 7 **estende** a porta para multi-turno; nao abre uma segunda via de
saida. O `GatewayDeIa` continua o unico chamador, com a mesma ordem de quatro
etapas: guardrail de entrada, teto de gasto, provider, guardrail de saida.

Um `HttpClient` falando com LLM fora do gateway continua sendo bug, e agora e
bug **detectado por teste** e nao por leitura de codigo.

### I5 - `procedencia` continua visivel

`FIXTURE | MODELO` viaja do provider ate a resposta e ate o banco. Se o copiloto
comentar um alt julgado por FIXTURE, isso aparece na resposta.

Enquanto o ADR 0005 estiver em PROPOSTA, toda conversa e FIXTURE — e diz isso.

## Onde a conversa pendura

**Filha de `analise`, sem `owner_id` proprio.**

A V5 concentrou `owner_id` em `analise` por decisao de raiz de agregado: repetir
a coluna nas tabelas filhas criaria lugares onde o dono pode divergir do dono da
analise, e divergencia de ownership e falha de seguranca, nao inconsistencia de
dado. A conversa e mais uma filha, e nao ha motivo para ela ser a excecao.

O precedente ja esta em codigo: `RecomendacaoRepository` nao tem `ownerId` em
consulta nenhuma, e o isolamento vem de `ServicoDeRecomendacoes` carregar a
analise por `findByIdAndOwnerId` antes de qualquer outra coisa.

Duas condicoes, sem as quais a heranca vaza:

1. **Nenhum caminho carrega conversa por id de conversa sozinho.** Sempre a
   analise primeiro, com `ownerId`; a conversa depois. Consulta que recebe so o
   id da conversa nao tem como saber de quem ela e — e um `findById` distraido
   seria uma leitura cruzada silenciosa, exatamente o que o teste de 404 da
   Slice 5A existe para impedir.
2. **`ON DELETE CASCADE` a partir de `analise`.** E o que da resposta estrutural
   a "o que acontece quando a analise e apagada" (ADR 0013), em vez de uma
   rotina de limpeza que alguem precisa lembrar de rodar.

## Alternativas consideradas

| Alternativa | Por que nao |
|---|---|
| Copiloto com acesso ao `.docx` | Vira segundo analisador: LLM opinando sobre acessibilidade sem regra, sem criterio versionado e sem evidencia. E a objecao que cortou a Slice 7, e ela continua valendo. |
| Manter a Slice 7 cortada | O corte protegia a fronteira. Com a fronteira travada por teste, o que sobra da slice e o multi-turno — que e onde o guardrail e o teto de gasto sao exercidos de forma diferente da chamada unica. |
| Segunda interface de provider, so para conversa | Duas portas de saida para LLM, e a regra do `CONTRIBUTING.md` secao 5 passaria a ter duas excecoes para conferir em vez de uma. Estender `AiProvider` custa menos. |
| `owner_id` proprio na conversa | Segundo lugar onde o dono pode divergir, contra a V5 e o ADR 0004. Ganharia consulta direta por id de conversa — que e justamente o que a condicao 1 proibe. |
| Guardrail so na entrada da conversa | O turno seguinte entra por outro caminho e nao passaria por conferencia. Em multi-turno, entrada e saida sao verificadas por turno. |

## Condicao de reabertura

Este ADR volta a mesa se **qualquer uma** destas acontecer:

1. **Existir demanda de achado que so o documento responde** dentro da conversa
   ("este alt descreve a imagem?"). A resposta certa NAO e dar o documento ao
   copiloto: e uma regra nova, ou um modelo, na camada que ja existe para isso.
   Reabrir aqui significa reabrir o `CONTRIBUTING.md` secao 2, e o ADR precisa
   dizer isso em voz alta.
2. **O historico de conversa passar a ser insumo de decisao do produto**
   (priorizacao, score, metrica de qualidade). Nesse dia a conversa deixa de ser
   camada de leitura e vira fonte de verdade, e I1 precisa de outra redacao.
3. **O ADR 0005 sair de PROPOSTA com provider real.** Nao muda a fronteira, mas
   muda o custo de cada turno: multi-turno reenvia contexto, e o teto de US$ 10
   por mes foi estimado sobre chamada unica.

## Consequencias

**Boas.** A fronteira vira teste (`ArquiteturaDaIaTest`) e para de depender de
alguem lembrar do javadoc. O copiloto herda de graca o isolamento, o guardrail,
o teto de gasto e a procedencia — a Slice 7 nao reimplementa nada disso.

**Ruins.** O copiloto e limitado por construcao: ele nao responde nada que a
analise nao mediu, e com seis regras isso ainda e a maior parte da WCAG. Um
avaliador que pedir "e o contraste?" vai ouvir uma recusa. A recusa e a resposta
honesta, mas ela **parece** limitacao de produto — e e, ate a regra existir.

**Limite honesto.** Travar a fronteira por teste impede dependencia de codigo.
Nao impede que alguem, um dia, copie o texto do documento para dentro de uma
pergunta e mande pelo campo livre. Contra isso valem a sanitizacao no construtor
compacto de `Fundamento` e o envelope do `MontadorDePrompt` — que sao mitigacao,
nao impossibilidade.
