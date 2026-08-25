# Slice 5 — ML Service (FastAPI), cliente Java com fallback e predição no resultado

> **Procedência desta entrada.** O texto foi rascunhado em par com o Claude, a
> partir dos ADRs e do código, e **revisado e adotado por mim** — as posições
> aqui são as minhas. Registrado porque o `CONTRIBUTING.md` §1 pede a entrada
> com as minhas palavras, e omitir como ela foi escrita seria o mesmo tipo de
> silêncio que o §1 existe para impedir.

- **Estado:** `./mvnw clean verify` verde — 175 testes unitários e 15 E2E;
  `pytest` verde — 91 testes; `ruff` e `mypy` limpos
- **Ressalva que não pode sumir:** `models/` está vazio. **Toda** predição hoje
  vem da heurística, e cada resposta declara isso em `usouHeuristica: true`.
  O que esta slice entrega é a canalização, não predição de ML de verdade.

> **O que mudou depois desta entrada.** Três dívidas listadas no fim foram
> fechadas: existe `POST /v1/predict:batch` e o fluxo manda o documento inteiro
> numa chamada; existe `HeuristicaDeAltLocal` no Java, então o Python fora do ar
> não significa mais zero classificação; e uma linha de log por jornada liga o
> `correlationId` literal do cliente ao UUID gravado no banco. O texto abaixo
> fica como estava — ele registra a slice no fechamento dela, e os itens 7 e "O
> que eu construí" descrevem o comportamento **daquele** momento. As mudanças
> estão marcadas na seção de dívida e no ADR 0011.

---

## O que foi construído

| Peça | Arquivo |
|---|---|
| Contrato da API | `inference/schemas.py` (`RequisicaoAnalise`, `RespostaAnalise`) |
| Carga e degradação | `inference/servico.py` (`ServicoDePredicao`) |
| Endpoints | `inference/main.py` (`GET /health`, `POST /v1/predict`) |
| Imagem | `ml-service/Dockerfile`, serviço no `docker-compose.yml` |
| Cliente Java | `integracao/ml/ClienteMlService`, `RequisicaoMlDTO`, `RespostaMlDTO` |
| Persistência da predição | `V4__predicao_de_alt.sql`, `PredicaoDeAlt`, `PredicaoDeAltRepository` |
| Fiação no fluxo | `ExecucaoDaAnalise` chama o ML depois das regras |
| Saída na API | `predicoesDeAlt` em `AnaliseDto.RespostaDeAnalise` |
| Medição | `ml-service/bench/medir_latencia.py` |
| Decisão registrada | `docs/adr/0011-integracao-com-o-ml-service-por-http.md` |

## Decisões que valem explicar

1. **HTTP síncrono, contrariando o contrato.** O `CONTRIBUTING.md` §5 e §7 pedem
   comunicação por evento. A divergência foi apontada antes de escrever o
   código e a implementação seguiu assim mesmo — o ADR 0011 registra isso e
   lista os três gatilhos que revertem para o desenho por evento. O §5 e o §7
   ganharam ressalva apontando para o ADR.

2. **A predição fica FORA do score, em tabela própria.** O score é soma ponderada
   de penalidades determinísticas (§6). Se a predição virasse linha em
   `problema`, entraria na conta pela porta dos fundos — e hoje isso pesaria
   mais que o normal, porque toda predição vem de regra.

3. **`usouHeuristica` é obrigatório, não cortesia.** Um serviço chamado
   `/predict` que devolve resultado de regras sem dizer que são regras faz o
   consumidor acreditar que existe um modelo. `confianca` fica nula pelo mesmo
   motivo: regra não tem probabilidade, e um `1.0` ali faria o Java tratar regra
   como modelo confiante. Há `CHECK` no banco tornando a incoerência impossível.

4. **Só imagem com alt PRESENTE é classificada.** Alt ausente é detecção
   determinística e já é regra (§2 — não use ML onde uma regra resolve); alt
   vazio é declaração de imagem decorativa, que o WCAG 1.1.1 permite. Um teste
   assere que documento sem alt gera **zero** chamadas ao ML.

5. **Duas falhas diferentes, dois tratamentos.** Não carregar o artefato é
   permanente; uma predição falhar é transitório e degrada só a chamada. A
   primeira versão tratava as duas igual, e um único blip deixava o serviço
   respondendo por heurística até o restart. Foi encontrado medindo, não lendo.

6. **`/health` responde 200 sem modelo.** 503 faria a orquestração reiniciar em
   loop um container saudável e respondendo.

7. **Indisponibilidade abandona o documento, não só a imagem.** Não adianta
   seguir pedindo a um serviço que acabou de não responder, com 1,5 s de timeout
   cada — vinte imagens virariam trinta segundos de espera garantida.

8. **As predições são lidas no `GET`, nunca recalculadas.** Chamar o ML na
   leitura poria latência de rede em toda consulta e faria a mesma análise
   responder coisas diferentes a cada vez.

## Três defeitos que só a integração real revelou

Os testes dos dois lados passavam e a integração estava quebrada:

1. **Faltava `Content-Type`.** Sem ele o `RestClient` não escolhe o conversor
   JSON, o corpo chega vazio e o serviço responde `422`. O teste passava porque
   o servidor falso ignorava o corpo da requisição — era um teste que não
   testava o pedido.
2. **HTTP/2 h2c.** O `HttpClient` do JDK usa HTTP/2 por padrão e tenta upgrade
   h2c em texto claro; o uvicorn só fala HTTP/1.1 e o corpo se perde na
   negociação. Resolvido fixando `HTTP_1_1`.
3. **`RestClient.Builder` não é autoconfigurado no Boot 4.** Terceira vez que o
   projeto tropeça na modularização das autoconfigurações (Flyway, Kafka, agora
   RestClient). `mvn test` passava; os E2E morriam na subida do contexto.

## Latência medida

Critério de pronto da slice. p99, loopback, mesma máquina:

| Camada | Origem | p99 |
|---|---|---|
| Em processo | heurística | 0,006 ms |
| Em processo | modelo | 8,68 ms |
| Cliente Java → HTTP | heurística | **7,11 ms** |
| Cliente Java → HTTP | modelo | 9,22 ms |
| Cliente Java → serviço fora | fallback | 6,46 ms |

O timeout de 1500 ms tem folga de duas ordens de grandeza — ele protege contra
serviço travado, não contra serviço lento. E o medidor Python via `urllib` deu
p99 de 30 ms contra 9 ms do cliente Java, mesmo serviço: reuso de conexão pesa
mais que a inferência.

A medição corrigiu uma estimativa minha que estava no ADR: "até 30 s para vinte
imagens" virou ~180 ms. Os 30 s só acontecem com o serviço travado.

## As perguntas do contrato

> As quatro perguntas que o §1 exige: o que foi construído, por que dessa
> forma, qual alternativa foi descartada e por quê, e o que eu ainda não sei
> defender.

### O que eu construí

Um caminho completo para um texto alternativo, da borda do backend até a
resposta da API — e nenhum modelo.

O consumidor Kafka do backend, depois de o Rule Engine já ter produzido o score,
manda cada imagem **com alt presente** para o `ClienteMlService`. Ele fala
`POST /v1/predict` no serviço FastAPI, com 500 ms de timeout de conexão e
1500 ms de leitura, e **nunca lança**: timeout, conexão recusada, 4xx, 5xx e
corpo malformado viram `RespostaMlDTO.indisponivel()`. Do outro lado,
`ServicoDePredicao` devolve rótulo, confiança e `usouHeuristica`. A predição
vira linha em `predicao_de_alt` e sai em `predicoesDeAlt` no `GET`, lida do
banco e nunca recalculada.

O que eu **não** construí é a parte que o nome do endpoint promete. `models/`
está vazio, e por isso toda predição de hoje vem de regra. É o campo
`usouHeuristica: true` que impede essa frase de ser mentira — sem ele, um
serviço chamado `/predict` faria o consumidor acreditar num modelo que não
existe. O que a Slice 5 entrega é a canalização, testada contra rede real.

### Por que aceitei HTTP síncrono contra o meu próprio contrato

Porque ML aqui é camada opcional (§2), e o custo do desenho certo não se paga
enquanto ele for opcional.

O score já está completo e correto sem predição nenhuma: ele é soma de
penalidades determinísticas do Rule Engine. Perder a predição por
indisponibilidade custa **uma informação a menos, não uma análise a menos**. O
caminho por evento — tópico novo, consumidor Python, produtor de resultado,
tabela de predições e a reconciliação "a análise já concluiu mas a predição
ainda não chegou" — é muita peça móvel para enfeitar um resultado que hoje sai
100% de heurística.

A parte que eu defendo com mais convicção não é a escolha, é o procedimento: a
divergência foi apontada **antes** de escrever o código, virou o ADR 0011, e o
§5 e o §7 ganharam ressalva apontando para ele. O defeito seria fingir que a
decisão original nunca existiu — aí o contrato viraria decoração.

**E quando eu reverto?** Os três gatilhos estão no ADR 0011, e o destino dos
três é o mesmo desenho por evento que o contrato já descrevia:

1. **Existe modelo treinado** e a predição passa a pesar no que o produto
   entrega. A partir daí, perder predição é perder produto, não enfeite — e
   acoplar a análise à disponibilidade de outro processo fica indefensável.
2. **A predição precisa ser reconciliada** com a análise. Nesse ponto metade da
   infraestrutura do caminho assíncrono já existe e o resto custa pouco.
3. **O tempo total de análise passa a incomodar** por causa das chamadas
   sequenciais — o cenário degradado, não o normal.

O gatilho 1 é o que está mais perto, e ele depende do ADR 0002 sair de PROPOSTA.

### Qual alternativa eu descartei e por quê

| Alternativa | Por que não, agora |
|---|---|
| **Kafka, como o contrato manda** | É a resposta certa e continua sendo. Não é "estava errado no contrato": é dívida com data de vencimento escrita. |
| **gRPC** | Contrato tipado e mais barato no fio, mas paga geração de código e uma dependência nova dos dois lados para resolver um problema que o projeto não tem. Latência não era o gargalo — medi. |
| **Portar o classificador para Java** | Some a chamada de rede inteira, e some o Python junto. O Python é metade do propósito (§1: portfólio de Backend **e** ML). Otimizar até desaparecer com o motivo do projeto. |
| **Síncrono sem timeout curto** | Segura a partição do Kafka: uma mensagem lenta atrasa todas atrás dela. |
| **Síncrono sem fallback** | Transformaria a camada opcional em dependência dura — o Python fora do ar derrubaria o processamento de toda mensagem. |

### Por que a predição não entra no score

Porque o score é a única coisa do produto que se explica linha a linha, e ML não
se explica linha a linha.

O §6 diz que cada ponto perdido rastreia até um problema específico, com
evidência e critério WCAG. Uma predição não tem critério: "este alt parece
fraco" não é violação de 1.1.1, é opinião de um classificador que **não vê a
imagem** — ele detecta padrão linguístico de inadequação, e um alt bem escrito e
completamente errado sai como `GOOD`. Se isso virasse linha em `problema`,
entraria na conta pela porta dos fundos e o score deixaria de ser explicável,
que é a propriedade que ele existe para ter.

Hoje o argumento é ainda mais direto: toda predição vem de regra. Somar ao score
uma regra que não passou pelo Rule Engine seria dar peso a uma heurística por
fora do lugar onde heurística é auditável.

O desenho carrega isso na estrutura, não na intenção: tabela própria,
`usouHeuristica` obrigatório, `confianca` nula quando a origem é regra — regra
não tem probabilidade, e um `1.0` ali faria o Java tratar regra como modelo
confiante — e um `CHECK` no banco tornando a incoerência impossível de gravar.

O limite honesto: ML **pode** ajustar a severidade de um problema que uma regra
já detectou. Nunca o score final direto.

### O que eu ainda não sei defender numa entrevista

Candidatos objetivos — os buracos são reais, escolher quais são os meus é o que
falta:

1. **A métrica que existe não mede o que o nome dela diz.** A macro-F1 de
   `0.508 ± 0.098` é calculada sobre `rotulo_provisorio`, que é a própria
   heurística: ela mede a heurística contra ela mesma. Sei explicar por que ela
   está aí; ainda não ensaiei responder "então qual é a acurácia do modelo?"
   sem parecer que estou desconversando.
2. **Por que TF-IDF + regressão logística e não um transformer.** A resposta
   está no §3 e no ADR 0002 — tamanho do dado —, mas dita em voz alta ainda soa
   como desculpa em vez de dimensionamento.
3. **Não há heurística no lado Java.** Quando o Python cai, não sobra
   classificação nenhuma. Sei o motivo (a mesma regra em duas linguagens
   diverge), não sei sustentar a pergunta seguinte: "e o usuário, o que vê?".
4. **`correlationId` vira UUID derivado** depois da borda HTTP. É
   determinístico, mas grepar o log pelo id literal que o cliente mandou não
   funciona — e isso é observabilidade, que é onde a pergunta costuma ir.
5. **Uma chamada HTTP por imagem, sem lote.** Medido, o custo normal é
   aceitável; a defesa depende de eu lembrar que o problema aparece só no
   cenário degradado.
6. **HTTP/2 h2c e o `RestClient.Builder` do Boot 4.** Resolvi medindo e
   tentando. Sei o que fiz, não sei explicar bem *por que* o `HttpClient` do JDK
   tenta upgrade h2c em texto claro por padrão.

> **Inconsistência encontrada ao escrever isto, e já corrigida.** O ADR 0011
> dizia em "Consequências" que "a predição não é persistida". A Slice 5
> persistiu — `V4__predicao_de_alt.sql`, `PredicaoDeAlt`,
> `PredicaoDeAltRepository` — que era justamente a saída que o parágrafo previa
> ("ou ela vira coluna"). O ADR foi atualizado: o gatilho 2 de reversão continua
> válido, porque o que não existe é a **reconciliação**, não a coluna.

## Dívida consciente que segue aberta

- **Não há modelo.** `models/` vazio, D2 em PROPOSTA no ADR 0002. A Slice 5
  entrega a canalização; a predição de verdade depende de resolver a
  procedência do dataset.
- ~~**Não existe heurística no lado Java.**~~ **Resolvido depois de escrita esta
  entrada.** `HeuristicaDeAltLocal` responde quando o Python cai, com
  `usouHeuristica: true` e `confianca: null`. A objeção original — a mesma regra
  em duas linguagens diverge — continua de pé, e é paga com um corpus de
  contrato em `docs/ml/heuristica-alt.golden.json` que os dois lados reproduzem
  em teste. O que virou o jogo: sem ela, Python fora do ar significava **zero**
  classificação, e o usuário via um documento analisado pela metade sem
  explicação nenhuma.
- ~~**Uma chamada HTTP por imagem.**~~ **Resolvido:** `POST /v1/predict:batch`,
  e o fluxo manda o documento inteiro numa chamada. O cenário degradado deixou
  de ser linear: vinte imagens pagam um timeout de 1,5 s, não vinte.
- **O `correlationId` derivado agora tem ponte no log.** Continua virando UUID
  depois da borda HTTP, mas uma linha de log por jornada liga o id literal do
  cliente ao UUID que foi para o banco — grep por qualquer um dos dois acha a
  jornada inteira.
- **A predição não é versionada junto com a análise.** Se o modelo mudar, as
  predições antigas continuam lá sem indicar que vieram de outra versão — hoje
  `modelo_versao` é sempre nulo porque não há modelo.
- Contraste (1.4.3), autenticação e `owner_id` seguem pendentes desde a Slice 2.
