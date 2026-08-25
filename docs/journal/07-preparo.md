# Slice 7 — preparação de ambiente (antes de qualquer código do copiloto)

> **Procedência desta entrada.** Rascunhada em par com o Claude, a partir dos
> ADRs e do código, e **revisada e adotada por mim**. Registrado porque o
> `CONTRIBUTING.md` §1 pede a entrada com as minhas palavras, e omitir como ela
> foi escrita seria o mesmo tipo de silêncio que o §1 existe para impedir.

- **Estado:** `./mvnw verify` verde — 235 unitários e 27 E2E, medidos com Docker
  no ar
- **Escopo:** nenhuma linha do copiloto. Só fronteira decidida e travada.

---

## Por que preparar antes

A Slice 7 estava **cortada por escrito** na `fase-0.md`. Estou revertendo, e a
reversão só se sustenta porque o desenho mudou — não porque eu mudei de ideia
sobre o argumento original.

O corte dizia: "chat em cima disso todo mundo tem". Ele valia contra um copiloto
que recebe o documento. Esse seria um segundo analisador — LLM opinando sobre
acessibilidade sem regra, sem critério versionado e sem evidência —, e o produto
passaria a ter duas fontes de achado sem nada dizer ao usuário qual produziu o
quê.

**O copiloto redesenhado não recebe o documento.** Recebe a `Analise` já
produzida e conversa sobre os `Problema` que o motor determinístico gerou. Sem
acesso ao `.docx`, ele não tem como opinar sobre o que a regra não mediu: a
impossibilidade é estrutural, não uma instrução no prompt que o modelo pode
ignorar.

Preparar antes é o ponto inteiro. Fronteira verificada depois que o código existe
é fronteira negociada com o código que já a violou — e a violação chega como diff
grande, no fim da slice, quando desfazer custa caro.

## O que foi construído

| Peça | Onde |
|---|---|
| Fronteira (I1–I5) e onde a conversa pendura | `docs/adr/0012-fronteira-do-copiloto.md` |
| Retenção multi-turno e dado pessoal | `docs/adr/0013-retencao-de-conversa.md` |
| Reversão do corte, anotada nos dois lugares | `fase-0.md` + `CONTRIBUTING.md` §7 |
| JDK travado no build | `maven-enforcer-plugin` + §9 |
| As invariantes como teste | `ArquiteturaDaIaTest` |
| Casa declarada do copiloto, ainda vazia | `dev/accessai/copiloto/package-info.java` |
| Pergunta do Docker, registrada | [issue #1](https://github.com/samuelmadeiro/accessai/issues/1) |

## Decisões que valem explicar

1. **Não havia ADR de privacidade.** Procurei antes de escrever: o assunto
   aparece só na condição C-3 da `fase-0.md` (dado pessoal de **terceiro** em
   `.docx` público, resolvido versionando manifesto em vez de binário) e no item
   7 do journal da 5A (o JWT carrega só o `sub`). Nenhuma das duas trata de
   retenção de conteúdo do usuário — então o 0013 é novo, e não concorre com
   nada.

2. **Trecho de documento fica fora do histórico, e a distinção é sutil.**
   Continuar **enviando** a evidência ao provider é o que torna a resposta
   fundamentada; sem isso não há produto. **Persistir** a evidência numa segunda
   tabela não acrescenta nada — ela já está em `problema.evidencia` — e criaria
   uma cópia do mesmo dado pessoal com ciclo de vida próprio. Cópia de dado
   pessoal com ciclo de vida próprio é como se perde o controle dos dois.
   O prompt é reconstruído a cada turno pelo `MontadorDePrompt`, que já é o único
   lugar que monta prompt.

   Custo aceito, dito aqui para não virar surpresa: não dá para auditar depois o
   texto exato enviado num turno passado.

3. **A conversa não ganha `owner_id`.** Fica filha de `analise`, herdando o
   isolamento via `findByIdAndOwnerId`. É o que a V5 já decidiu para as outras
   filhas, e o precedente está em código: `RecomendacaoRepository` não tem
   `ownerId` em consulta nenhuma. Duas condições ficaram escritas no ADR 0012,
   porque sem elas a herança vaza: nunca carregar conversa só pelo id dela, e
   `ON DELETE CASCADE` a partir de `analise`.

4. **As regras de arquitetura foram vistas falhando.** Escrevi uma classe
   violadora temporária em `dev.accessai.copiloto` com campos de
   `DocumentoExtraido`, `Problema` e `AiProvider`; as três regras acusaram, uma
   cada, apontando o campo exato. Depois apaguei. Regra de arquitetura que
   ninguém viu falhar não prova nada — ela pode estar verde porque não verifica
   coisa alguma.

5. **O pacote do copiloto nasce vazio, com `package-info.java`.** Regra sobre
   pacote inexistente passa por vacuidade. Com `allowEmptyShould(false)` mais o
   pacote declarado, a guarda já está de pé antes da primeira classe existir.

6. **`Problema.Nivel` e `Problema.Severidade` ficaram FORA da proibição de I1.**
   `VisaoDaAnalise.ProblemaVisto` expõe esses dois tipos aninhados, e ler a
   severidade de um problema é leitura legítima — I1 proíbe calcular e gravar,
   não ler. Proibir o nome inteiro faria a regra impedir o copiloto de ler a
   análise, que é exatamente o que ele deve fazer.

   **Isso enfraquece a guarda, e o motivo é real:** o DTO de leitura vaza tipos
   aninhados de uma entidade JPA. Não é violação do §5 (a entidade em si não
   cruza a fronteira), mas é o tipo de acoplamento que faz uma regra de
   arquitetura precisar de exceção. Fica anotado; mover `Nivel` e `Severidade`
   para fora de `Problema` é candidato à Slice 9, não a este escopo.

## O que ficou em aberto

- **`AiProvider` ainda não tem multi-turno.** A extensão da interface é código da
  Slice 7, não da preparação. O que existe hoje é a guarda que falha se alguém
  abrir uma segunda porta em vez de estender esta.
- **Não existe endpoint de exclusão de análise.** O cascade garante que a
  exclusão, quando existir, é completa; ele não cria a exclusão. Guardar dado
  pessoal sem caminho de apagamento pelo titular é incompleto, e o lugar disso é
  a Slice 9. Está declarado no ADR 0013 para não virar descoberta tardia.
- **ADR 0005 segue em PROPOSTA.** Toda conversa nasce `FIXTURE`. Multi-turno
  reenvia contexto a cada turno, e o teto de US$ 10/mês foi estimado sobre
  chamada única — recalcular quando houver provider real.
- **[issue #1](https://github.com/samuelmadeiro/accessai/issues/1)**: sem Docker,
  `verify` passa com os 27 E2E pulados e o mesmo exit code de 27 verdes. Não há
  `.github/workflows/`, então a única execução da suíte é local. Aberta, não
  resolvida — está fora deste escopo de propósito.
