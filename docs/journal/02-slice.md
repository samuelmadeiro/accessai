# Slice 2 — Rule Engine completo e score por princípio WCAG

> **Procedência desta entrada.** O texto foi rascunhado em par com o Claude, a
> partir dos ADRs e do código, e **revisado e adotado por mim** — as posições
> aqui são as minhas. Registrado porque o `CONTRIBUTING.md` §1 pede a entrada
> com as minhas palavras, e omitir como ela foi escrita seria o mesmo tipo de
> silêncio que o §1 existe para impedir.

- **Estado:** `./mvnw verify` verde — 144 testes unitários e 6 E2E
- **Decisão registrada:** ADR 0009 (modelo de score)

---

## O que foi construído

De uma regra para seis, todas determinísticas e todas citando a tabela
versionada — nenhuma escreve critério, nível ou numeração em código.

| Regra | Critério | Como detecta |
|---|---|---|
| `IMAGEM_SEM_TEXTO_ALTERNATIVO` | 1.1.1 A | herdada da Slice 1 |
| `TABELA_SEM_CABECALHO` | 1.3.1 A | primeira linha sem `w:tblHeader`; tabela vazia não é cobrada |
| `ORDEM_HIERARQUICA_CABECALHOS` | 1.3.1 A | `w:outlineLvl` antes de nome de estilo, que é regional |
| `TITULO_AUSENTE` | 2.4.2 A | `dc:title` ausente ou em branco em `docProps/core.xml` |
| `LINK_SEM_TEXTO_DESCRITIVO` | 2.4.4 A | expressão genérica ou URL como texto visível |
| `IDIOMA_NAO_DECLARADO` | 3.1.1 A | sem `w:lang` no padrão do documento |

**Extração em passagem única.** `ExtratorDeImagens` virou `ExtratorDeDocumento`:
uma iteração no `ZipInputStream` alimenta sete coletores — imagens, tabelas,
títulos, links, idioma, `dc:title`, relacionamentos — e cada coletor declara que
partes aceita. A assinatura das regras passou de `List<ImagemDoDocumento>` para
`avaliar(DocumentoExtraido)`.

**Score por princípio POUR.** Nota 0–100 por princípio WCAG, com a categoria
saindo do **número do critério** (1.x Perceptível, 2.x Operável, 3.x
Compreensível, 4.x Robusto). Pesos e penalidades em `application.yml`, porque são
escolha e não medida. Princípio sem regra implementada fica fora da média, com
os pesos renormalizados, e aparece em `naoAvaliados`.

## Decisões que valem explicar

1. **A categoria sai da numeração, não de uma tabela.** Um mapa regra→categoria
   escrito à mão seria uma segunda fonte de verdade para algo que a própria
   numeração da WCAG já define — e as duas divergiriam no primeiro critério novo.
2. **Princípio não verificado não vale 100.** Dar nota cheia a uma categoria que
   o sistema não checa é afirmar conformidade inexistente. É o mesmo defeito do
   falso negativo silencioso que tirou o POI do caminho de extração (ADR 0008).
3. **O score é calculado na leitura, não persistido.** Número salvo diverge no
   dia em que um peso mudar, e número sem rastro de como foi obtido é pior que
   número nenhum.
4. **Tabela vazia não é cobrada.** Tabela sem dados não tem cabeçalho para
   faltar. Cobrar seria transformar layout em violação.
5. **Link sem texto fica com a 1.1.1.** `LINK_SEM_TEXTO_DESCRITIVO` cobra texto
   ruim, não texto ausente — duas regras cobrando o mesmo achado dobrariam a
   penalidade sobre um problema só.
6. **As fixtures `.docx` binárias saíram.** Os pacotes passaram a ser montados em
   memória, com o XML à vista ao lado da asserção. Fixture binária é teste que
   ninguém consegue ler nem ajustar seis meses depois.

## As perguntas do contrato

> As quatro perguntas que o §1 exige: o que foi construído, por que dessa forma,
> qual alternativa foi descartada e por quê, e o que eu ainda não sei defender.

### O que eu construí

O Rule Engine deixou de ser demonstração e virou o produto. Seis regras
determinísticas, cada uma com suíte própria incluindo o **caso de conformidade
que ela se recusa a marcar** — que é a metade que costuma faltar em teste de
regra e é onde mora o falso positivo.

E o score, que é a peça que transforma uma lista de achados em resposta:
"quão acessível é este documento, por quê, contra qual critério".

### Por que desta forma e não de outra

**A extração virou passagem única porque reabrir o zip por coletor não escala em
clareza, não em desempenho.** Cada regra nova abriria o pacote de novo e
descobriria sozinha que parte ler; sete coletores declarando o que aceitam
mantêm a decisão num lugar só.

**O score é soma ponderada de penalidades determinísticas, e cada ponto perdido
rastreia até um problema com evidência.** É a propriedade que o produto existe
para ter — e é por isso que o §6 proíbe predição de ML no score. Um número que
não se explica linha a linha não vale mais que um selo.

**Os pesos são 25% iguais porque não há evidência publicada para hierarquizar
princípios.** Um 35/30/25/10 inventado daria ao score uma precisão que ele não
tem. Quem mudar passa a ter que defender o motivo — e o `application.yml` deixa
isso barato.

### Qual alternativa eu descartei e por quê

| Alternativa | Por que não |
|---|---|
| **Manter as cinco categorias** (Structure/Content/Visual/Semantic/Metadata) | Exigia mapeamento manual por regra e deixava *Visual* sem nenhuma regra até a de contraste existir — inflando a nota por uma categoria vazia. |
| **POUR agora e cinco categorias depois** | Duas definições de score convivendo no README. É o tipo exato de inconsistência que o code review da Slice 1 apontou. |
| **Categoria vazia valendo 100** | Dá nota a um princípio que o sistema não verifica. |
| **Persistir o score** | Cópia que diverge quando um peso mudar. |
| **Contraste (1.4.3) nesta slice** | A cor efetiva de um run vem de uma cascata de cinco níveis, mais aritmética de `themeTint`/`themeShade`, mais fundo em quatro lugares. O ADR 0001 já dizia que é a parte mais cara do projeto; entrar aqui teria consumido a slice inteira. |

### O que eu ainda não sei defender numa entrevista

1. **A categoria *Robusto* nunca é avaliada.** Nenhuma regra 4.x existe, então
   ela vive em `naoAvaliados`. A renormalização é honesta, mas a pergunta "então
   seu score cobre três quartos da WCAG?" ainda me pega.
2. **Contraste continua fora**, e é a regra que qualquer pessoa espera de uma
   ferramenta de acessibilidade.
3. **`w:outlineLvl` antes de nome de estilo.** Sei que nome de estilo é regional
   e quebra em Word em português; não sei dizer com precisão o que acontece em
   documento que usa estilos personalizados sem `outlineLvl`.
4. **Heading formatado à mão não foi implementado.** Era a regra que o ADR 0001
   usou para justificar DOCX — "a regra mais interessante do projeto" — e ela
   não está aqui.
5. **Seis regras é pouco perto do Verificador do Word.** A resposta é que o
   produto é outro (score, WCAG citada, API, lote), mas ela precisa sair sem
   soar defensiva.

## Dívida consciente que segue aberta

- **Contraste (1.4.3)** adiado — cascata `themeTint`/`themeShade` (ADR 0001).
- **Heading formatado à mão** não implementado, apesar de ser o argumento de D1.
- **Princípio Robusto sem nenhuma regra** — fica sempre em `naoAvaliados`.
- **`HYPERLINK` legado** não é mapeado.
- **Sem outbox, retry ou DLT.** O pipeline assíncrono segue no mínimo. Slice 3.
- Autenticação e `owner_id` seguem pendentes desde a Slice 1 (ADR 0004).
