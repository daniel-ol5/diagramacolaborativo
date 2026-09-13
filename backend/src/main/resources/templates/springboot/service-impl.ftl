package ${paquete}.service.impl;

import ${paquete}.domain.model.${clase};
import ${paquete}.repository.${clase}Repository;
<#list referencias as r>
import ${paquete}.repository.${r.clase}Repository;
</#list>
import ${paquete}.service.${clase}Service;
import ${paquete}.service.dto.${clase}RequestDTO;
import ${paquete}.service.dto.${clase}ResponseDTO;
import ${paquete}.service.dto.mapper.${clase}Mapper;
import java.util.List;
<#list importesServicio as importe>
import ${importe};
</#list>
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * CRUD de ${clase} mas los stubs de los metodos del diagrama.
 *
 * <p>NO editar a mano: este archivo se regenera desde el diagrama. Si necesitas implementar la
 * logica de un metodo del UML, hacelo en una clase propia que extienda o delegue en esta.
 */
@Service
@Transactional
public class ${clase}ServiceImpl implements ${clase}Service {

    private static final Logger log = LoggerFactory.getLogger(${clase}ServiceImpl.class);

    private final ${clase}Repository repository;
    private final ${clase}Mapper mapper;
<#list referencias as r>
    private final ${r.clase}Repository ${r.repo};
</#list>

    public ${clase}ServiceImpl(
            ${clase}Repository repository,
            ${clase}Mapper mapper<#if referencias?size gt 0>,</#if>
<#list referencias as r>
            ${r.clase}Repository ${r.repo}<#if r_has_next>,</#if>
</#list>
    ) {
        this.repository = repository;
        this.mapper = mapper;
<#list referencias as r>
        this.${r.repo} = ${r.repo};
</#list>
    }

    @Override
    @Transactional(readOnly = true)
    public List<${clase}ResponseDTO> listar() {
        return repository.findAll().stream().map(mapper::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ${clase}ResponseDTO buscarPorId(${tipoDelIdEfectivo} id) {
        return mapper.toResponse(traer(id));
    }

    @Override
    public ${clase}ResponseDTO crear(${clase}RequestDTO dto) {
        ${clase} entidad = mapper.toEntity(dto);
        asociar(dto, entidad);
        return mapper.toResponse(repository.save(entidad));
    }

    @Override
    public ${clase}ResponseDTO actualizar(${tipoDelIdEfectivo} id, ${clase}RequestDTO dto) {
        ${clase} entidad = traer(id);
        mapper.aplicar(dto, entidad);
        asociar(dto, entidad);
        return mapper.toResponse(repository.save(entidad));
    }

    @Override
    public void eliminar(${tipoDelIdEfectivo} id) {
        repository.delete(traer(id));
    }

    private ${clase} traer(${tipoDelIdEfectivo} id) {
        return repository.findById(id).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "No existe ningun ${clase} con id " + id));
    }

<#if referencias?size gt 0>
    /**
     * Resuelve los identificadores del DTO a las entidades relacionadas. Vive aca y no en el
     * mapper porque necesita consultar la base.
     */
    private void asociar(${clase}RequestDTO dto, ${clase} entidad) {
<#list referencias as r>
        if (dto.${r.campoId}() != null) {
            entidad.set${r.setter}(${r.repo}.findById(dto.${r.campoId}())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "No existe ningun ${r.clase} con id " + dto.${r.campoId}())));
        }
</#list>
    }
<#else>
    /** Esta entidad no tiene relaciones "a uno" que resolver. */
    private void asociar(${clase}RequestDTO dto, ${clase} entidad) {
        // nada que asociar
    }
</#if>
<#list metodos as m>

    /**
     * Stub del metodo "${m.nombreOriginal}" del diagrama UML.
     */
    @Override
    public ${m.retorno} ${m.nombre}(${m.firmaDeParametros}) {
        // TODO: Implementar logica de negocio definida en diagrama UML
        log.info("Ejecutando stub ${m.nombre}<#if m.parametros?size gt 0> con<#list m.parametros as p> ${p.nombre}={}<#if p_has_next>,</#if></#list></#if>"<#list m.parametros as p>, ${p.nombre}</#list>);
<#if !m.esVoid>
        return ${m.valorPorDefecto};
</#if>
    }
</#list>
}
