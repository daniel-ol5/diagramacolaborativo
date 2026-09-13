package ${paquete}.controller;

import ${paquete}.service.${clase}Service;
import ${paquete}.service.dto.${clase}RequestDTO;
import ${paquete}.service.dto.${clase}ResponseDTO;
import jakarta.validation.Valid;
import java.util.List;
<#list importesDto as importe>
import ${importe};
</#list>
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API REST de ${clase} ("${nombreOriginal}" en el diagrama).
 *
 * <p>NO editar a mano: este archivo se regenera desde el diagrama.
 */
@RestController
@RequestMapping("/api/${rutaRest}")
public class ${clase}Controller {

    private final ${clase}Service service;

    public ${clase}Controller(${clase}Service service) {
        this.service = service;
    }

    @GetMapping
    public List<${clase}ResponseDTO> listar() {
        return service.listar();
    }

    @GetMapping("/{id}")
    public ${clase}ResponseDTO buscarPorId(@PathVariable ${tipoDelIdEfectivo} id) {
        return service.buscarPorId(id);
    }

    @PostMapping
    public ResponseEntity<${clase}ResponseDTO> crear(
            @Valid @RequestBody ${clase}RequestDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.crear(dto));
    }

    @PutMapping("/{id}")
    public ${clase}ResponseDTO actualizar(
            @PathVariable ${tipoDelIdEfectivo} id, @Valid @RequestBody ${clase}RequestDTO dto) {
        return service.actualizar(id, dto);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> eliminar(@PathVariable ${tipoDelIdEfectivo} id) {
        service.eliminar(id);
        return ResponseEntity.noContent().build();
    }
}
