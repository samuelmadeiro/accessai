-- Slice 5A: autenticacao e isolamento por linha (D4, ADR 0004).
--
-- A V1 ja avisava que "a migration que introduzir autenticacao e quem adiciona
-- a coluna e o indice de isolamento". Esta e ela.
--
-- Por que `owner_id` so em `analise`: as demais tabelas de dominio penduram na
-- analise por FK com ON DELETE CASCADE. Repetir a coluna nelas criaria quatro
-- lugares onde o dono pode divergir do dono da analise — e divergencia de
-- ownership e falha de seguranca, nao inconsistencia de dado. O isolamento sai
-- de um `JOIN` com a analise, que e a raiz do agregado.

CREATE TABLE usuario (
    id         UUID        PRIMARY KEY,
    -- CITEXT seria melhor, mas exige extensao. LOWER() no indice unico resolve
    -- o mesmo problema sem adicionar dependencia ao banco: dois cadastros com
    -- "Samuel@x.com" e "samuel@x.com" sao a mesma pessoa.
    email      TEXT        NOT NULL,
    -- Hash BCrypt tem 60 caracteres e comeca com $2. A checagem existe para que
    -- senha em texto claro gravada por engano NAO passe: e o tipo de defeito
    -- que so aparece depois de vazar.
    senha_hash TEXT        NOT NULL CHECK (senha_hash LIKE '$2%' AND LENGTH(senha_hash) = 60),
    criado_em  TIMESTAMPTZ NOT NULL,

    CONSTRAINT usuario_email_nao_vazio CHECK (LENGTH(TRIM(email)) > 0)
);

CREATE UNIQUE INDEX usuario_email_unico ON usuario (LOWER(email));

COMMENT ON TABLE usuario IS
    'Multiusuario, single-tenant por usuario (D4). Sem organizacao, sem RBAC, '
    'sem convite — cortados por over-engineering na Fase 0.';

-- ---------------------------------------------------------------- owner_id
--
-- Em tres passos, e nao num `ADD COLUMN ... NOT NULL` direto: a coluna nasce
-- nula para as linhas que ja existem, ganha dono, e so entao vira obrigatoria.
-- Fazer NOT NULL de uma vez quebraria qualquer banco com analise gravada — e o
-- de desenvolvimento tem.

ALTER TABLE analise ADD COLUMN owner_id UUID;

-- Dono de sistema para o que foi analisado antes de existir autenticacao. Ele
-- NAO tem senha utilizavel: o campo abaixo tem a FORMA de um BCrypt — e por
-- isso passa no CHECK — mas o miolo e aleatorio, nao e digest de senha nenhuma.
-- Nenhuma entrada faz `BCrypt.matches` devolver verdadeiro contra ele.
--
-- Deixar aqui uma conta com senha conhecida seria abrir uma porta dos fundos
-- permanente para poupar uma migration.
INSERT INTO usuario (id, email, senha_hash, criado_em)
SELECT '00000000-0000-0000-0000-000000000001'::uuid,
       'sistema@accessai.invalid',
       '$2a$10$zrY11kQf2Thy8qregQMpS5yx2pYvFUIBWyeZbRoYUpx7B01mw0tC3',
       NOW()
WHERE EXISTS (SELECT 1 FROM analise WHERE owner_id IS NULL);

UPDATE analise
   SET owner_id = '00000000-0000-0000-0000-000000000001'::uuid
 WHERE owner_id IS NULL;

ALTER TABLE analise ALTER COLUMN owner_id SET NOT NULL;

ALTER TABLE analise
    ADD CONSTRAINT analise_owner_fk FOREIGN KEY (owner_id) REFERENCES usuario (id);

-- Toda consulta de analise passa a filtrar por dono. Sem este indice, o
-- isolamento custaria um seq scan em cada leitura.
CREATE INDEX analise_owner_idx ON analise (owner_id);

COMMENT ON COLUMN analise.owner_id IS
    'Isolamento por linha (D4). Aplicado em findByIdAndOwnerId, nunca em filtro '
    'global do Hibernate: filtro global e facil de esquecer e o esquecimento e '
    'silencioso.';
