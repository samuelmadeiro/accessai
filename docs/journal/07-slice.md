# Slice 7 — Copiloto conversacional sobre a análise

> **Procedência desta entrada.** Rascunhada em par com o Claude, a partir dos
> ADRs e do código, e **revisada e adotada por mim**. Registrado porque o
> `CONTRIBUTING.md` §1 pede a entrada com as minhas palavras.

- **Estado:** `./mvnw verify` verde — **248 unitários e 34 E2E**, medidos com
  Docker no ar
- **Critério de pronto do §7:** "idem [guardrail testado], com histórico" —
  **cumprido**, em `ConversaNoFluxoIT.perguntaSemBaseEhRecusadaNoTurno` e
  `ConversaNoFluxoIT.historicoAcumulaOsDoisLados`
- **Ressalva que não pode sumir:** **nenhum modelo foi consultado.** O provider
  ativo continua sendo o `FakeAiProvider`, e toda fala do assistente declara
  `procedencia: "FIXTURE"` — da resposta HTTP até a coluna do banco.

---

## O que foi construído

| Peça | Arquivo |
|---|---|
| Multi-turno na porta única | `ia/AiProvider.conversar` + `AiProvider.Turno` |
| Resposta de conversa | `ia/RespostaDeConversa` |
| Guardrail de saída em texto corrido | `ia/GuardrailDeFundamentacao.conferirSaidaDeConversa` |
| Prompt de conversa | `ia/MontadorDePrompt.montarConversa` |
| Turno no gateway | `ia/GatewayDeIa.conversar` |
| Caso de uso | `copiloto/ServicoDeConversa` |
| API | `POST` e `GET /analyses/{id}/chat` |
| Persistência | `V7__conversa.sql`, `copiloto/TurnoDeConversa` |

## Decisões que valem explicar

1. **A porta foi estendida, não duplicada.** `AiProvider` ganhou um método; o
   copiloto não ganhou uma interface própria. Se cada caso de uso falasse com o
   seu modelo, a regra do §5 passaria a ter duas exceções para conferir em vez de
   uma — e trocar de provider deixaria de ser mudança num lugar só.

2. **O guardrail roda por turno, e a diferença aparece no teste.** Conferir só na
   abertura da conversa deixaria o segundo turno entrar sem verificação — que é
   exatamente onde a pergunta fora de escopo aparece, depois de o primeiro ter
   estabelecido confiança. `GatewayDeIaTest.guardrailValePorTurno` prova isso com
   histórico já povoado, e o E2E prova pelo HTTP.

3. **Em conversa o guardrail de saída recusa por inteiro.** Em recomendação ele
   descarta item a item e entrega o resto, porque a resposta é uma lista presa a
   `regraId`. Conversa é texto corrido: cortar a frase que cita um critério
   inventado deixaria o parágrafo apoiado numa premissa que sumiu, e o usuário
   leria um texto remendado sem saber. Recusar é a saída honesta.

4. **O turno recusado não deixa rastro — nem a pergunta.** `responder` é
   transacional, e o guardrail lança antes do commit. Gravar a pergunta de um
   turno cuja resposta nunca existiu produziria um histórico que não aconteceu, e
   esse histórico voltaria como contexto nos turnos seguintes. Tem teste próprio
   (`turnoRecusadoNaoGravaNada`), porque é o tipo de coisa que só se descobre
   quebrada muito depois.

5. **O histórico é recorte de envio, não de retenção.** Só os últimos 10 turnos
   voltam ao prompt (`accessai.copiloto.turnos-de-contexto`); o histórico inteiro
   continua gravado e continua saindo no GET. Com provider pago, mandar a
   conversa toda a cada turno faria o custo crescer com o quadrado do número de
   turnos.

6. **Sanitização vale mais em multi-turno, não menos.** O texto do usuário
   **volta** ao prompt em todos os turnos seguintes: uma injeção que passasse uma
   vez seria reenviada para sempre. Por isso `AiProvider.Turno` sanitiza no
   construtor compacto — mesma garantia-por-construção do `Fundamento` — e cada
   fala entra no seu próprio envelope, porque um envelope único permitiria a uma
   fala fingir ser várias.

7. **A pergunta é gravada como o usuário escreveu.** A sanitização existe para a
   fronteira do prompt, não para o banco. Guardar o texto alterado faria a pessoa
   reler a própria pergunta modificada, sem entender por quê.

8. **Documento limpo não rende conversa, e isso é intencional.** O guardrail
   recusa quando não há achado nenhum, e em multi-turno isso recusa a conversa
   inteira. Estava previsto no ADR 0012 justamente para não parecer defeito: sem
   achados, o que sairia seria conselho genérico de acessibilidade apresentado
   como análise deste documento.

## O que o copiloto não faz, por construção

Não recebe o documento, não cria `Problema`, não mexe no score, não introduz
critério. Isso não é promessa de javadoc: `ArquiteturaDaIaTest` foi escrito na
preparação, antes deste código existir, e as três regras foram vistas falhando
contra uma classe violadora. O código desta slice nasceu dentro delas.

## O que ficou em aberto

- **ADR 0005 segue em PROPOSTA.** Sem chave e sem modelo escolhido, o copiloto é
  fixture declarada. O que a slice prova é a moldura — porta única, guardrail por
  turno, teto de gasto, histórico, procedência —, não a qualidade da conversa.
- **O teto de US$ 10/mês foi estimado sobre chamada única.** Multi-turno reenvia
  contexto por turno; a conta precisa ser refeita quando houver provider real.
- **Não existe endpoint de exclusão de análise.** O cascade da V7 garante que a
  conversa some junto quando a exclusão existir — ele não cria a exclusão.
  Continua sendo Slice 9 (ADR 0013).
- **Não há rate limit no `/chat`.** O limitador da 5A é por upload. Um turno é
  barato hoje (fixture, custo zero) e deixa de ser no dia do provider real —
  entra junto com o ADR 0005, não antes.
- **[issue #1](https://github.com/samuelmadeiro/accessai/issues/1) mordeu de
  novo.** No primeiro `verify` desta slice o Docker estava parado: os 34 E2E
  foram pulados e o build deu `BUILD SUCCESS`. Precisei subir o daemon e rodar de
  novo para ter número medido. É a segunda vez em duas sessões.
