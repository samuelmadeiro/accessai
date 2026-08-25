-- Slice 6: recomendacoes fundamentadas na analise.
--
-- Persistidas, e nao recalculadas na leitura. O score PODE ser recalculado
-- porque e funcao pura dos problemas mais os pesos; recomendacao de LLM nao e:
-- a mesma entrada devolve texto diferente a cada chamada. Recalcular faria a
-- mesma analise responder coisas diferentes a cada consulta — o defeito que o
-- ADR 0011 ja evitou para a predicao de alt — e, com provider pago, cobraria de
-- novo por cada releitura.

CREATE TABLE recomendacao (
    id           UUID        PRIMARY KEY,
    analise_id   UUID        NOT NULL REFERENCES analise (id) ON DELETE CASCADE,
    -- A regra que ORIGINOU a recomendacao. E o que o guardrail confere: texto
    -- que cita regra ausente da analise nao chega aqui.
    regra_id     TEXT        NOT NULL,
    criterio_wcag TEXT       NOT NULL,
    texto        TEXT        NOT NULL,
    -- FIXTURE ou MODELO. Sem esta coluna, texto de fixture e saida de modelo
    -- ficam indistinguiveis no banco — e o §1 proibe apresentar um como o outro.
    procedencia  TEXT        NOT NULL,
    modelo       TEXT        NOT NULL,
    criada_em    TIMESTAMPTZ NOT NULL,

    CONSTRAINT recomendacao_procedencia_valida
        CHECK (procedencia IN ('FIXTURE', 'MODELO')),
    CONSTRAINT recomendacao_texto_nao_vazio CHECK (LENGTH(TRIM(texto)) > 0)
);

CREATE INDEX recomendacao_analise_idx ON recomendacao (analise_id);

COMMENT ON COLUMN recomendacao.procedencia IS
    'FIXTURE = nenhum modelo foi consultado. A API repete este valor ao cliente.';
