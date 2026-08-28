# Slice 8 — Frontend acessível de verdade

> **Procedência desta entrada.** Rascunhada em par com o Claude, a partir do
> código e dos ADRs, e **revisada e adotada por mim**. Registrado porque o
> `CONTRIBUTING.md` §1 pede a entrada com as minhas palavras.

- **Critério de pronto do §7:** navegação 100% por teclado, testado com leitor de
  tela — **cumprido pela metade, e a metade que falta está dita abaixo**
- **Estado:** `frontend/` com quatro arquivos, servido pelo próprio Boot, mais 16
  testes que auditam a própria interface

---

## Por que sem framework

Não há React, nem build, nem `npm install`. HTML, CSS e três arquivos de
JavaScript que o navegador executa como estão.

A slice é sobre a interface **ser acessível**, e nenhum bundler ajuda nisso. O
que ele traria é um passo a mais entre o que eu escrevo e o que roda —
e num projeto que existe para ser defendido numa entrevista (§1), código que dá
para ler é código que dá para defender. O alvo continua sendo `docker compose up`
(ADR 0006): quatro arquivos estáticos não justificam um segundo container nem uma
etapa de build.

O `frontend/` fica fora do módulo Java, como o `CONTRIBUTING.md` §10 previu, e o
Maven copia para `static/` no build — o mesmo mecanismo que já traz
`docs/wcag/criteria.json` para dentro do backend.

## O que a interface faz

Entrar ou criar conta, enviar um `.docx`, acompanhar a análise até concluir, ler
o score por princípio e os problemas, gerar recomendações e conversar com o
copiloto. É o sistema inteiro das slices 1 a 7 pela tela.

## Decisões de acessibilidade que valem explicar

1. **Foco movido quando o conteúdo muda.** Ao criar a conta, o foco vai para o
   título "Enviar documento". Sem isso, quem navega por teclado continua no botão
   que apertou enquanto a seção nova aparece atrás, fora do caminho. O
   `tabindex="-1"` no título existe só para permitir esse foco por programa — ele
   não entra na ordem de tabulação.

2. **Região viva desde o carregamento, e sempre `polite`.** A situação da análise
   muda sozinha enquanto o backend processa. Sem `aria-live`, a página mudaria em
   silêncio. `assertive` interromperia a leitura em curso, e nada aqui é urgente
   a esse ponto.

   A mensagem de "analisando" carrega o horário e muda a cada ciclo **de
   propósito**: leitor de tela ignora região viva cujo texto não mudou, e a
   pessoa ficaria sem saber se a página ainda está viva.

3. **Erro de campo é estado, não cor.** `aria-invalid` no campo mais o texto num
   `span` apontado por `aria-describedby`. A borda vermelha é só o reflexo — a
   cor sozinha deixa de fora quem não a distingue (1.4.1).

4. **Validação própria, com `novalidate` no formulário.** A bolha nativa do
   navegador some sozinha, é lida de forma inconsistente entre leitores e não
   deixa rastro na página para quem voltar ao campo depois.

5. **`textContent`, nunca `innerHTML`.** Nome de arquivo, evidência extraída do
   `.docx` e resposta do copiloto vêm de fora. O §5 já trata conteúdo de terceiro
   como hostil ao montar prompt; na tela vale o mesmo, e `innerHTML` transformaria
   qualquer um deles em XSS. Tem teste.

6. **A recusa do guardrail aparece como resposta do copiloto, não como erro.**
   Perguntar por 1.4.3 num documento onde contraste nunca foi medido devolve 422,
   e a tela mostra "Não posso responder isso" no fio da conversa. Recusar por
   falta de base é o comportamento correto — pintar isso de vermelho como falha
   técnica ensinaria a pessoa errada a coisa errada.

7. **A ressalva do score fica ao lado do número.** "Sem problema no que foi
   medido" não é "documento acessível", e a lista de princípios não avaliados
   aparece junto. Essa distinção é o produto (§6), não uma nota de rodapé.

8. **Contraste conferido, não estimado.** `#1b1f24` sobre branco dá 15,8:1;
   `#0b5cab` sobre branco, 6,3:1. O modo escuro tem os próprios pares, também
   conferidos. Botão desabilitado não usa opacidade baixa — ela derrubaria o
   contraste do texto.

## O teste que audita a própria interface

`AcessibilidadeDoFrontendTest` lê os arquivos de `static/` — o que o Boot
realmente serve, não a origem — e cobra: `lang`, `title`, um único `h1`, link de
pular apontando para âncora existente, três marcos, todo campo com `label for`,
nenhum `tabindex` positivo, toda imagem com `alt`, nenhum `onclick`, região viva
no HTML inicial, tabela com `caption` e `th scope`, e nenhum `innerHTML`.

É a mesma régua que o Rule Engine aplica ao `.docx` de terceiro, virada para
dentro. Uma ferramenta que audita acessibilidade com interface inacessível é
autogol — a `fase-0.md` diz isso com essas palavras ao proteger esta slice.

**O teste pegou o primeiro defeito nele mesmo:** a versão inicial reprovou o
comentário do `app.js` que explica por que `innerHTML` não é usado. A regra
estava certa e a checagem é que era grosseira; agora ela tira os comentários
antes de olhar o código. A alternativa — apagar o comentário para o teste passar —
teria empurrado a explicação para fora do arquivo em nome da própria regra.

## O que eu verifiquei no navegador, e como

Com a aplicação rodando contra Postgres, Kafka e Redis reais, dirigindo o
navegador:

- **Ordem de tabulação em `index.html`:** pular → e-mail → senha → Entrar →
  Criar conta → link do WCAG2ICT. Nenhuma parada perdida, nenhuma armadilha.
- **O link de pular sai da tela e volta no foco** (`left` de −9999px para 0).
- **Cada campo anuncia o próprio rótulo e a própria ajuda** (`label for` e
  `aria-describedby` conferidos no elemento focado).
- **O foco foi para o `h2` "Enviar documento"** depois de criar a conta, com
  `Sessão iniciada.` na região viva.
- **Fluxo inteiro pela tela:** upload → `CONCLUIDA` → score 87 com três
  categorias → três problemas → três recomendações declarando
  `fixture local — nenhum modelo de linguagem foi consultado` → conversa com
  turno gravado → **recusa do guardrail** para "e o contraste 1.4.3?", com a
  lista do que a análise mediu.
- **O foco volta para o campo de pergunta** depois de cada turno.

## O que NÃO foi feito, e é o critério do §7

**Não testei com leitor de tela.** O §7 pede navegação por teclado *testada com
leitor de tela*, e isso é um passo manual com NVDA ou Narrator que eu preciso
fazer. Sem ele, o que existe é a estrutura correta e a evidência de que o teclado
alcança tudo — não a prova de que a experiência de áudio é boa.

**A ativação por Enter e Espaço não pôde ser reproduzida na automação.** O
navegador ativa `<button>` nativo com as duas teclas por conta própria, e o
markup usa `<button>` em todo controle — mas o harness não gera o evento
confiável que dispara essa ativação, então cliquei para seguir o fluxo. É
verificação pendente junto com o leitor de tela.

Enquanto esses dois não acontecerem, **a Slice 8 não está fechada.** Prefiro
deixá-la aberta a marcar como pronta uma coisa que ninguém ouviu.

## O que ficou em aberto

- Leitor de tela e ativação por teclado, acima.
- **Não há listagem de análises** — a API não tem o endpoint, então a tela também
  não tem. O id vem da URL depois do upload; recarregar sem o id perde o caminho.
- **Sem rate limit visível na tela.** O 429 chega como mensagem de erro genérica,
  sem o `Retry-After` que o backend manda.
- **Sem `prefers-contrast` nem tamanho de fonte ajustável na interface.** O zoom
  do navegador resolve, mas não é a mesma coisa.
