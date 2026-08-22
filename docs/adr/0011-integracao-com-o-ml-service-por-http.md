# ADR 0011 - Integracao com o ML Service por HTTP sincrono

- **Status:** aceita (Slice 5)
- **Contradiz:** `CONTRIBUTING.md` secao 5 ("O ML Service nao acessa o banco
  principal. Comunicacao por evento") e a definicao da Slice 5 na secao 7
  ("ML Service consumindo Kafka, predicao no resultado")

## Contexto

A Slice 5 precisa levar um texto alternativo do backend Java ate o classificador
em Python e trazer a categoria de volta.

O contrato do projeto ja tinha uma resposta para isso: comunicacao por evento. O
ML Service consumiria o topico, escreveria a predicao, e o backend leria o
resultado. Nenhuma chamada sincrona, nenhum acoplamento de disponibilidade.

A implementacao foi feita com `RestClient` sincrono, com timeout curto e
fallback. Este ADR existe porque **a divergencia foi apontada antes de escrever
o codigo e a implementacao seguiu assim mesmo** — registrar isso vale mais que
fingir que a decisao original nunca existiu.

## Decisao

**Chamada HTTP sincrona**, do consumidor Kafka do backend para o
`POST /v1/predict` do ML Service, com:

- timeout de conexao de 500 ms e de leitura de 1500 ms, separados;
- `ClienteMlService` que **nunca lanca**: timeout, conexao recusada, 4xx, 5xx e
  corpo malformado viram `RespostaMlDTO.indisponivel()`;
- o Rule Engine seguindo sozinho quando nao ha predicao.

O que sustenta a escolha: **ML e camada opcional** (CONTRIBUTING.md secao 2). Se
a predicao fosse essencial ao resultado, acoplar a analise a disponibilidade de
outro processo seria indefensavel. Como ela e um enfeite sobre um score que ja
esta completo e correto sem ela, a indisponibilidade custa uma informacao a
menos — nao uma analise a menos.

## Alternativas consideradas

| Alternativa | Por que nao (agora) |
|---|---|
| **Kafka, como manda o contrato** | E a resposta certa e continua sendo. Custa um topico novo, um consumidor Python, um produtor de resultado, uma tabela de predicoes e a reconciliacao "a analise ja concluiu mas a predicao ainda nao chegou". Muita infraestrutura para um servico que hoje responde 100% por heuristica porque nao existe modelo treinado (ADR 0002). |
| Chamada sincrona sem timeout curto | Segura a particao do Kafka. Uma mensagem lenta atrasa todas atras dela. |
| Chamada sincrona sem fallback | Transforma a camada opcional em dependencia dura: o Python fora do ar derrubaria o processamento de toda mensagem. |
| gRPC no lugar de REST | Contrato tipado e mais barato no fio, mas adiciona geracao de codigo e uma dependencia nova dos dois lados para resolver um problema que o projeto nao tem. |
| Portar o classificador para Java | Some a chamada de rede inteira, e some tambem o Python — que e metade do proposito do projeto (CONTRIBUTING.md secao 1: portfolio de Backend **e** ML). |

## Consequencias

**Boas.** Menos peca movel: nenhum topico novo, nenhuma tabela de predicao,
nenhuma reconciliacao. A predicao chega junto com a analise, no mesmo fluxo. O
caminho de falha e curto e foi testado contra rede real — timeout de leitura,
conexao recusada, 500, corpo malformado.

**Ruins, e assumidas.**

- **Acoplamento de disponibilidade.** Cada analise espera ate 1,5 s por um
  servico opcional. Nao quebra, mas atrasa.
- **Uma chamada por imagem.** O contrato e de item unico. Documento com vinte
  imagens vira vinte chamadas sequenciais.

  **Medido depois deste ADR ser escrito, e menos grave do que ele previa.** Com
  p99 de 9 ms por chamada (ver `ml-service/README.md`), vinte imagens custam
  ~180 ms — nao os 30 s que este paragrafo estimava. Os 30 s so acontecem se
  TODA chamada estourar o timeout de 1500 ms, o que e o cenario de servico
  travado, nao o tipico.

  O que sobra de preocupacao e o cenario degradado, nao o normal: e com servico
  travado que o custo vira linear no numero de imagens. Lote resolveria, e a API
  nao suporta hoje.
- **Contradiz o contrato escrito.** Alguem que leia so o `CONTRIBUTING.md` vai
  procurar um consumidor Kafka em `ml-service/` e nao vai achar. Este ADR e o
  unico lugar onde os dois se conciliam.
- **A predicao nao e persistida.** Ela existe durante o processamento e some. Ao
  fiar no fluxo, ou ela vira coluna, ou toda releitura da analise precisa chamar
  o Python de novo.

## Quando reverter

Esta decisao vale enquanto ML for enfeite. Ela deixa de valer quando qualquer
uma destas for verdade:

1. **Existe modelo treinado** e a predicao passa a ter peso no que o produto
   entrega — a partir dai perder predicao por indisponibilidade e perder
   produto, nao enfeite.
2. **A predicao precisa ser persistida** e reconciliada com a analise. Nesse
   ponto metade da infraestrutura do caminho por evento ja existe, e o resto
   custa pouco.
3. **O tempo total de analise passa a incomodar** por causa das chamadas
   sequenciais.

Nos tres casos o destino e o mesmo: o desenho por evento que o
`CONTRIBUTING.md` ja descrevia.
