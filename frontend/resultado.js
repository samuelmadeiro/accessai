/* Pagina de resultado: score, problemas, recomendacoes e copiloto. */

(function () {
  const parametros = new URLSearchParams(window.location.search);
  const analiseId = parametros.get('id');

  const situacao = document.getElementById('situacao');
  const secaoScore = document.getElementById('secao-score');
  const scoreNumero = document.getElementById('score-numero');
  const scoreRessalva = document.getElementById('score-ressalva');
  const corpoCategorias = document.getElementById('corpo-categorias');

  const secaoProblemas = document.getElementById('secao-problemas');
  const contagemProblemas = document.getElementById('contagem-problemas');
  const listaProblemas = document.getElementById('lista-problemas');

  const secaoRecomendacoes = document.getElementById('secao-recomendacoes');
  const botaoRecomendar = document.getElementById('botao-recomendar');
  const avisoRecomendacoes = document.getElementById('aviso-recomendacoes');
  const listaRecomendacoes = document.getElementById('lista-recomendacoes');
  const procedenciaRecomendacoes = document.getElementById('procedencia-recomendacoes');

  const secaoCopiloto = document.getElementById('secao-copiloto');
  const conversa = document.getElementById('conversa');
  const formPergunta = document.getElementById('form-pergunta');
  const pergunta = document.getElementById('pergunta');
  const erroPergunta = document.getElementById('erro-pergunta');
  const botaoPerguntar = document.getElementById('botao-perguntar');

  const PRINCIPIOS = {
    PERCEPTIVEL: 'Perceptível',
    OPERAVEL: 'Operável',
    COMPREENSIVEL: 'Compreensível',
    ROBUSTO: 'Robusto',
  };

  function celula(texto, ehCabecalho) {
    const td = document.createElement(ehCabecalho ? 'th' : 'td');
    if (ehCabecalho) {
      // `scope="row"` liga cada numero ao principio da linha. Sem ele, quem
      // navega a tabela por leitor de tela ouve "62" sem saber de que.
      td.setAttribute('scope', 'row');
    }
    td.textContent = texto;
    return td;
  }

  function desenharScore(analise) {
    const score = analise.score || {};
    scoreNumero.textContent = score.global === null || score.global === undefined
      ? '—'
      : String(score.global);

    const naoAvaliados = (score.naoAvaliados || []).map((p) => PRINCIPIOS[p] || p);
    // A ressalva do score nao e nota de rodape: 100 quer dizer "sem problema no
    // que foi medido", e omitir o que NAO foi medido seria afirmar conformidade
    // que o sistema nao verificou.
    scoreRessalva.textContent = naoAvaliados.length === 0
      ? 'A nota cobre os quatro princípios da WCAG. Ela diz "sem problema no que foi medido", não "documento acessível".'
      : `A nota cobre apenas os princípios avaliados. Sem nenhuma regra implementada, ficaram de fora: ${naoAvaliados.join(', ')}.`;

    corpoCategorias.replaceChildren();
    (score.categorias || []).forEach((categoria) => {
      const linha = document.createElement('tr');
      linha.append(
        celula(categoria.titulo || PRINCIPIOS[categoria.principio] || categoria.principio, true),
        celula(`${categoria.score} de 100`),
        celula(String(categoria.problemas)),
        celula(`−${categoria.penalidade}`),
      );
      corpoCategorias.append(linha);
    });
    secaoScore.hidden = false;
  }

  function desenharProblemas(analise) {
    const problemas = analise.problemas || [];
    contagemProblemas.textContent = problemas.length === 1
      ? '1 problema encontrado.'
      : `${problemas.length} problemas encontrados.`;

    listaProblemas.replaceChildren();
    problemas.forEach((problema) => {
      const item = document.createElement('li');

      const criterio = document.createElement('p');
      criterio.className = 'criterio';
      criterio.textContent =
        `${problema.criterioWcag} — nível ${problema.nivelWcag} · severidade ${problema.severidade}`;

      const regra = document.createElement('p');
      regra.textContent = problema.regraId;

      const evidencia = document.createElement('p');
      evidencia.className = 'evidencia';
      // textContent: a evidencia vem do .docx de terceiro e e conteudo hostil.
      evidencia.textContent = problema.evidencia;

      item.append(criterio, regra, evidencia);
      listaProblemas.append(item);
    });
    secaoProblemas.hidden = false;
  }

  function desenharRecomendacoes(dados) {
    const recomendacoes = dados.recomendacoes || [];
    // A procedencia viaja ate a tela: FIXTURE diz, em uma palavra, que nenhum
    // modelo foi consultado. Sem isso a pessoa acredita em IA onde ha fixture.
    procedenciaRecomendacoes.textContent = recomendacoes.length === 0
      ? ''
      : `Origem: ${dados.procedencia === 'FIXTURE'
        ? 'fixture local — nenhum modelo de linguagem foi consultado'
        : `modelo ${dados.modelo}`}.`;

    listaRecomendacoes.replaceChildren();
    recomendacoes.forEach((recomendacao) => {
      const item = document.createElement('li');
      const titulo = document.createElement('p');
      titulo.className = 'criterio';
      titulo.textContent = `${recomendacao.regraId} · ${recomendacao.criterioWcag}`;
      const texto = document.createElement('p');
      texto.textContent = recomendacao.texto;
      item.append(titulo, texto);
      listaRecomendacoes.append(item);
    });
  }

  function desenharTurno(turno) {
    const item = document.createElement('li');
    item.className = turno.papel === 'ASSISTENTE' ? 'assistente' : 'usuario';

    const papel = document.createElement('span');
    papel.className = 'papel';
    papel.textContent = turno.papel === 'ASSISTENTE' ? 'Copiloto' : 'Você';

    const texto = document.createElement('p');
    texto.textContent = turno.texto;

    item.append(papel, texto);

    if (turno.papel === 'ASSISTENTE' && turno.procedencia) {
      const origem = document.createElement('p');
      origem.className = 'procedencia';
      origem.textContent = turno.procedencia === 'FIXTURE'
        ? 'Origem: fixture local — nenhum modelo foi consultado.'
        : `Origem: modelo ${turno.modelo}.`;
      item.append(origem);
    }
    conversa.append(item);
  }

  async function acompanhar() {
    if (!analiseId) {
      avisar(situacao, 'Nenhuma análise foi informada no endereço.', true);
      return;
    }

    let analise;
    try {
      analise = await chamar(`/analyses/${encodeURIComponent(analiseId)}`);
    } catch (e) {
      avisar(situacao, e.message, true);
      return;
    }

    if (analise.situacao === 'RECEBIDA' || analise.situacao === 'PROCESSANDO') {
      // A mensagem muda de texto a cada ciclo para NAO ser repetida: leitor de
      // tela ignora regiao viva cujo conteudo nao mudou, e a pessoa ficaria sem
      // saber se a pagina ainda esta viva.
      avisar(situacao, `Analisando o documento… (${new Date().toLocaleTimeString('pt-BR')})`);
      setTimeout(acompanhar, 1500);
      return;
    }

    if (analise.situacao === 'FALHOU') {
      avisar(situacao, 'A análise falhou. O documento pode estar corrompido ou não ser um .docx válido.', true);
      return;
    }

    avisar(situacao, `Análise concluída para ${analise.nomeArquivo}.`);
    desenharScore(analise);
    desenharProblemas(analise);
    secaoRecomendacoes.hidden = false;
    secaoCopiloto.hidden = false;

    try {
      desenharRecomendacoes(await chamar(`/analyses/${encodeURIComponent(analiseId)}/recommendations`));
    } catch (e) {
      // Recomendacao ausente nao e falha da pagina: a analise esta completa sem
      // ela, porque IA e camada opcional.
      avisar(avisoRecomendacoes, '');
    }

    try {
      const historico = await chamar(`/analyses/${encodeURIComponent(analiseId)}/chat`);
      (historico.turnos || []).forEach(desenharTurno);
    } catch (e) {
      avisar(situacao, e.message, true);
    }
  }

  botaoRecomendar.addEventListener('click', async () => {
    avisar(avisoRecomendacoes, 'Gerando recomendações…');
    try {
      const dados = await enquantoCarrega(botaoRecomendar, 'Gerando…', () =>
        json(`/analyses/${encodeURIComponent(analiseId)}/recommendations`, 'POST', {}));
      desenharRecomendacoes(dados);
      avisar(avisoRecomendacoes,
        `${(dados.recomendacoes || []).length} recomendação(ões) gerada(s).`);
    } catch (e) {
      avisar(avisoRecomendacoes, e.message, true);
    }
  });

  formPergunta.addEventListener('submit', async (evento) => {
    evento.preventDefault();
    limparErro(pergunta, erroPergunta);

    const texto = pergunta.value.trim();
    if (!texto) {
      marcarErro(pergunta, erroPergunta, 'Escreva a pergunta antes de enviar.');
      pergunta.focus();
      return;
    }

    desenharTurno({ papel: 'USUARIO', texto });
    pergunta.value = '';

    try {
      const turno = await enquantoCarrega(botaoPerguntar, 'Perguntando…', () =>
        json(`/analyses/${encodeURIComponent(analiseId)}/chat`, 'POST', { pergunta: texto }));
      desenharTurno(turno);
    } catch (e) {
      // A recusa do guardrail chega aqui como 422 e e mostrada como resposta do
      // copiloto, e nao como erro tecnico: recusar por falta de base na analise
      // e o comportamento correto, nao uma falha.
      desenharTurno({
        papel: 'ASSISTENTE',
        texto: e.codigo === 'SEM_FUNDAMENTO_NA_ANALISE'
          ? `Não posso responder isso. ${e.message}`
          : e.message,
      });
    } finally {
      // O foco volta para o campo: quem usa teclado continua a conversa sem
      // precisar tabular de volta pela lista inteira de turnos.
      pergunta.focus();
    }
  });

  acompanhar();
})();
