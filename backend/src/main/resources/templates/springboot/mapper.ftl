package ${paquete}.service.dto.mapper;

import ${paquete}.domain.model.${clase};
import ${paquete}.service.dto.${clase}RequestDTO;
import ${paquete}.service.dto.${clase}ResponseDTO;
import org.springframework.stereotype.Component;

/**
 * Traduccion entre ${clase} y sus DTO.
 *
 * <p>Deliberadamente tonto: copia campos escalares y nada mas. <b>No resuelve referencias</b> —
 * convertir un {@code clienteId} en un {@code Cliente} exige consultar la base, y un mapper que
 * consulta la base deja de ser una funcion y se vuelve imposible de probar sin infraestructura.
 * Esa resolucion la hace el servicio, que ya tiene los repositorios.
 *
 * <p>NO editar a mano: este archivo se regenera desde el diagrama.
 */
@Component
public class ${clase}Mapper {

    /** Entidad nueva a partir de los datos de entrada. */
    public ${clase} toEntity(${clase}RequestDTO dto) {
        ${clase} entidad = new ${clase}();
        aplicar(dto, entidad);
        return entidad;
    }

    /** Copia los campos escalares del DTO sobre una entidad existente. */
    public void aplicar(${clase}RequestDTO dto, ${clase} entidad) {
<#list campos as c>
        entidad.set${c.getter}(dto.${c.campo}());
</#list>
<#if campos?size == 0>
        // La clase del diagrama no tiene atributos propios.
</#if>
    }

    public ${clase}ResponseDTO toResponse(${clase} entidad) {
        return new ${clase}ResponseDTO(
                entidad.get${idGetter}()<#if campos?size gt 0 || referencias?size gt 0>,</#if>
<#list campos as c>
                entidad.get${c.getter}()<#if c_has_next || referencias?size gt 0>,</#if>
</#list>
<#list referencias as r>
                entidad.get${r.setter}() == null ? null : entidad.get${r.setter}().get${idGetterDe[r.clase]}()<#if r_has_next>,</#if>
</#list>
        );
    }
}
