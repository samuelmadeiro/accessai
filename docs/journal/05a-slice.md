# Slice 5A — Autenticação JWT, isolamento por linha e rate limit

> **Procedência desta entrada.** O texto foi rascunhado em par com o Claude, a
> partir dos ADRs e do código, e **revisado e adotado por mim** — as posições
> aqui são as minhas. Registrado porque o `CONTRIBUTING.md` §1 pede a entrada
> com as minhas palavras, e omitir como ela foi escrita seria o mesmo tipo de
> silêncio que o §1 existe para impedir.

- **Estado:** `./mvnw verify` verde — 187 testes unitários e 20 E2E
- **Critério de pronto do §7:** teste de integração em que o usuário A recebe
  **404** ao pedir a análise do usuário B — **cumprido**, em
  `IsolamentoPorUsuarioIT`
- **Decisão registrada:** ADR 0004 (D4), que estava **aceito e não
  implementado** desde a Fase 0

---

## Por que esta slice existe

Ela não estava na tabela do §7. Foi criada porque uma auditoria da arquitetura
contra o código encontrou o pior tipo de dívida: **decisão aceita sem dono no
plano.**

O D4 decidiu `owner_id` em toda tabela de domínio, isolamento por
`findByIdAndOwnerId`, JWT stateless e rate limit por usuário. Nada disso existia,
e nenhuma slice era responsável — a tabela ia da 5 direto para IA, e a 9 é
"observabilidade, hardening". Decisão aceita sem dono não acontece.

Entrou **antes da 6** porque `owner_id` toca toda tabela, todo repositório e todo
endpoint. Retrofitar depois do AI Gateway significaria reescrever consulta em
código recém-escrito e migrar tabela com dado dentro.

## O que foi construído

| Peça | Arquivo |
|---|---|
| Migration | `V5__usuario_e_owner_id.sql` (tabela `usuario`, `owner_id` em `analise`) |
| Conta | `autenticacao/Usuario`, `UsuarioRepository` |
| Cadeia de segurança | `autenticacao/SegurancaConfig` |
| Cadastro e login | `autenticacao/ServicoDeAutenticacao`, `AutenticacaoController` |
| Dono da requisição | `autenticacao/UsuarioAutenticado` |
| Rate limit | `autenticacao/LimitadorDeUpload` + Redis no `docker-compose.yml` |
| Isolamento | `AnaliseRepository.findByIdAndOwnerId` |
| Provas | `IsolamentoPorUsuarioIT`, `RateLimitDeUploadIT` |

## Decisões que valem explicar

1. **404, não 403** — e desde o repositório. `findByIdAndOwnerId` devolve vazio
   tanto para "não existe" quanto para "não é seu", e os dois caem no mesmo
   caminho. Um 403 confirmaria a existência do id para quem está sondando.
2. **Método explícito, não filtro global do Hibernate.** Filtro global é fácil
   de esquecer de ligar, e o esquecimento é silencioso: não quebra teste, só
   vaza a análise de outra pessoa. Na assinatura, o isolamento é visível.
3. **`owner_id` só em `analise`.** As demais tabelas penduram nela por FK com
   cascade. Repetir a coluna criaria quatro lugares onde o dono pode divergir do
   dono da análise — e divergência de ownership é falha de segurança, não
   inconsistência de dado.
4. **A migration em três passos.** `ADD COLUMN NOT NULL` direto quebraria
   qualquer banco com análise gravada. O usuário de sistema que adota as linhas
   órfãs tem senha **inutilizável**: o campo tem a forma de um BCrypt, para
   passar no `CHECK`, mas o miolo é aleatório e não é digest de senha nenhuma.
5. **Segredo simétrico (HS256), do ambiente, sem padrão.** Quem emite e quem
   valida é o mesmo serviço; par de chaves custaria rotação e distribuição para
   resolver problema que não existe. Sem `ACCESSAI_JWT_SECRET` a aplicação **não
   sobe** — um padrão de desenvolvimento aqui viraria segredo publicado.
6. **Nenhuma biblioteca de JWT de terceiro.** O `oauth2-resource-server` já traz
   o Nimbus. Uma dependência a menos no caminho de autenticação é uma superfície
   de CVE a menos onde ela dói.
7. **O token carrega só o `sub`.** Email dentro do token seria dado pessoal
   viajando em toda requisição, gravado em log de proxy. O id basta para o
   isolamento.
8. **Email inexistente e senha errada dão a mesma resposta.** Distinguir os dois
   transforma o login num oráculo de quais emails estão cadastrados.
9. **O rate limit falha ABERTO.** Redis fora do ar libera o upload, com aviso no
   log. Falhar fechado transformaria uma proteção opcional em ponto único de
   falha — a mesma escolha que o ADR 0011 faz para o ML Service.
10. **Janela fixa, não deslizante.** Permite um pico no limite entre janelas.
    Aceito: a alternativa custa um sorted set por usuário, e o que este limite
    protege é orçamento, não integridade.

## O defeito que só o teste revelou

`JwtEncodingException: Failed to select a JWK signing key`. O `NimbusJwtEncoder`
assume RS256 quando o cabeçalho não declara o algoritmo, procura uma chave RSA
que não existe e falha com uma mensagem que aponta para **chave ausente** quando
o problema é **algoritmo errado**. Resolvido declarando `JwsHeader` com HS256.

E um susto que vale registrar: o `verify` deu BUILD SUCCESS com os 15 E2E
**pulados**, porque o Docker tinha caído. Verde de teste que não rodou é pior que
vermelho.

## As perguntas do contrato

> As quatro perguntas que o §1 exige: o que foi construído, por que dessa forma,
> qual alternativa foi descartada e por quê, e o que eu ainda não sei defender.

### O que eu construí

Transformei um sistema de um usuário implícito num sistema multiusuário com
isolamento provado. O que importa não é o login — é a linha
`findByIdAndOwnerId` e o teste que prova que ela funciona.

E o rate limit, que é a peça que faz o D4 fechar inteiro: upload é a operação
cara do sistema — descompacta OOXML, roda seis regras, chama o ML e, a partir da
Slice 6, gasta orçamento de LLM. Sem teto, uma conta consome o de todas.

### Por que desta forma e não de outra

**Porque o entregável do D4 é um teste, e eu escrevi o teste.** O ADR não diz "o
isolamento será aplicado": diz que a prova é um teste, e nomeia o cenário. Antes
disso a decisão estava aceita e não verificada — que é o mesmo que não decidida.

**O limite é cobrado antes de ler os bytes.** Contar depois faria o trabalho caro
acontecer mesmo para quem já passou do teto, que é exatamente o que o limite
existe para evitar.

### Qual alternativa eu descartei e por quê

| Alternativa | Por que não |
|---|---|
| **Filtro global do Hibernate** | Silencioso quando esquecido. O D4 escolhe método explícito de propósito. |
| **403 para recurso de outro dono** | Confirma que o id existe. |
| **RS256 com par de chaves** | Emissor e validador são o mesmo serviço. Rotação e distribuição de chave pública para nada. |
| **jjwt / auth0-java-jwt** | Dependência nova no caminho mais sensível do sistema, para o que o Nimbus já faz. |
| **Refresh token** | Cortado no D4. Invalidação de refresh é a parte mais delicada de autenticação, e o ganho num projeto de um usuário é zero. |
| **`owner_id` em todas as tabelas** | Quatro lugares onde o dono pode divergir. |
| **Rate limit falhando fechado** | O sistema pararia de aceitar documento porque um contador não respondeu. |
| **Janela deslizante** | Sorted set por usuário e limpeza periódica, para proteger orçamento. |

### O que eu ainda não sei defender numa entrevista

1. **Não há revogação de token.** Oito horas de validade e nenhuma lista de
   bloqueio: um token vazado vale até expirar. Sei a mitigação (validade curta),
   não sei defender a ausência da lista.
2. **BCrypt e não Argon2.** A escolha foi "o que vem sem dependência extra". A
   diferença só importa contra quem já tem o dump — mas essa é exatamente a
   situação em que o hash importa.
3. **O usuário de sistema da migration.** Ele adota as análises órfãs e fica no
   banco para sempre. Não sei responder bem "e se alguém conseguir logar nele?"
   além de "o hash não é de senha nenhuma".
4. **Janela fixa permite o dobro do teto na virada.** Sei o número; não ensaiei
   dizer por que isso é aceitável sem soar como preguiça.
5. **Não medi o custo do BCrypt no login.** Custo 10 é ~100 ms; com muitos
   logins simultâneos isso é CPU, e eu não olhei.

## Dívida consciente que segue aberta

- **Sem revogação de token** e sem refresh.
- **Sem `/auth/me`** nem troca de senha: só cadastro e login.
- **Rate limit só no upload.** As demais rotas são baratas, mas "baratas" não foi
  medido.
- **O usuário de sistema fica no banco** depois de adotar as linhas órfãs.
- **Não há RBAC nem organização** — cortados no D4, e a decisão continua de pé.
