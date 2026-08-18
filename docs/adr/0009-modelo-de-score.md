# ADR 0009 - Score por principio WCAG, com categoria vazia fora da media

- **Status:** aceita (aprovada por Samuel na Slice 2)
- **Substitui:** o modelo de cinco categorias do CLAUDE.md secao 6 original

## Contexto

CLAUDE.md secao 6 fixava `Structure 25% | Content 25% | Visual 20% |
Semantic 20% | Metadata 10%`. Ao implementar as seis regras da Slice 2, dois
problemas apareceram:

1. **Nao existe mapeamento obvio de regra para essas categorias.** 1.3.1
   (Info and Relationships) e Structure ou Semantic? A resposta teria que ser
   escrita a mao, regra por regra, e viraria uma segunda fonte de verdade que
   diverge na primeira regra nova.
2. **Visual 20% nao tem nenhuma regra** ate a de contraste existir (Slice 2, por
   ultimo, segundo o ADR 0001). Um quinto do score sempre cheio infla a nota de
   todo documento.

## Decisao

Categorias = os quatro principios WCAG, derivados do primeiro digito do
criterio: 1.x Perceptivel, 2.x Operavel, 3.x Compreensivel, 4.x Robusto.

- **Sem tabela de mapeamento.** A numeracao da WCAG ja define o principio;
  `PrincipioWcag.doCriterio("1.3.1")` e a unica traducao, e ela falha alto para
  criterio fora de 1 a 4.
- **Pesos iguais (25 cada) por padrao, configuraveis.** Nao ha evidencia
  publicada de que Perceptivel valha mais que Operavel. Numeros diferentes
  seriam invencao com cara de medicao.
- **Penalidade por severidade** (CRITICA 25, ALTA 15, MEDIA 8, BAIXA 3), tambem
  em configuracao. A escala foi escolhida para que sete problemas ALTA zerem
  uma categoria; o valor exato e arbitrario, a ordem entre eles nao e.
- **Categoria sem regra fica FORA da media**, com pesos renormalizados, e
  aparece em `naoAvaliados` na resposta.
- **O score nao e persistido.** E funcao pura dos problemas gravados mais a
  configuracao, calculada na leitura.

## Alternativas consideradas

| Alternativa | Por que nao |
|---|---|
| Manter as cinco categorias | Exige mapeamento manual por regra e deixa Visual sem regra, inflando a nota. |
| POUR agora e cinco categorias depois | Duas definicoes de score convivendo no README — o tipo de inconsistencia que o code review da Slice 1 apontou. |
| Categoria vazia valendo 100 | Da nota a um principio que o sistema nao verifica. Mesmo defeito do falso negativo silencioso do POI (ADR 0008). |
| Persistir o score no banco | Copia que diverge no dia em que um peso mudar, e numero salvo sem rastro de como foi obtido e pior que numero nenhum. |

## Consequencias

**Boas.** Regra nova cai na categoria certa sozinha. A resposta carrega
penalidade, contagem e peso por categoria, entao cada ponto perdido rastreia ate
os problemas que o causaram (CLAUDE.md secao 6). Robusto aparece hoje como nao
avaliado, o que e verdade e esta dito.

**Ruins.** Mudar um peso muda a nota de analises antigas, porque nada foi
gravado — quem quiser comparar historico vai precisar de uma coluna de score e
de versao da configuracao. Isso entra quando existir listagem ou tendencia, nao
antes.

**Limite honesto:** a nota 100 significa "sem problema no que foi medido", e nao
"documento acessivel". Com seis regras, o que nao e medido ainda e a maior parte
da WCAG. O campo `naoAvaliados` existe para que ninguem leia o numero sozinho.
