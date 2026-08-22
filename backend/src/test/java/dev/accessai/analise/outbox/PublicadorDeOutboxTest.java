package dev.accessai.analise.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.accessai.config.PropriedadesAccessAi;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.util.unit.DataSize;

/**
 * Testes do publicador do outbox.
 *
 * <p>O que precisa estar provado aqui e a ORDEM: publicar, esperar a
 * confirmacao do broker, e so entao marcar como publicado. Inverter isso
 * transformaria falha de rede em evento perdido em silencio.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PublicadorDeOutbox")
class PublicadorDeOutboxTest {

    private static final Instant AGORA = Instant.parse("2026-08-20T10:00:00Z");
    private static final String TOPICO = "accessai.analise.solicitada.v1";
    private static final int MAX_TENTATIVAS = 10;
    private static final long ORCAMENTO_MS = 10_000;

    @Mock
    private EventoDeOutboxRepository repositorio;

    @Mock
    private KafkaTemplate<String, byte[]> kafkaTemplate;

    private PublicadorDeOutbox publicador;

    @BeforeEach
    void montar() {
        PropriedadesAccessAi propriedades = new PropriedadesAccessAi(
                new PropriedadesAccessAi.Upload(DataSize.ofMegabytes(25)),
                new PropriedadesAccessAi.Kafka(TOPICO, 3, (short) 1,
                        new PropriedadesAccessAi.Kafka.Retry(4, 500, 2.0, 10_000)),
                new PropriedadesAccessAi.Score(
                        new PropriedadesAccessAi.Score.Pesos(25, 25, 25, 25),
                        new PropriedadesAccessAi.Score.Penalidades(25, 15, 8, 3)),
                new PropriedadesAccessAi.Outbox(500, 50, 2_000, ORCAMENTO_MS, MAX_TENTATIVAS),
                new PropriedadesAccessAi.MlService("http://localhost:8000", 500, 1500));
        publicador = new PublicadorDeOutbox(repositorio, kafkaTemplate, propriedades,
                Clock.fixed(AGORA, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("evento pendente e publicado e marcado com a hora do relogio")
    void publicaEMarca() {
        EventoDeOutbox evento = pendente();
        when(repositorio.pegarPendentes(50, MAX_TENTATIVAS)).thenReturn(List.of(evento));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(envioConfirmado());

        publicador.publicarPendentes();

        assertThat(evento.foiPublicado()).isTrue();
        assertThat(evento.getPublicadoEm()).isEqualTo(AGORA);
        assertThat(evento.getTentativas()).isZero();
    }

    @Test
    @DisplayName("o payload vai como bytes, sem reserializar, e com a chave do agregado")
    void payloadIntacto() {
        EventoDeOutbox evento = pendente();
        when(repositorio.pegarPendentes(50, MAX_TENTATIVAS)).thenReturn(List.of(evento));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(envioConfirmado());

        publicador.publicarPendentes();

        ArgumentCaptor<ProducerRecord<String, byte[]>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        ProducerRecord<String, byte[]> registro = captor.getValue();

        assertThat(registro.topic()).isEqualTo(TOPICO);
        assertThat(registro.key()).isEqualTo(evento.getChave());
        assertThat(new String(registro.value(), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo(evento.getPayload());
        assertThat(registro.headers().lastHeader("X-Correlation-ID")).isNotNull();
    }

    @Test
    @DisplayName("falha ao publicar conta tentativa e NAO marca como publicado")
    void falhaNaoMarca() {
        EventoDeOutbox evento = pendente();
        when(repositorio.pegarPendentes(50, MAX_TENTATIVAS)).thenReturn(List.of(evento));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("broker indisponivel")));

        publicador.publicarPendentes();

        assertThat(evento.foiPublicado())
                .as("marcar sem confirmacao do broker perderia o evento em silencio")
                .isFalse();
        assertThat(evento.getTentativas()).isEqualTo(1);
        assertThat(evento.getUltimoErro()).contains("broker indisponivel");
    }

    @Test
    @DisplayName("uma linha com problema nao impede o resto do lote de sair")
    void loteContinuaAposFalha() {
        EventoDeOutbox quebrado = pendente();
        EventoDeOutbox bom = pendente();
        when(repositorio.pegarPendentes(50, MAX_TENTATIVAS)).thenReturn(List.of(quebrado, bom));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("falhou")))
                .thenReturn(envioConfirmado());

        publicador.publicarPendentes();

        assertThat(quebrado.foiPublicado()).isFalse();
        assertThat(bom.foiPublicado()).isTrue();
    }

    @Test
    @DisplayName("sem pendentes, nao toca no broker")
    void semPendentes() {
        when(repositorio.pegarPendentes(50, MAX_TENTATIVAS)).thenReturn(List.of());

        publicador.publicarPendentes();

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    @Test
    @DisplayName("o teto de tentativas vai para a consulta: linha desistida sai da fila")
    void consultaLevaOTetoDeTentativas() {
        when(repositorio.pegarPendentes(50, MAX_TENTATIVAS)).thenReturn(List.of());

        publicador.publicarPendentes();

        // Sem este filtro, um evento impublicavel em definitivo e relido a cada
        // ciclo e, somados o bastante, eles ocupam o lote inteiro para sempre.
        verify(repositorio).pegarPendentes(50, MAX_TENTATIVAS);
    }

    @Test
    @DisplayName("orcamento esgotado interrompe o ciclo e deixa o resto para o proximo")
    void orcamentoInterrompeOLote() {
        PublicadorDeOutbox semOrcamento = comOrcamentoDe(0);
        EventoDeOutbox primeiro = pendente();
        EventoDeOutbox segundo = pendente();
        when(repositorio.pegarPendentes(50, MAX_TENTATIVAS))
                .thenReturn(List.of(primeiro, segundo));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(envioConfirmado());

        semOrcamento.publicarPendentes();

        // O primeiro sai mesmo com orcamento zerado: um ciclo que nao publica
        // nada nunca esvaziaria a fila. O segundo fica pendente, nao perdido.
        assertThat(primeiro.foiPublicado()).isTrue();
        assertThat(segundo.foiPublicado()).isFalse();
        assertThat(segundo.getTentativas())
                .as("adiar por orcamento nao e falha de publicacao")
                .isZero();
    }

    private PublicadorDeOutbox comOrcamentoDe(long orcamentoMs) {
        PropriedadesAccessAi propriedades = new PropriedadesAccessAi(
                new PropriedadesAccessAi.Upload(DataSize.ofMegabytes(25)),
                new PropriedadesAccessAi.Kafka(TOPICO, 3, (short) 1,
                        new PropriedadesAccessAi.Kafka.Retry(4, 500, 2.0, 10_000)),
                new PropriedadesAccessAi.Score(
                        new PropriedadesAccessAi.Score.Pesos(25, 25, 25, 25),
                        new PropriedadesAccessAi.Score.Penalidades(25, 15, 8, 3)),
                new PropriedadesAccessAi.Outbox(500, 50, 2_000, orcamentoMs, MAX_TENTATIVAS),
                new PropriedadesAccessAi.MlService("http://localhost:8000", 500, 1500));
        return new PublicadorDeOutbox(repositorio, kafkaTemplate, propriedades,
                Clock.fixed(AGORA, ZoneOffset.UTC));
    }

    private static EventoDeOutbox pendente() {
        UUID analiseId = UUID.randomUUID();
        return EventoDeOutbox.pendente(UUID.randomUUID(), analiseId, "AnaliseSolicitadaV1",
                TOPICO, analiseId.toString(), "{\"analiseId\":\"" + analiseId + "\"}",
                UUID.randomUUID(), AGORA);
    }

    private static CompletableFuture<SendResult<String, byte[]>> envioConfirmado() {
        RecordMetadata metadados = new RecordMetadata(
                new TopicPartition(TOPICO, 0), 0L, 0, 0L, 0, 0);
        return CompletableFuture.completedFuture(
                new SendResult<>(new ProducerRecord<>(TOPICO, "k", new byte[0]), metadados));
    }
}
