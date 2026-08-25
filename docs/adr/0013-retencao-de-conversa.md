# ADR 0013 - Retencao de conversa multi-turno e dado pessoal

- **Status:** aceita (preparacao da Slice 7)
- **Depende de:** ADR 0012 (fronteira do copiloto), ADR 0004 (isolamento por
  linha), ADR 0005 (provider de LLM, em PROPOSTA)
- **Nao substitui nada:** nao existia ADR de privacidade neste repositorio. As
  duas mencoes anteriores ao assunto sao a condicao C-3 da `fase-0.md` (dado
  pessoal de TERCEIRO em `.docx` publico, resolvida versionando manifesto em vez
  de binario) e o item 7 do `docs/journal/05a-slice.md` (o JWT carrega so o
  `sub`). Nenhuma das duas trata de retencao de conteudo do usuario.

## Contexto

Ate a Slice 6 a chamada de IA e **single-shot**: monta o prompt, chama o
provider, grava a recomendacao, esquece o prompt. Nao ha historico.

Multi-turno muda a natureza da exposicao, e nao o volume dela. Historico e um
registro persistido do que a pessoa perguntou, quando, sobre qual documento — e
ele fica. Tres fatos tornam isso mais do que uma tabela a mais:

1. **O corpus deste projeto e documento publico brasileiro** (condicao C-3):
   edital, ata, formulario. Ele contem nome, CPF e matricula de terceiros que
   nunca souberam deste sistema.
2. **A evidencia ja carrega trecho desse documento.** `Fundamento.Achado` tem
   um campo `evidencia`, que e texto extraido do `.docx`. Ele ja e enviado ao
   provider hoje, na Slice 6.
3. **Quando o ADR 0005 sair de PROPOSTA, o provider e um terceiro.** O que sai
   daqui passa a estar na infraestrutura de outra empresa, sob a politica de
   retencao dela — que nao e esta.

Sem decisao explicita, o caminho de menor esforco seria gravar o prompt inteiro
por turno, "para debugar". Isso criaria uma segunda copia persistida do trecho
de documento, numa tabela que ninguem pensou como dado pessoal, dentro de um
projeto cuja unica decisao de privacidade anterior foi **nao** comprometer esses
arquivos no git.

## Decisao

### O que persiste

Por turno de conversa, e so isto:

| Campo | Por que |
|---|---|
| `analise_id` | dono do agregado; e por onde o isolamento e a exclusao passam |
| `papel` (USUARIO / ASSISTENTE) | ordenar o dialogo |
| `texto` | a pergunta como ela foi escrita, e a resposta como ela foi devolvida |
| `procedencia` (FIXTURE / MODELO) | I5 do ADR 0012 |
| `modelo` | qual provider respondeu |
| `criado_em` | ordem e auditoria |

### O que NAO persiste

**Trecho de documento nao entra no historico.** Nem a `evidencia`, nem o prompt
montado, nem qualquer recorte do `.docx`.

A distincao que sustenta isso, e que precisa ficar clara porque e sutil:

- **Enviar ao provider** a evidencia e o que torna a resposta fundamentada. Sem
  ela nao ha produto. Isso ja acontece desde a Slice 6 e continua acontecendo.
- **Persistir** a evidencia numa segunda tabela nao acrescenta nada: ela ja esta
  gravada em `problema.evidencia`, presa ao problema que a originou, e o
  historico pode ser remontado a partir dali quando um turno novo precisar de
  contexto.

Gravar de novo criaria uma copia do mesmo dado pessoal num lugar com regra de
ciclo de vida diferente — e copia de dado pessoal com ciclo de vida proprio e
como se perde o controle de qualquer um deles. O prompt e **reconstruido** a
cada turno a partir dos problemas, pelo `MontadorDePrompt`, que ja e o unico
lugar que monta prompt.

Custo aceito: nao da para auditar depois o texto exato enviado ao provider num
turno passado. Se um dia isso for necessario para investigar uma resposta ruim,
a saida e log efemero com retencao curta e declarada, nao coluna no banco.

### Por quanto tempo

**Enquanto a analise existir. Sem TTL proprio.**

Um prazo independente — "conversa expira em 30 dias" — produziria dialogo que
perde o proprio inicio enquanto o resto segue vivo: o usuario abriria uma
conversa cujas primeiras perguntas sumiram, e o copiloto responderia sem o
contexto que ele mesmo tinha. Ciclo de vida de filha acompanhando a mae e a
regra que ja vale para `problema`, `predicao_de_alt` e `recomendacao`.

Minimizacao entra por outro lado, e no lugar certo: **o contexto enviado ao
provider e limitado aos ultimos N turnos**, por custo e por tamanho de janela.
Isso e recorte de envio, nao de retencao.

### O que acontece quando a analise e apagada

`ON DELETE CASCADE` a partir de `analise`, como toda tabela filha desde a V1. A
conversa some junto, no mesmo `DELETE`, sem rotina de limpeza que alguem precisa
lembrar de rodar.

**Pendencia declarada, e nao suposicao:** hoje **nao existe** endpoint de
exclusao de analise — o `AnaliseController` tem POST e GET. O cascade garante que
a exclusao, quando existir, e completa; ele nao cria a exclusao. Um sistema que
guarda dado pessoal e nao tem caminho de apagamento pelo titular esta incompleto
perante a LBI/LGPD, e o lugar disso e a Slice 9 (hardening). Registrado aqui
para nao virar descoberta tardia.

## Alternativas consideradas

| Alternativa | Por que nao |
|---|---|
| Gravar o prompt completo por turno | Segunda copia persistida de trecho de `.docx` de terceiro, com ciclo de vida proprio. Contradiz a C-3, que escolheu nem versionar esses arquivos. |
| TTL proprio da conversa (30/90 dias) | Dialogo que perde o inicio e mantem o fim. Minimizacao real entra no recorte de envio ao provider. |
| Nao persistir conversa nenhuma (so memoria) | Multi-turno morreria a cada restart e nao sobreviveria a duas instancias. E historico e o que o `CONTRIBUTING.md` secao 7 pede da Slice 7. |
| Tabela de conversa com `owner_id` proprio | Segundo lugar onde o dono diverge (V5, ADR 0004, ADR 0012). |
| Anonimizar a evidencia antes de enviar | Detectar CPF e nome em texto livre e um classificador que este projeto nao tem, e errar nele silenciosamente e pior que nao tentar. A evidencia e curta e presa ao problema; o recorte ja e a mitigacao. |

## Consequencias

**Boas.** O dado pessoal de terceiro vive num lugar so (`problema.evidencia`),
com um ciclo de vida so. A conversa e texto do proprio usuario mais texto que
nos geramos. Apagar a analise apaga tudo, por estrutura.

**Ruins.** Sem o prompt gravado, reproduzir exatamente um turno antigo e
impossivel — e com provider generativo, o mesmo contexto nao devolve a mesma
resposta. Investigacao de resposta ruim vira reconstrucao aproximada.

**Limite honesto.** Isto e decisao de arquitetura, nao parecer juridico. Nada
aqui foi conferido contra artigo de lei: a base e minimizacao e ciclo de vida
unico, que sao praticas defensaveis, e nao um mapeamento clausula a clausula da
LGPD. Chamar isto de conformidade seria vender rigor que o documento nao tem —
o mesmo cuidado que o ADR 0007 toma com o WCAG2ICT.
