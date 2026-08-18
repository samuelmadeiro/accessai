package dev.accessai.analise.extracao;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Percorre o pacote uma unica vez e entrega os eventos XML aos coletores.
 *
 * <p>Uma passagem so, e nao uma por regra: o {@code ZipInputStream} e sequencial
 * e reabrir o pacote por coletor multiplicaria a leitura por seis. Cada coletor
 * declara em {@link ColetorDeParte#aceita(String)} as partes que lhe
 * interessam; parte que ninguem quer nem chega a ser parseada.
 *
 * <p>Duas responsabilidades ficam aqui, e nao nos coletores, porque valem para
 * todos: a profundidade do elemento e o descarte da subarvore
 * {@code mc:Fallback}, que repete o desenho que {@code mc:Choice} ja declarou.
 */
final class VarredorDoPacote {

    /**
     * {@link XMLInputFactory} nao e thread-safe e o extrator e bean singleton.
     * Um {@code ThreadLocal} da uma fabrica por thread sem recriar a fabrica a
     * cada documento — {@code newFactory()} faz busca de servico e nao e de
     * graca. Campo compartilhado funcionaria hoje, com concorrencia 1 no
     * listener, e quebraria em silencio no dia em que ela subir.
     */
    private static final ThreadLocal<XMLInputFactory> FABRICA =
            ThreadLocal.withInitial(VarredorDoPacote::criarFabricaSegura);

    /** Documento enviado por usuario e hostil: DTD e entidade externa desligadas. */
    private static XMLInputFactory criarFabricaSegura() {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return f;
    }

    void varrer(byte[] docx, List<ColetorDeParte> coletores) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx))) {
            ZipEntry entrada;
            while ((entrada = zip.getNextEntry()) != null) {
                String parte = entrada.getName();
                List<ColetorDeParte> interessados = interessadosEm(parte, coletores);
                if (!interessados.isEmpty()) {
                    varrerParte(zip, parte, interessados);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("falha ao ler o pacote DOCX", e);
        }
    }

    private static List<ColetorDeParte> interessadosEm(String parte,
                                                       List<ColetorDeParte> coletores) {
        List<ColetorDeParte> interessados = new ArrayList<>();
        for (ColetorDeParte coletor : coletores) {
            if (coletor.aceita(parte)) {
                interessados.add(coletor);
            }
        }
        return interessados;
    }

    private void varrerParte(InputStream in, String parte, List<ColetorDeParte> coletores) {
        for (ColetorDeParte coletor : coletores) {
            coletor.aoIniciarParte(parte);
        }

        XMLStreamReader r = null;
        try {
            // O stream do zip nao pode ser fechado pelo leitor: as proximas
            // entradas ainda serao lidas dele.
            r = FABRICA.get().createXMLStreamReader(new EntradaNaoFechavel(in));
            int profundidade = 0;
            int profundidadeFallback = -1;

            while (r.hasNext()) {
                int evento = r.next();
                switch (evento) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        profundidade++;
                        if (profundidadeFallback < 0 && ehFallback(r)) {
                            profundidadeFallback = profundidade;
                            continue;
                        }
                        if (profundidadeFallback >= 0) {
                            continue;
                        }
                        for (ColetorDeParte coletor : coletores) {
                            coletor.aoAbrirElemento(r, profundidade);
                        }
                    }
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                        if (profundidadeFallback < 0) {
                            String texto = r.getText();
                            for (ColetorDeParte coletor : coletores) {
                                coletor.aoTexto(texto);
                            }
                        }
                    }
                    case XMLStreamConstants.END_ELEMENT -> {
                        if (profundidadeFallback == profundidade) {
                            profundidadeFallback = -1;
                        } else if (profundidadeFallback < 0) {
                            for (ColetorDeParte coletor : coletores) {
                                coletor.aoFecharElemento(profundidade);
                            }
                        }
                        profundidade--;
                    }
                    default -> {
                        // comentario, instrucao de processamento, espaco ignoravel
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw new ParteIlegivelException(parte, e);
        } finally {
            fechar(r, parte);
        }

        for (ColetorDeParte coletor : coletores) {
            coletor.aoTerminarParte(parte);
        }
    }

    private static boolean ehFallback(XMLStreamReader r) {
        return Ooxml.NS_MC.equals(r.getNamespaceURI()) && "Fallback".equals(r.getLocalName());
    }

    private static void fechar(XMLStreamReader r, String parte) {
        if (r == null) {
            return;
        }
        try {
            r.close();
        } catch (XMLStreamException e) {
            throw new ParteIlegivelException(parte, e);
        }
    }

    /** Impede que o leitor XML feche o ZipInputStream compartilhado. */
    private static final class EntradaNaoFechavel extends java.io.FilterInputStream {
        EntradaNaoFechavel(InputStream in) {
            super(in);
        }

        @Override
        public void close() {
            // intencionalmente vazio: quem fecha o zip e o metodo varrer
        }
    }
}
