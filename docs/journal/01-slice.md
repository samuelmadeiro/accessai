# Slice 1 — upload → Kafka → uma regra → Postgres → `GET /analyses/{id}`

> **Procedência desta entrada.** O texto foi rascunhado em par com o Claude, a
> partir dos ADRs e do código, e **revisado e adotado por mim** — as posições
> aqui são as minhas. Registrado porque o `CONTRIBUTING.md` §1 pede a entrada
> com as minhas palavras, e omitir como ela foi escrita seria o mesmo tipo de
> silêncio que o §1 existe para impedir.

- **Período:** 2026-08-19 (implementação) e 2026-08-19 (correções pós-review)
- **Estado:** funcional, `./mvnw verify` verde com Docker

---

## O que foi construído

| Peça | Arquivo |
|---|---|
| Borda HTTP | `AnaliseController` (`POST /analyses`, `GET /analyses/{id}`) |
| Validação de tipo real | `ValidadorDeDocx` (assinatura zip + partes OOXML + limites de zip bomb) |
| Persistência | `Analise`, `DocumentoBinario`, `Problema`, `EventoProcessado` + `V1__esquema_de_analise.sql` |
| Evento | `AnaliseSolicitadaV1`, `ProdutorDeAnalise`, `ConsumidorDeAnalise` |
| Processamento | `ProcessadorDeAnalise` (política de falha) + `ExecucaoDaAnalise` (transação) + `RegistroDeFalha` |
| Extração | `ExtratorDeImagens` (StAX, sem POI) |
| Regras | `MotorDeRegras`, `RegraImagemSemTextoAlternativo`, `CatalogoWcag` |

Uma regra: `IMAGEM_SEM_TEXTO_ALTERNATIVO`, WCAG 1.1.1 nível A, com
aplicabilidade a documento não-web resolvida via WCAG2ICT (ADR 0007).

## Números

- 46 testes unitários + 7 E2E (Testcontainers: Postgres e Kafka reais)
- 1 regra, 1 critério na tabela WCAG
- Corpus real coletado: 13 URLs semente, 9 utilizáveis, 4 documentos com imagem,
  **zero alt text preenchido**

## Decisões registradas como ADR

- ADR 0001 — DOCX como formato do MVP
- ADR 0003 — Kafka como fronteira entre runtimes
- ADR 0004 — autenticação e isolamento por linha (ainda não implementado)
- ADR 0006 — fora de escopo
- ADR 0007 — WCAG2ICT como base normativa
- ADR 0008 — extração por XML direto, sem Apache POI

ADR 0002 e 0005 estão como **proposta**: dependem de resposta minha.

## O que o code review encontrou (e o que mudou por causa dele)

1. **Falso positivo no extrator.** `wp:docPr` existe em qualquer desenho —
   caixa de texto, autoforma, gráfico. Tudo virava "imagem sem alt". Agora um
   desenho só conta como imagem com `pic:pic`, `a:blip` ou `v:imagedata` na
   subárvore.
2. **Falha silenciosa no consumidor.** Qualquer exceção derrubava a transação e
   a análise voltava para `RECEBIDA` para sempre, sem log de erro. Agora falha
   permanente vira `FALHOU` numa transação nova (`REQUIRES_NEW`), e falha
   transitória sobe para o Kafka reentregar.
3. **Extrator de produção sem teste.** Os 26 testes verdes eram do `spike/`, que
   já tinha divergido do backend. Agora há suíte própria.
4. **Entidade JPA cruzando para a API.** `VisaoDaAnalise` carregava `Analise` e
   `Problema` até o pacote `api`. Virou record puro convertido dentro da
   transação.
5. **`XMLInputFactory` compartilhada** em bean singleton (não é thread-safe).
   Virou `ThreadLocal`.
6. **Manifesto do corpus reescrito do zero** a cada execução do script, e o
   SHA-256 gravado em vez de conferido. Agora o manifesto é mesclado e a
   divergência de hash para a coleta com código de saída 2.

## As perguntas do contrato

> As quatro perguntas que o §1 exige: o que foi construído, por que dessa forma,
> qual alternativa foi descartada e por quê, e o que eu ainda não sei defender.

### O que eu construí

Uma fatia fina que atravessa o sistema inteiro e funciona: sobe um `.docx`,
volta um `id`, e no `GET` aparece um problema de acessibilidade real, citando
critério WCAG, com evidência.

O caminho é `POST /analyses` → validação do binário → grava análise e bytes no
Postgres → publica `AnaliseSolicitadaV1` no Kafka → consumidor extrai as imagens
do OOXML por StAX → `MotorDeRegras` aplica **uma** regra → problemas
persistidos → `GET /analyses/{id}`.

Uma regra só, e de propósito: `IMAGEM_SEM_TEXTO_ALTERNATIVO`, WCAG 1.1.1 nível
A. O valor da slice não está no número de regras — está em provar que as sete
peças conversam com Postgres e Kafka de verdade, sob Testcontainers.

### Por que desta forma e não de outra

**O evento não carrega os bytes.** Ele carrega o `id`. Payload de megabytes num
tópico Kafka transforma o broker em armazenamento de arquivo, e o limite de
tamanho de mensagem vira um problema de configuração que aparece com o primeiro
documento grande de verdade. O consumidor relê o binário do banco, que é onde
ele já estava.

**A publicação acontece depois do commit, e isso custa uma coisa.** Publicar
dentro da transação deixaria o consumidor ler o evento antes de a linha existir
— "evento cedo demais". Publicar depois abre a janela oposta: se o processo
morrer entre o commit e a publicação, a análise fica em `RECEBIDA` para sempre.
Escolhi a janela que não corrompe leitura, e registrei a outra como dívida
explícita. Ela é exatamente o que o outbox da Slice 3 fecha.

**A validação lê a assinatura do zip, não a extensão.** O §5 diz que arquivo
enviado por usuário é hostil e que o MIME real tem que ser validado. Extensão é
declaração de quem envia; os bytes `PK` no começo e as partes OOXML são o
arquivo dizendo o que ele é. Junto vem o teto de descompressão, porque zip é o
formato onde a bomba é fácil.

**`descr=""` não é defeito.** Alt vazio é declaração de imagem decorativa, e o
WCAG 1.1.1 permite. Tratar vazio como ausente geraria violação em documento
correto — e falso positivo num produto de score é pior que regra faltando,
porque destrói a confiança em todas as outras.

### Qual alternativa eu descartei e por quê

| Alternativa | Por que não |
|---|---|
| **Apache POI na extração** | O caso decisivo é desenho dentro de `mc:AlternateContent`: o XmlBeans do POI só vincula o que está no schema de `w:r`, então `getDrawingList()` devolve **zero imagens**. Não é alt errado, é imagem nenhuma — falso negativo silencioso, que num produto de score significa afirmar conformidade inexistente. E o código empatou: 112 linhas contra 124, com 13 jars e ~18 MB de diferença. ADR 0008. |
| **`@Async` no lugar do Kafka** | Morre com a JVM e não alcança o Python. O ML Service da Slice 5 é outro runtime, e a fronteira precisa sobreviver a restart e permitir replay. D3 da `fase-0.md`. |
| **Outbox já na Slice 1** | Slice fina é slice fina. A janela de perda existe, está medida e registrada; fechá-la exige tabela, worker e política de retry, que é o conteúdo inteiro da Slice 3. Antecipar teria inflado a primeira fatia sem provar nada a mais. |
| **Binário fora do Postgres desde já** | MinIO ou S3 adicionaria um serviço ao compose para resolver um problema que ainda não dói. O alvo é `docker compose up` local (ADR 0006). `bytea` é dívida assumida, e ela não sobrevive à Slice 5. |

### O que eu ainda não sei defender numa entrevista

1. **`bytea` no banco principal.** Sei por que está lá; não sei responder bem
   "e quando o documento tiver 50 MB?" sem admitir que a resposta é migrar.
2. **Por que Kafka num projeto sem volume.** O argumento honesto é fan-out,
   desacoplamento entre runtimes e replay — nunca throughput. Sei a frase, mas
   ela precisa sair antes de perguntarem, não depois.
3. **O corpus não valida a extração.** As fixtures são sintéticas. Nunca rodei
   contra export real de Google Docs ou LibreOffice, que é justamente onde o
   OOXML diverge.
4. **StAX e `ThreadLocal`.** Troquei a `XMLInputFactory` singleton por
   `ThreadLocal` porque ela não é thread-safe. Sei o sintoma, não sei descrever
   o modo de falha concreto sob concorrência.

## Dívida consciente que segue aberta

- Sem outbox: se o processo morrer entre o commit e a publicação, a análise fica
  em `RECEBIDA`. Slice 3.
- Sem retry com backoff nem DLT. A política de falha atual é o mínimo. Slice 3.
- Sem score. Slice 2.
- Sem autenticação e sem `owner_id`. ADR 0004.
- Binário em `bytea` no banco principal — não sobrevive à Slice 5.
- Fixtures e corpus do extrator são sintéticos. Validação com exports reais de
  Word, Google Docs e LibreOffice continua pendente.
