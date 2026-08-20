-- Slice 3: outbox transacional, retry com backoff, DLT e correlationId.
--
-- O problema que esta migration resolve: na Slice 1 o evento era publicado
-- DEPOIS do commit. Se o processo morresse entre o commit e a publicacao, a
-- analise ficava em RECEBIDA para sempre — gravada, e invisivel para o
-- consumidor. Nao havia como descobrir isso sem varrer o banco a mao.
--
-- Com o outbox, gravar a analise e gravar a intencao de publicar viram a MESMA
-- transacao. Ou as duas acontecem, ou nenhuma. A publicacao passa a ser um
-- trabalho separado, que pode falhar e ser repetido sem perder o evento.

CREATE TABLE outbox_evento (
    -- O id do evento e a chave de deduplicacao do consumidor (evento_processado).
    -- Gerar aqui, e nao no publicador, garante que uma republicacao carrega o
    -- MESMO id: reentrega vira duplicata detectavel, nao evento novo.
    id             UUID        PRIMARY KEY,
    agregado_id    UUID        NOT NULL,
    tipo           TEXT        NOT NULL,
    topico         TEXT        NOT NULL,
    -- Chave de particionamento do Kafka. Mesma chave, mesma particao, ordem
    -- preservada para uma mesma analise.
    chave          TEXT        NOT NULL,
    -- TEXT e nao JSONB: o payload nunca e consultado por campo, so lido inteiro
    -- e entregue ao broker. JSONB cobraria parsing e indice para nada
    -- (CONTRIBUTING.md secao 5 manda justificar cada uso de JSONB).
    payload        TEXT        NOT NULL,
    correlation_id UUID        NOT NULL,
    criado_em      TIMESTAMPTZ NOT NULL,
    -- NULL enquanto pendente. Preenchido apos o broker confirmar (acks=all).
    publicado_em   TIMESTAMPTZ,
    tentativas     INT         NOT NULL DEFAULT 0,
    ultimo_erro    TEXT,

    CONSTRAINT outbox_tentativas_nao_negativas CHECK (tentativas >= 0)
);

-- Indice PARCIAL: o publicador so pergunta por pendentes, e a tabela tende a
-- ser quase toda de publicados. Indexar a coluna inteira faria o indice crescer
-- para sempre carregando linhas que nenhuma consulta procura.
CREATE INDEX idx_outbox_pendentes
    ON outbox_evento (criado_em)
    WHERE publicado_em IS NULL;

COMMENT ON TABLE outbox_evento IS
    'Eventos gravados na mesma transacao do agregado e publicados depois. '
    'Entrega e at-least-once: se o processo morrer entre publicar e marcar, '
    'o evento sai duas vezes e a deduplicacao do consumidor resolve.';

COMMENT ON COLUMN outbox_evento.ultimo_erro IS
    'Ultima falha de publicacao. Existe para diagnostico: evento parado com '
    'tentativas altas e sintoma de broker inacessivel, nao de bug de dominio.';

-- Registro do que a DLT recebeu. Sem isto, "a mensagem foi para a DLT" e uma
-- linha de log que ninguem le; com isto, e uma consulta.
CREATE TABLE evento_em_dlt (
    id             UUID        PRIMARY KEY,
    evento_id      UUID        NOT NULL,
    analise_id     UUID,
    topico_origem  TEXT        NOT NULL,
    excecao        TEXT,
    mensagem_erro  TEXT,
    recebido_em    TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_dlt_analise ON evento_em_dlt (analise_id);

COMMENT ON TABLE evento_em_dlt IS
    'Mensagens que esgotaram o retry e cairam na Dead Letter Topic. A analise '
    'correspondente e marcada como FALHOU pelo consumidor da DLT.';
