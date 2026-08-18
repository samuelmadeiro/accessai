# Slice 2 — Rule Engine completo e score por categoria

> **RASCUNHO.** O `CLAUDE.md` §1 diz que esta entrada sou eu quem escreve, com
> minhas palavras. O registro factual está montado; as perguntas do contrato
> estão marcadas **PARA COMPLETAR**. Enquanto estiverem em branco, a slice não
> está pronta.

- **Estado:** `./mvnw verify` verde — 144 testes unitários e 6 E2E

---

## O que foi construído

**Seis regras**, todas determinísticas, todas citando critério da tabela
versionada:

| Regra | Critério | Nível | Severidade |
|---|---|---|---|
| `IMAGEM_SEM_TEXTO_ALTERNATIVO` | 1.1.1 | A | ALTA |
| `TABELA_SEM_CABECALHO` | 1.3.1 | A | ALTA |
| `ORDEM_HIERARQUICA_CABECALHOS` | 1.3.1 | A | MEDIA |
| `TITULO_AUSENTE` | 2.4.2 | A | MEDIA |
| `LINK_SEM_TEXTO_DESCRITIVO` | 2.4.4 | A | MEDIA |
| `IDIOMA_NAO_DECLARADO` | 3.1.1 | A | ALTA |

**Extração reescrita.** O `ExtratorDeImagens` virou `ExtratorDeDocumento`: uma
passagem pelo pacote alimenta sete coletores (imagens, tabelas, títulos, links,
idioma, título do pacote, relacionamentos). Cada coletor declara as partes que
lhe interessam; parte que ninguém quer nem é parseada.

**Score por princípio WCAG**, calculado na leitura, com categoria sem regra fora
da média (ADR 0009).

## Decisões que valem explicar

1. **A assinatura da regra mudou** de `avaliar(List<ImagemDoDocumento>)` para
   `avaliar(DocumentoExtraido)`. Com uma regra, a lista era mais honesta; com
   seis, cada regra pediria um parâmetro diferente.
2. **`w:tblHeader` como sinal de cabeçalho de tabela.** Ele existe para repetir
   a linha entre páginas, mas é o único marcador semântico que o
   WordprocessingML oferece — `w:tblLook firstRow` é formatação. É o mesmo sinal
   que o Verificador de Acessibilidade do Word usa.
3. **Nível de título por `w:outlineLvl` antes do nome do estilo.** Nome de
   estilo varia por idioma e exportador (`Heading1`, `Ttulo1`); `outlineLvl` não.
4. **Subir de nível não é salto.** Voltar de H3 para H1 é fim de seção. Tratar
   como erro encheria de falso positivo qualquer documento com duas seções.
5. **Link sem texto não entra na regra 2.4.4.** Ele costuma envolver imagem, e
   quem responde é a 1.1.1. Marcar os dois viraria um defeito em dois problemas.
6. **Fixtures binárias saíram.** Os `.docx` de teste agora são montados em
   memória, com o XML à vista ao lado da asserção.

## PARA COMPLETAR

**O que eu construí, com minhas palavras:**

_(escrever)_

**Por que o score é POUR e não as cinco categorias que eu mesmo escrevi no
contrato:**

_(escrever — o ADR 0009 tem o argumento, mas a resposta de entrevista é sua)_

**Qual alternativa eu descartei e por quê:**

_(escrever — candidatas: persistir o score; pesos diferentes por princípio;
categoria vazia valendo 100; manter o extrator antigo ao lado do novo)_

**O que eu ainda não sei defender numa entrevista:**

_(escrever)_

## Dívida consciente que segue aberta

- **Contraste (1.4.3) não entrou.** É a regra mais cara do projeto — cascata de
  cor com `themeTint`/`themeShade` — e continua sendo a última da fila.
- **Score não é persistido.** Mudar um peso muda a nota de análises antigas.
- **Link por campo `HYPERLINK`** (`w:instrText`, forma legada) não é visto.
- **Lista simulada com hífen digitado** estava no plano do ADR 0001 e não entrou.
- Pacotes de teste continuam sintéticos; validação com exports reais pendente.
