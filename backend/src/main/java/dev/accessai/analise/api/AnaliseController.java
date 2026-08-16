package dev.accessai.analise.api;

import dev.accessai.analise.app.DocumentoInvalidoException;
import dev.accessai.analise.app.ServicoDeAnalise;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Borda HTTP.
 *
 * <p>Controller nao tem regra de negocio (CLAUDE.md secao 5): aqui so acontece
 * traducao entre HTTP e o servico. Nem validacao de conteudo, que e decisao de
 * dominio e mora no validador.
 */
@RestController
@RequestMapping("/analyses")
public class AnaliseController {

    private final ServicoDeAnalise servico;

    public AnaliseController(ServicoDeAnalise servico) {
        this.servico = servico;
    }

    @PostMapping
    public ResponseEntity<AnaliseDto.RespostaDeRecebimento> receber(
            @RequestParam("file") MultipartFile arquivo) throws IOException {

        if (arquivo.isEmpty()) {
            throw new DocumentoInvalidoException("nenhum conteudo enviado");
        }

        var resultado = servico.receber(arquivo.getBytes(), nomeSeguro(arquivo));
        var corpo = AnaliseDto.RespostaDeRecebimento.de(resultado);
        return ResponseEntity.created(URI.create("/analyses/" + corpo.analiseId())).body(corpo);
    }

    @GetMapping("/{id}")
    public AnaliseDto.RespostaDeAnalise buscar(@PathVariable("id") UUID id) {
        return AnaliseDto.RespostaDeAnalise.de(servico.buscar(id));
    }

    private static String nomeSeguro(MultipartFile arquivo) {
        String original = arquivo.getOriginalFilename();
        if (original == null || original.isBlank()) {
            return "sem-nome.docx";
        }
        String semCaminho = original.replace('\\', '/');
        semCaminho = semCaminho.substring(semCaminho.lastIndexOf('/') + 1);
        return semCaminho.isBlank() ? "sem-nome.docx" : semCaminho;
    }
}
