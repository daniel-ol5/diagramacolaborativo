package ${paquete}.service.dto;

<#list importesDto as importe>
import ${importe};
</#list>

/**
 * Datos de entrada para crear o actualizar un ${clase} ("${nombreOriginal}" en el diagrama).
 *
 * <p>NO editar a mano: este archivo se regenera desde el diagrama.
 */
public record ${clase}RequestDTO(
<#list campos as c>
<#if !c.nullable && c.tipo == "String">
        @jakarta.validation.constraints.NotBlank
<#elseif !c.nullable>
        @jakarta.validation.constraints.NotNull
</#if>
<#if c.length??>
        @jakarta.validation.constraints.Size(max = ${c.length})
</#if>
        ${t[c.tipo]} ${c.campo}<#if c_has_next || referencias?size gt 0>,</#if>
</#list>
<#list referencias as r>
<#if r.obligatorio>
        @jakarta.validation.constraints.NotNull
</#if>
        ${r.tipoDelId} ${r.campoId}<#if r_has_next>,</#if>
</#list>
) {}
