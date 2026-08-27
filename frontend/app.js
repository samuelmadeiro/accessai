/*
 * O que as duas paginas compartilham: token, chamada de API e as tres funcoes
 * de acessibilidade que o resto usa sem pensar.
 *
 * Sem framework e sem build. A Slice 8 e sobre a interface ser acessivel de
 * verdade; um bundler nao ajudaria nisso, e custaria um passo a mais entre o
 * codigo e o que o navegador executa — este e um projeto de portfolio, e o
 * codigo que da para ler e o codigo que da para defender.
 */

const CHAVE_TOKEN = 'accessai.token';
const CHAVE_EMAIL = 'accessai.email';

const Sessao = {
  token: () => localStorage.getItem(CHAVE_TOKEN),
  email: () => localStorage.getItem(CHAVE_EMAIL),
  abrir(token, email) {
    localStorage.setItem(CHAVE_TOKEN, token);
    localStorage.setItem(CHAVE_EMAIL, email);
  },
  fechar() {
    localStorage.removeItem(CHAVE_TOKEN);
    localStorage.removeItem(CHAVE_EMAIL);
  },
};

/** Erro que ja tem mensagem pronta para uma pessoa ler. */
class ErroDaApi extends Error {
  constructor(mensagem, status, codigo) {
    super(mensagem);
    this.status = status;
    this.codigo = codigo;
  }
}

async function chamar(caminho, opcoes = {}) {
  const cabecalhos = new Headers(opcoes.cabecalhos || {});
  const token = Sessao.token();
  if (token) {
    cabecalhos.set('Authorization', `Bearer ${token}`);
  }

  const resposta = await fetch(caminho, {
    method: opcoes.metodo || 'GET',
    headers: cabecalhos,
    body: opcoes.corpo,
  });

  if (resposta.status === 401) {
    // Token expirado ou invalido: a sessao morre aqui, e nao numa tela seguinte
    // que responderia 401 de novo sem explicar por que.
    Sessao.fechar();
    throw new ErroDaApi('Sua sessão expirou. Entre novamente.', 401, 'NAO_AUTENTICADO');
  }

  if (resposta.status === 204) {
    return null;
  }

  const texto = await resposta.text();
  const corpo = texto ? JSON.parse(texto) : null;

  if (!resposta.ok) {
    // A API ja devolve `codigo` e `mensagem` num corpo de erro uniforme; usar a
    // mensagem dela evita inventar aqui um segundo texto para o mesmo problema.
    const mensagem = (corpo && corpo.mensagem) || `Falha na requisição (${resposta.status}).`;
    throw new ErroDaApi(mensagem, resposta.status, corpo && corpo.codigo);
  }
  return corpo;
}

function json(caminho, metodo, dados) {
  return chamar(caminho, {
    metodo,
    cabecalhos: { 'Content-Type': 'application/json' },
    corpo: JSON.stringify(dados),
  });
}

/* ------------------------------------------------------------------ */
/* Acessibilidade                                                       */
/* ------------------------------------------------------------------ */

/**
 * Escreve numa regiao viva.
 *
 * O texto entra em `textContent`, nunca em `innerHTML`: o nome do arquivo, a
 * evidencia extraida do `.docx` e a resposta do copiloto vem de fora, e
 * `innerHTML` transformaria qualquer um deles em XSS. O CONTRIBUTING secao 5 ja
 * trata conteudo de terceiro como hostil no prompt — na tela vale o mesmo.
 */
function avisar(elemento, mensagem, ehErro = false) {
  elemento.textContent = mensagem;
  elemento.classList.toggle('erro', ehErro);
}

/**
 * Marca um campo como invalido e liga o erro a ele.
 *
 * O `aria-invalid` e o estado que o leitor de tela anuncia; o texto no `span`
 * apontado por `aria-describedby` e o que diz o que fazer. Um sem o outro deixa
 * metade da informacao de fora.
 */
function marcarErro(campo, spanDeErro, mensagem) {
  campo.setAttribute('aria-invalid', 'true');
  spanDeErro.textContent = mensagem;
}

function limparErro(campo, spanDeErro) {
  campo.removeAttribute('aria-invalid');
  spanDeErro.textContent = '';
}

/**
 * Mostra uma secao escondida e leva o foco ate o titulo dela.
 *
 * Sem mover o foco, quem navega por teclado continua no botao que apertou —
 * enquanto o conteudo novo aparece atras, fora do caminho de tabulacao. O
 * `tabindex="-1"` existe para que um titulo, que nao e focavel por natureza,
 * possa receber foco por programa sem entrar na ordem de tabulacao.
 */
function revelar(secao, tituloParaFocar) {
  secao.hidden = false;
  if (tituloParaFocar) {
    tituloParaFocar.setAttribute('tabindex', '-1');
    tituloParaFocar.focus();
  }
}

/** Desliga o botao enquanto a requisicao corre, e o religa no fim. */
async function enquantoCarrega(botao, textoDeEspera, acao) {
  const original = botao.textContent;
  botao.disabled = true;
  botao.textContent = textoDeEspera;
  try {
    return await acao();
  } finally {
    botao.disabled = false;
    botao.textContent = original;
  }
}
