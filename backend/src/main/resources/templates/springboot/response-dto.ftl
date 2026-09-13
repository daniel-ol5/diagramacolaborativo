package ${paquete}.service.dto;

<#list importesDto as importe>
import ${importe};
</#list>

/**
 * Vista de salida de un ${clase}. Record inmutable.
 *
 * <p>Devuelve los identificadores de las entidades relacionadas y no las entidades enteras: si
 * expusiera los objetos, cada respuesta arrastraria el grafo completo del diagrama y ademas
 * obligaria a Hibernate a cargarlo.
 *
 * <p>NO editar a mano: este archivo se regenera desde el diagrama.
 */
public record ${clase}ResponseDTO(
        ${tipoDelIdEfectivo} id<#if campos?size gt 0 || referencias?size gt 0>,</#if>
<#list campos as c>
        ${t[c.tipo]} ${c.campo}<#if c_has_next || referencias?size gt 0>,</#if>
</#list>
<#list referencias as r>
        ${r.tipoDelId} ${r.campoId}<#if r_has_next>,</#if>
</#list>
) {}
