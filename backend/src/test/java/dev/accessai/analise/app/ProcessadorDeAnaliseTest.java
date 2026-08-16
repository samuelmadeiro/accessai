package dev.accessai.analise.app;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import dev.accessai.analise.evento.AnaliseSolicitadaV1;
import dev.accessai.analise.extracao.ExtratorDeImagens;
import java.io.UncheckedIOException;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Testes da politica de falha do consumidor.
 *
 * <p>Antes desta politica, qualquer excecao derrubava a transacao e a analise
 * voltava a RECEBIDA sem registro nenhum. O que precisa estar provado aqui e a
 * separacao: falha permanente vira FALHOU e para; falha transitoria sobe para o
 * Kafka reentregar.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProcessadorDeAnalise")
class ProcessadorDeAnaliseTest {

    private static final AnaliseSolicitadaV1 EVENTO = AnaliseSolicitadaV1.de(
            UUID.randomUUID(), UUID.randomUUID(), "edital.docx", "a".repeat(64),
            Instant.parse("2026-08-19T12:00:00Z"));

    @Mock
    private ExecucaoDaAnalise execucao;

    @Mock
    private RegistroDeFalha registroDeFalha;

    @InjectMocks
    private ProcessadorDeAnalise processador;

    @Test
    @DisplayName("pacote ilegivel e falha permanente: marca FALHOU e nao relanca")
    void parteIlegivelMarcaFalhou() {
        doThrow(new ExtratorDeImagens.ParteIlegivelException("word/document.xml",
                new RuntimeException("xml torto")))
                .when(execucao).executar(EVENTO);

        processador.processar(EVENTO);

        verify(registroDeFalha).registrar(EVENTO);
    }

    @Test
    @DisplayName("binario ausente e falha permanente")
    void binarioAusenteMarcaFalhou() {
        doThrow(new BinarioAusenteException(EVENTO.analiseId()))
                .when(execucao).executar(EVENTO);

        processador.processar(EVENTO);

        verify(registroDeFalha).registrar(EVENTO);
    }

    @Test
    @DisplayName("zip ilegivel (UncheckedIOException) e falha permanente")
    void zipIlegivelMarcaFalhou() {
        doThrow(new UncheckedIOException("falha ao ler o pacote DOCX", new IOException("eof")))
                .when(execucao).executar(EVENTO);

        processador.processar(EVENTO);

        verify(registroDeFalha).registrar(EVENTO);
    }

    @Test
    @DisplayName("falha nao classificada e transitoria: relanca e nao marca FALHOU")
    void falhaTransitoriaRelanca() {
        // Banco fora do ar nao e defeito do documento do usuario. Marcar FALHOU
        // aqui seria culpar o documento por um problema de infraestrutura.
        doThrow(new IllegalStateException("conexao recusada"))
                .when(execucao).executar(EVENTO);

        assertThatThrownBy(() -> processador.processar(EVENTO))
                .isInstanceOf(IllegalStateException.class);

        verify(registroDeFalha, never()).registrar(any());
    }

    @Test
    @DisplayName("caminho feliz nao toca no registro de falha")
    void caminhoFeliz() {
        processador.processar(EVENTO);

        verify(execucao).executar(EVENTO);
        verify(registroDeFalha, never()).registrar(any());
    }
}
