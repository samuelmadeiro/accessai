-- Slice 1: upload -> evento -> uma regra -> persistencia -> GET.
--
-- Nao ha coluna de score aqui. Score por categoria e a entrega da Slice 2;
-- criar coluna agora seria schema especulativo. Nao ha owner_id: autenticacao
-- (D4) nao esta nesta slice, e a migration que a introduzir e quem adiciona a
-- coluna e o indice de isolamento.

CREATE TABLE analise (
    id                  UUID         PRIMARY KEY,
    correlation_id      UUID         NOT NULL,
    nome_arquivo        TEXT         NOT NULL,
    tipo_mime_detectado TEXT         NOT NULL,
    tamanho_bytes       BIGINT       NOT NULL CHECK (tamanho_bytes > 0),
    -- VARCHAR e nao CHAR: CHAR no Postgres preenche com espaco a direita, o que
    -- transformaria comparacao de digest numa fonte silenciosa de bug.
    sha256              VARCHAR(64)  NOT NULL CHECK (LENGTH(sha256) = 64),
    situacao            TEXT         NOT NULL,
    criada_em           TIMESTAMPTZ  NOT NULL,
    atualizada_em       TIMESTAMPTZ  NOT NULL,

    CONSTRAINT analise_situacao_valida
        CHECK (situacao IN ('RECEBIDA', 'PROCESSANDO', 'CONCLUIDA', 'FALHOU'))
);

COMMENT ON COLUMN analise.tipo_mime_detectado IS
    'Tipo detectado do conteudo, nunca o declarado pelo cliente nem a extensao.';

-- O binario fica em tabela propria para nao pesar as consultas de listagem,
-- que nunca precisam do conteudo.
CREATE TABLE documento_binario (
    analise_id UUID   PRIMARY KEY REFERENCES analise (id) ON DELETE CASCADE,
    conteudo   BYTEA  NOT NULL
);

CREATE TABLE problema (
    id             UUID        PRIMARY KEY,
    analise_id     UUID        NOT NULL REFERENCES analise (id) ON DELETE CASCADE,
    regra_id       TEXT        NOT NULL,
    criterio_wcag  TEXT        NOT NULL,
    nivel_wcag     TEXT        NOT NULL,
    severidade     TEXT        NOT NULL,
    parte_pacote   TEXT        NOT NULL,
    evidencia      TEXT        NOT NULL,
    criado_em      TIMESTAMPTZ NOT NULL,

    CONSTRAINT problema_nivel_valido CHECK (nivel_wcag IN ('A', 'AA', 'AAA')),
    CONSTRAINT problema_severidade_valida
        CHECK (severidade IN ('BAIXA', 'MEDIA', 'ALTA', 'CRITICA'))
);

CREATE INDEX idx_problema_analise ON problema (analise_id);

COMMENT ON COLUMN problema.criterio_wcag IS
    'Identificador do criterio na tabela versionada docs/wcag/criteria.json, '
    'com aplicabilidade a documento nao-web resolvida via WCAG2ICT. '
    'Nunca inventado em codigo.';

-- Idempotencia do consumidor (CLAUDE.md secao 5). A chave e o eventId do
-- evento Kafka. Reprocessar a mesma mensagem colide na primary key, e o
-- consumidor trata isso como "ja processado" em vez de duplicar problema.
-- Retry, DLT e o teste que mata o consumidor no meio sao da Slice 3; aqui
-- fica so a garantia minima que a invariante exige.
CREATE TABLE evento_processado (
    evento_id     UUID        PRIMARY KEY,
    consumidor    TEXT        NOT NULL,
    processado_em TIMESTAMPTZ NOT NULL
);
