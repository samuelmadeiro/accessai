# Slice 6 — AI Gateway e recomendações fundamentadas na análise

> **Procedência desta entrada.** O texto foi rascunhado em par com o Claude, a
> partir dos ADRs e do código, e **revisado e adotado por mim** — as posições
> aqui são as minhas. Registrado porque o `CONTRIBUTING.md` §1 pede a entrada
> com as minhas palavras, e omitir como ela foi escrita seria o mesmo tipo de
> silêncio que o §1 existe para impedir.

- **Estado:** `./mvnw verify` verde — 231 testes unitários e 27 E2E
- **Critério de pronto do §7:** guardrail testado, pergunta sem base na análise →
  recusa — **cumprido**, em `RecomendacaoNoFluxoIT.perguntaSemBaseEhRecusada` e
  em `GuardrailDeFundamentacaoTest`
- **Ressalva que não pode sumir:** **nenhum modelo foi consultado.** O provider
  ativo é o `FakeAiProvider`, e toda resposta declara `procedencia: "FIXTURE"`.

---

## Por que sem LLM de verdade

O ADR 0005 está em **PROPOSTA**, aguardando as perguntas 5 e 6 da `fase-0.md` —
chave da API e escolha entre Haiku e Sonnet. E não existe camada gratuita da
Claude API: toda chamada é paga.

O D5 já previa este caminho: *"FakeAiProvider implementando AiProvider,
devolvendo fixtures. Zero rede no CI."* O que mudou é que ele deixou de ser só o
dublê de teste e passou a ser o provider **ativo**. O critério de pronto da slice
— o guardrail — não depende de modelo nenhum para ser exercido.

**Isto não é "IA que é template string", e a diferença cabe num campo.** O §1
proíbe apresentar template como IA; não proíbe usar template **declarado como
template**. `procedencia: "FIXTURE"` viaja do provider até o corpo da resposta
HTTP e até a coluna do banco. É o mesmo papel que `usouHeuristica` faz no ML
Service.

## O que foi construído

| Peça | Arquivo |
|---|---|
| A única porta para LLM | `ia/AiProvider` (interface) |
| Provider ativo | `ia/FakeAiProvider` |
| Guardrail | `ia/GuardrailDeFundamentacao` |
| Teto de gasto | `ia/ContadorDeGastoDeIa` (Redis) |
| O gateway | `ia/GatewayDeIa` |
| Caso de uso | `ia/ServicoDeRecomendacoes` |
| API | `POST` e `GET /analyses/{id}/recommendations` |
| Persistência | `V6__recomendacao.sql`, `ia/Recomendacao` |
| Conteúdo hostil | `ia/ConteudoNaoConfiavel`, `ia/MontadorDePrompt` |

## Decisões que valem explicar

1. **O guardrail age nas DUAS pontas.** Na entrada, recusa pergunta que cita
   critério que a análise não verificou. Na saída, descarta recomendação que
   cita regra ausente da análise. A da saída parece redundante hoje — a fixture
   não tem como inventar — e existe justamente porque o provider de amanhã é
   generativo, e alucinar critério WCAG plausível é o modo de falha dele.
   Guardrail escrito depois que o modelo chega é guardrail escrito com pressa.
2. **O fundamento é um tipo, não uma convenção.** `AiProvider.Fundamento` só
   aceita achados reais. Não existe caminho no sistema que mande texto livre ao
   provider — "recomendação fundamentada na análise" virou contrato de
   compilador em vez de promessa no README.
3. **A ordem das quatro etapas do gateway é a decisão inteira dele.** Guardrail
   de entrada primeiro, porque recusar custa zero e recusar depois de pagar
   seria pagar para descobrir que a pergunta não tinha base. Depois o teto,
   depois o provider, depois o guardrail de saída.
4. **O critério é conferido pelo número, não por palavra-chave.** `1.4.3` numa
   pergunta é objetivo; "contraste" exigiria um classificador de intenção
   escrito à mão, com falso positivo e falso negativo decidindo se o sistema
   pode responder.
5. **Pergunta genérica passa.** Sem número de critério não há o que conferir na
   entrada — quem protege esse caso é o guardrail de saída.
6. **Recomendação é persistida, não recalculada.** O score pode ser recalculado
   porque é função pura; texto de LLM não é. Recalcular faria a mesma análise
   responder coisas diferentes a cada consulta, e com provider pago cobraria de
   novo a cada releitura.
7. **O teto de gasto falha FECHADO** — ao contrário do rate limit de upload, que
   falha aberto. A assimetria é deliberada: liberar upload sem contar custa CPU;
   liberar chamada de LLM sem contar custa dinheiro que ninguém autorizou.
8. **503, não 402, quando o contador não responde.** Dizer "orçamento esgotado"
   seria mentir: o orçamento pode estar intacto, o que falta é como conferi-lo.
9. **`@ConditionalOnMissingBean` no fake.** No dia em que existir um provider
   real, basta ele existir como bean para o fake sair de cena. Sem flag, sem
   perfil, sem `if`.

## As perguntas do contrato

> As quatro perguntas que o §1 exige: o que foi construído, por que dessa forma,
> qual alternativa foi descartada e por quê, e o que eu ainda não sei defender.

### O que eu construí

Um gateway que é a única porta de saída para LLM, e um guardrail que faz o
sistema **se recusar a falar do que não mediu**.

O que eu não construí é a chamada ao modelo. E a parte que eu defendo é
justamente essa: a slice inteira funciona, é testada ponta a ponta, e não gastou
um centavo — porque o valor dela não está no modelo, está na fundamentação e na
recusa.

### Por que desta forma e não de outra

**Porque a pergunta perigosa de uma entrevista sobre IA é "como você impede que
ele invente?".** A resposta aqui é estrutural, não um prompt pedindo educação:
o tipo de entrada só carrega achados reais, e a saída é filtrada contra a mesma
lista. Prompt engineering pedindo "não invente" é um pedido; tipo e filtro são
uma garantia.

**E porque o §2 diz que IA é camada de enriquecimento, nunca caminho crítico.**
Guardrail recusando, orçamento esgotado, contador indisponível — nos três casos
a análise continua completa. Só a seção de recomendações para.

### Qual alternativa eu descartei e por quê

| Alternativa | Por que não |
|---|---|
| **Chamar a Claude API agora** | ADR 0005 em PROPOSTA: sem chave, sem teto aprovado, e a escolha de custo × qualidade é do dono do orçamento. |
| **Modelo local (Ollama)** | Reabre o ADR 0005, que o §3 manda não reabrir sem consulta. Adiciona serviço ao compose e um modelo pequeno em português entregaria texto pior que a fixture. |
| **Fixture sem declarar procedência** | Seria exatamente a "IA que é template string" do §1. |
| **Prompt pedindo para não inventar** | Pedido, não garantia. |
| **Guardrail só na saída** | A chamada aconteceria antes de descobrir que a pergunta não tinha base — com provider pago, paga-se para recusar. |
| **Recomendação recalculada no GET** | Mesma análise respondendo coisas diferentes, e cobrança a cada leitura. |
| **Classificador de intenção por palavra-chave** | Falso positivo decide que o sistema não pode responder algo que ele mediu. |

### O que eu ainda não sei defender numa entrevista

1. **O guardrail de entrada só pega critério citado por número.** "Por que as
   cores estão ruins?" passa. Sei que o guardrail de saída segura, não sei
   explicar bem por que aceito o buraco na entrada.
2. **A fixture é boa demais para o que é.** Um texto por regra, sempre correto.
   Quando o modelo real entrar, a comparação pode parecer regressão.
3. **Não medi nada.** Latência, custo real por análise, tamanho de prompt — tudo
   é estimativa do D5. A Slice 5 fechou com latência medida; esta não tem o
   equivalente.
4. **O `@ConditionalOnMissingBean` é elegante e silencioso.** Se alguém
   registrar um provider real sem perceber, o sistema troca sozinho — e a única
   pista é o campo `procedencia` mudando.
5. ~~**Prompt injection.**~~ **Resolvido depois desta entrada** — ver
   "Prompt injection" abaixo. O que continua sem resposta boa é o limite do que
   sanitização alcança: nenhuma das camadas prova que injeção é impossível, só
   que o texto perde o poder de formatar.

## Prompt injection

Resolvido depois de a entrada ter sido escrita, em quatro camadas — nenhuma
suficiente sozinha:

1. **Sanitização por construção.** `AiProvider.Fundamento` limpa a evidência e a
   pergunta no construtor compacto. Nenhum `Fundamento` pode existir com texto
   bruto dentro, então **todo provider recebe conteúdo tratado — inclusive o que
   ainda não foi escrito**. Se a limpeza morasse em quem monta o prompt, o
   provider escrito com pressa seria o que esquece.
2. **Tirar o poder de formatar.** Quebra de linha, caractere de controle e
   marcador de papel (`System:`, `<|im_start|>`, `[INST]`, cerca de código,
   `###`) viram `[removido]`. Não é lista negra de frases — "ignore as
   instruções anteriores" continua passando como texto, e é isso mesmo: o que
   transforma conteúdo em instrução é a formatação, não o vocabulário.
3. **Envelope com nonce.** O texto entra entre delimitadores sorteados por
   chamada. Com delimitador fixo, bastaria escrevê-lo para "sair" do bloco.
4. **A instrução desarma o bloco**, dizendo que o que está dentro é dado a
   analisar e que pedido para ignorar as regras é o próprio problema.

A montagem do prompt saiu de dentro dos providers e virou `MontadorDePrompt`,
usado pelo gateway: montagem é onde conteúdo não confiável encosta na instrução,
e ela mora num lugar só.

**A última linha de defesa não é nenhuma das quatro.** Se uma injeção sobreviver
a tudo e convencer o modelo a recomendar outra coisa, o guardrail de saída
descarta o que citar regra ausente da análise. Sanitizar reduz a chance; o
guardrail limita o estrago.

E um teste que existe pelo motivo oposto: `textoLegitimoNaoEhMutilado`. Se a
sanitização estragasse evidência normal, a recomendação pioraria e alguém
desligaria o filtro — que é como proteção morre.

## Dívida consciente que segue aberta

- **Nenhum modelo real.** ADR 0005 em PROPOSTA.
- **O adaptador da Anthropic não existe** — só a interface que ele vai
  implementar.
- **Sem medição** de latência ou custo.
- **Sem cache de prompt.** O D5 discute o prefixo mínimo cacheável; nada disso é
  aplicável enquanto não há chamada.
- **Guardrail de entrada é sintático**, não semântico.
