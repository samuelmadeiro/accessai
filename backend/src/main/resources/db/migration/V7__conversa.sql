-- Slice 7: historico do copiloto conversacional (ADR 0012, ADR 0013).
--
-- Uma linha por FALA, e nao uma por par pergunta/resposta. O par so existe
-- quando os dois lados chegaram: se o guardrail recusar a resposta, gravar o par
-- exigiria uma linha com metade nula, e "resposta nula" e um estado que todo
-- leitor da tabela passaria a ter que interpretar.
--
-- Nao ha owner_id: a conversa e filha de `analise`, que e a raiz do agregado
-- (V5). Repetir a coluna criaria um segundo lugar onde o dono pode divergir do
-- dono da analise — e divergencia de ownership e falha de seguranca, nao
-- inconsistencia de dado. O isolamento sai de carregar a analise por
-- `findByIdAndOwnerId` ANTES de tocar nesta tabela.

CREATE TABLE turno_de_conversa (
    id          UUID        PRIMARY KEY,
    analise_id  UUID        NOT NULL REFERENCES analise (id) ON DELETE CASCADE,
    -- USUARIO ou ASSISTENTE. Sem um terceiro papel: nao ha mensagem de sistema
    -- persistida, porque a instrucao e remontada a cada turno pelo
    -- MontadorDePrompt e nunca guardada.
    papel       TEXT        NOT NULL,
    texto       TEXT        NOT NULL,
    -- FIXTURE ou MODELO, na fala do assistente. Na fala do usuario nao ha
    -- procedencia: ele nao foi gerado por provider nenhum.
    procedencia TEXT,
    modelo      TEXT,
    criado_em   TIMESTAMPTZ NOT NULL,

    CONSTRAINT turno_papel_valido CHECK (papel IN ('USUARIO', 'ASSISTENTE')),
    CONSTRAINT turno_texto_nao_vazio CHECK (LENGTH(TRIM(texto)) > 0),
    CONSTRAINT turno_procedencia_valida
        CHECK (procedencia IS NULL OR procedencia IN ('FIXTURE', 'MODELO')),
    -- A fala do assistente SEMPRE declara de onde veio. Sem esta checagem, uma
    -- linha de fixture e uma de modelo ficariam indistinguiveis no banco — e o
    -- CONTRIBUTING.md secao 1 proibe apresentar uma como a outra.
    CONSTRAINT turno_assistente_declara_procedencia
        CHECK (papel <> 'ASSISTENTE' OR (procedencia IS NOT NULL AND modelo IS NOT NULL))
);

-- Toda leitura da conversa e "os turnos desta analise, em ordem".
CREATE INDEX turno_de_conversa_analise_idx ON turno_de_conversa (analise_id, criado_em);

COMMENT ON TABLE turno_de_conversa IS
    'Historico do copiloto (ADR 0013). NAO guarda trecho de documento nem prompt '
    'montado: a evidencia ja vive em problema.evidencia, e uma segunda copia '
    'teria ciclo de vida proprio. Some junto com a analise, por cascade.';
