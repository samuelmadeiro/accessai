/* Pagina de entrada: conta e envio do documento. */

(function () {
  const aviso = document.getElementById('aviso');

  const secaoConta = document.getElementById('secao-conta');
  const secaoEnvio = document.getElementById('secao-envio');
  const tituloEnvio = document.getElementById('titulo-envio');
  const tituloConta = document.getElementById('titulo-conta');
  const contaAtual = document.getElementById('conta-atual');

  const formConta = document.getElementById('form-conta');
  const email = document.getElementById('email');
  const senha = document.getElementById('senha');
  const erroEmail = document.getElementById('erro-email');
  const erroSenha = document.getElementById('erro-senha');
  const botaoEntrar = document.getElementById('botao-entrar');
  const botaoCriar = document.getElementById('botao-criar');

  const formEnvio = document.getElementById('form-envio');
  const arquivo = document.getElementById('arquivo');
  const erroArquivo = document.getElementById('erro-arquivo');
  const botaoEnviar = document.getElementById('botao-enviar');
  const botaoSair = document.getElementById('botao-sair');

  function mostrarEnvio(moverFoco) {
    secaoConta.hidden = true;
    contaAtual.textContent = `Conta: ${Sessao.email() || 'desconhecida'}`;
    revelar(secaoEnvio, moverFoco ? tituloEnvio : null);
  }

  function mostrarConta(moverFoco) {
    secaoEnvio.hidden = true;
    revelar(secaoConta, moverFoco ? tituloConta : null);
  }

  function validarConta() {
    let valido = true;
    limparErro(email, erroEmail);
    limparErro(senha, erroSenha);

    // Validacao propria, com `novalidate` no form: a bolha nativa do navegador
    // some sozinha, nao e lida de forma consistente por leitor de tela e nao
    // deixa rastro na pagina para quem voltar ao campo depois.
    if (!email.value.trim()) {
      marcarErro(email, erroEmail, 'Informe o e-mail.');
      valido = false;
    } else if (!email.value.includes('@')) {
      marcarErro(email, erroEmail, 'O e-mail precisa conter @.');
      valido = false;
    }
    if (senha.value.length < 8) {
      marcarErro(senha, erroSenha, 'A senha precisa de pelo menos 8 caracteres.');
      valido = false;
    }

    if (!valido) {
      // O foco vai para o primeiro campo com erro: sem isso, quem nao ve a tela
      // fica sem saber onde o problema esta.
      (email.getAttribute('aria-invalid') ? email : senha).focus();
    }
    return valido;
  }

  async function autenticar(caminho, botao, textoDeEspera) {
    if (!validarConta()) {
      avisar(aviso, 'Confira os campos destacados.', true);
      return;
    }
    avisar(aviso, '');

    try {
      await enquantoCarrega(botao, textoDeEspera, async () => {
        const resposta = await json(caminho, 'POST', {
          email: email.value.trim(),
          senha: senha.value,
        });
        Sessao.abrir(resposta.token, email.value.trim());
      });
      senha.value = '';
      avisar(aviso, 'Sessão iniciada.');
      mostrarEnvio(true);
    } catch (e) {
      avisar(aviso, e.message, true);
    }
  }

  formConta.addEventListener('submit', (evento) => {
    evento.preventDefault();
    autenticar('/auth/login', botaoEntrar, 'Entrando…');
  });

  botaoCriar.addEventListener('click', () => {
    autenticar('/auth/registrar', botaoCriar, 'Criando…');
  });

  botaoSair.addEventListener('click', () => {
    Sessao.fechar();
    avisar(aviso, 'Você saiu da conta.');
    mostrarConta(true);
  });

  formEnvio.addEventListener('submit', async (evento) => {
    evento.preventDefault();
    limparErro(arquivo, erroArquivo);

    if (!arquivo.files || arquivo.files.length === 0) {
      marcarErro(arquivo, erroArquivo, 'Escolha um arquivo .docx.');
      arquivo.focus();
      avisar(aviso, 'Nenhum arquivo escolhido.', true);
      return;
    }

    const dados = new FormData();
    dados.append('file', arquivo.files[0]);
    avisar(aviso, 'Enviando o documento. Isso pode levar alguns segundos.');

    try {
      const criada = await enquantoCarrega(botaoEnviar, 'Enviando…', () =>
        chamar('/analyses', { metodo: 'POST', corpo: dados }));
      // A analise roda de forma assincrona no backend; a pagina de resultado
      // acompanha a situacao ate CONCLUIDA.
      window.location.href = `analise.html?id=${encodeURIComponent(criada.analiseId)}`;
    } catch (e) {
      avisar(aviso, e.message, true);
      if (e.status === 401) {
        mostrarConta(true);
      }
    }
  });

  // Estado inicial sem mover foco: mexer no foco durante o carregamento tira a
  // pessoa do topo da pagina antes de ela ter lido o titulo.
  if (Sessao.token()) {
    mostrarEnvio(false);
  }
})();
