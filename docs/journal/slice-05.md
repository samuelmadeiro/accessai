# Slice 5 — ML Service (FastAPI), cliente Java com fallback e predição no resultado

> **RASCUNHO.** O `CONTRIBUTING.md` §1 diz que esta entrada é escrita com as
> palavras do autor. O registro factual está montado; as perguntas do contrato
> estão marcadas **PARA COMPLETAR**.

- **Estado:** `./mvnw clean verify` verde — 175 testes unitários e 15 E2E;
  `pytest` verde — 91 testes; `ruff` e `mypy` limpos
- **Ressalva que não pode sumir:** `models/` está vazio. **Toda** predição hoje
  vem da heurística, e cada resposta declara isso em `usouHeuristica: true`.
  O que esta slice entrega é a canalização, não predição de ML de verdade.

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

## PARA COMPLETAR

**O que eu construí, com minhas palavras:** _(escrever)_

**Por que aceitei HTTP síncrono contra o meu próprio contrato:** _(escrever — o
ADR 0011 tem o argumento; a resposta de entrevista é sua. A pergunta que vem
depois é "e quando você reverte?")_

**Qual alternativa eu descartei e por quê:** _(escrever — candidatas: Kafka como
o contrato mandava, gRPC, portar o classificador para Java)_

**Por que a predição não entra no score:** _(escrever — vale ensaiar, porque é a
pergunta que separa "sei o que é ML" de "sei onde ML não deve entrar")_

**O que eu ainda não sei defender numa entrevista:** _(escrever)_

## Dívida consciente que segue aberta

- **Não há modelo.** `models/` vazio, D2 em PROPOSTA no ADR 0002. A Slice 5
  entrega a canalização; a predição de verdade depende de resolver a
  procedência do dataset.
- **Não existe heurística no lado Java.** Quando o Python cai, não há
  classificação nenhuma — a análise fica com o que o Rule Engine já produziu.
  Portar a heurística criaria a mesma regra em duas linguagens, que divergem.
- **Uma chamada HTTP por imagem.** A API é de item único. Medido, o custo é
  aceitável (~180 ms para vinte imagens); com o serviço degradado vira linear.
  Lote resolveria e a API não suporta.
- **O correlationId do cliente vira UUID derivado** depois da borda HTTP, porque
  a coluna e o payload são `UUID`. A derivação é determinística, mas grepar o
  log pelo id literal que o cliente mandou não funciona.
- **A predição não é versionada junto com a análise.** Se o modelo mudar, as
  predições antigas continuam lá sem indicar que vieram de outra versão — hoje
  `modelo_versao` é sempre nulo porque não há modelo.
- Contraste (1.4.3), autenticação e `owner_id` seguem pendentes desde a Slice 2.
