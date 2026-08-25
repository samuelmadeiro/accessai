# ADR 0004 - Autenticacao e isolamento por linha

- **Status:** aceita e **implementada na Slice 5A**. O entregavel desta decisao
  — o teste em que o usuario A recebe 404 ao pedir a analise do usuario B — esta
  em `IsolamentoPorUsuarioIT`. O rate limit por usuario esta em
  `LimitadorDeUpload`, com Redis no `docker-compose.yml`.

  A slice 5A foi criada porque esta decisao estava aceita sem dono no plano: a
  tabela do §7 ia da 5 direto para IA.
- **Decisao original:** D4 de `docs/architecture/fase-0.md`

## Contexto

Usuario unico, multiusuario ou multi-tenant? A escolha decide schema, seguranca
e quanto tempo o projeto gasta longe do proprio tema.

## Decisao

Multiusuario, single-tenant por usuario, isolamento por linha.

- Toda tabela de dominio ganha `owner_id` (FK para `users`).
- Isolamento no repositorio com metodos explicitos (`findByIdAndOwnerId`), nao
  com filtro global do Hibernate: filtro global e facil de esquecer e o
  esquecimento e silencioso.
- **A prova e um teste**, nao uma afirmacao: usuario A recebe 404 ao pedir a
  analise do usuario B.
- JWT stateless (Spring Security), sem sessao em servidor.
- Redis entra em rate limit por usuario, deduplicacao de consumer e contador de
  gasto de LLM. Nao guarda sessao.

## Cortado por over-engineering

Organizacoes, RBAC, convites, refresh token rotation, MFA.

## Consequencias

A migration que introduzir autenticacao adiciona `owner_id` e o indice de
isolamento - mais o teste de 404 entre usuarios. Ate la a API e aberta: a Slice 1
nao tem autenticacao, e isso esta declarado no README.
