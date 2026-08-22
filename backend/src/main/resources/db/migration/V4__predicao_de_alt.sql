-- Slice 5: predicao de qualidade de texto alternativo.
--
-- Tabela SEPARADA de `problema`, e a separacao e o ponto. O CONTRIBUTING.md
-- secao 6 diz que o score nunca e uma predicao de ML: ele e soma ponderada de
-- penalidades deterministicas, e cada ponto perdido rastreia ate uma regra com
-- evidencia. Se a predicao virasse linha em `problema`, ela entraria na conta do
-- score pela porta dos fundos.
--
-- O peso disso hoje e maior que o normal: `models/` esta vazio e TODA predicao
-- vem da heuristica. Deixa-la mexer no score seria o score virar funcao de um
-- punhado de regras disfarcadas de ML.
--
-- So imagem com alt PRESENTE entra aqui. Alt ausente e deteccao deterministica
-- e ja e a regra IMAGEM_SEM_TEXTO_ALTERNATIVO; alt vazio e declaracao de imagem
-- decorativa, que o WCAG 1.1.1 permite. O modelo classifica a QUALIDADE de um
-- alt que existe (ADR 0002: MISSING nao e classe).

CREATE TABLE predicao_de_alt (
    id             UUID        PRIMARY KEY,
    analise_id     UUID        NOT NULL REFERENCES analise (id) ON DELETE CASCADE,
    -- Posicao da imagem na ordem de extracao. Com analise_id forma a chave
    -- natural: reprocessar o mesmo evento nao pode duplicar predicao.
    indice         INT         NOT NULL,
    parte_pacote   TEXT        NOT NULL,
    nome_imagem    TEXT        NOT NULL,
    alt            TEXT        NOT NULL,
    categoria      TEXT        NOT NULL,
    -- NULO quando a resposta veio da heuristica: regra nao tem probabilidade, e
    -- um 1.0 aqui faria quem le tratar regra como modelo confiante.
    confianca      REAL,
    -- A coluna que impede o produto de apresentar regra como predicao de ML
    -- (CONTRIBUTING.md secao 1).
    usou_heuristica BOOLEAN    NOT NULL,
    -- Nulo quando nao havia modelo carregado do outro lado.
    modelo_versao  TEXT,
    criado_em      TIMESTAMPTZ NOT NULL,

    CONSTRAINT predicao_categoria_valida
        CHECK (categoria IN ('GOOD', 'WEAK', 'INSUFFICIENT')),
    CONSTRAINT predicao_confianca_no_intervalo
        CHECK (confianca IS NULL OR (confianca >= 0 AND confianca <= 1)),
    -- Heuristica nunca tem confianca nem versao de modelo. A restricao existe
    -- para que a incoerencia seja impossivel, e nao so improvavel.
    CONSTRAINT predicao_heuristica_sem_confianca
        CHECK (NOT usou_heuristica OR (confianca IS NULL AND modelo_versao IS NULL))
);

CREATE UNIQUE INDEX idx_predicao_analise_indice
    ON predicao_de_alt (analise_id, indice);

COMMENT ON TABLE predicao_de_alt IS
    'Qualidade do texto alternativo, inferida pelo ML Service. NAO entra no '
    'score: o score e deterministico (CONTRIBUTING.md secao 6). Ausencia de '
    'linha significa que o servico estava indisponivel ou que a imagem nao '
    'tinha alt para classificar.';
