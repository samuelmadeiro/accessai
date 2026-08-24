-- Um evento, um registro de DLT.

--
-- RegistroDeFalha ja verificava com existsByEventoId antes de gravar, mas isso
-- e check-then-act: a invariante vivia so no codigo da aplicacao. Hoje o
-- consumidor da DLT roda com concorrencia 1 e a verificacao basta; no dia em
-- que alguem subir a concorrencia ou uma segunda instancia consumir a mesma
-- particao, duas leituras simultaneas passariam pelo exists e gravariam duas
-- vezes a mesma falha, poluindo o diagnostico que a tabela existe para dar.
--
-- O indice unico move a garantia para onde ela nao depende de ninguem lembrar.
CREATE UNIQUE INDEX idx_dlt_evento ON evento_em_dlt (evento_id);
