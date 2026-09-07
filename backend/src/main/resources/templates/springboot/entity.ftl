package ${paquete}.domain.model;

<#list importes as importe>
import ${importe};
</#list>

/**
 * Entidad JPA generada a partir de la clase UML "${nombreOriginal}".
 *
 * <p>NO editar a mano: este archivo se regenera desde el diagrama.
 */
@${t.Entity}
@${t.Table}(name = "${tabla}")
<#if esPadre>
@${t.Inheritance}(strategy = ${t.InheritanceType}.JOINED)
</#if>
public <#if esAbstracta>abstract </#if>class ${clase}<#if extiende??> extends ${extiende}</#if> {

<#if id??>
    @${t.Id}
<#if id.generacion??>
    @${t.GeneratedValue}(strategy = ${t.GenerationType}.${id.generacion})
</#if>
<#if id.inyectado>
    // Clave primaria inyectada por el generador: la clase UML no declaraba ninguna (11.2.2).
</#if>
    @${t.Column}(name = "${id.columna}", nullable = false, updatable = false)
    private ${t[id.tipo]} ${id.campo};

</#if>
<#list campos as c>
<#if c.campo != c.nombreOriginal>
    /** Atributo "${c.nombreOriginal}" del diagrama; renombrado porque es reservado en Java. */
</#if>
    @${t.Column}(name = "${c.columna}"<#if !c.nullable>, nullable = false</#if><#if c.unique>, unique = true</#if><#if c.length??>, length = ${c.length}</#if><#if c.precision??>, precision = ${c.precision}, scale = ${c.scale}</#if>)
    private ${t[c.tipo]} ${c.campo};

</#list>
<#list relaciones as r>
<#if r.esColeccion>
    @${t[r.anotacion]}<#if r.atributos??>(${r.atributos})</#if>
<#if r.joinTable??>
    @${t.JoinTable}(
            name = "${r.joinTable}",
            joinColumns = @${t.JoinColumn}(name = "${r.joinColumn}"),
            inverseJoinColumns = @${t.JoinColumn}(name = "${r.inverseJoinColumn}"))
</#if>
    private ${t.List}<${r.clase}> ${r.campo} = new ${t.ArrayList}<>();

<#else>
    @${t[r.anotacion]}<#if r.atributos??>(${r.atributos})</#if>
<#if r.joinColumn??>
    @${t.JoinColumn}(name = "${r.joinColumn}"<#if r.obligatorio>, nullable = false</#if>)
</#if>
    private ${r.clase} ${r.campo};

</#if>
</#list>
    protected ${clase}() {
        // Constructor sin argumentos requerido por JPA.
    }

<#if id??>
    public ${t[id.tipo]} get${id.campo?cap_first}() {
        return ${id.campo};
    }

    public void set${id.campo?cap_first}(${t[id.tipo]} ${id.campo}) {
        this.${id.campo} = ${id.campo};
    }

</#if>
<#list campos as c>
    public ${t[c.tipo]} get${c.getter}() {
        return ${c.campo};
    }

    public void set${c.getter}(${t[c.tipo]} ${c.campo}) {
        this.${c.campo} = ${c.campo};
    }

</#list>
<#list relaciones as r>
<#if r.esColeccion>
    public ${t.List}<${r.clase}> get${r.campo?cap_first}() {
        return ${r.campo};
    }

    public void set${r.campo?cap_first}(${t.List}<${r.clase}> ${r.campo}) {
        this.${r.campo} = ${r.campo};
    }

<#else>
    public ${r.clase} get${r.campo?cap_first}() {
        return ${r.campo};
    }

    public void set${r.campo?cap_first}(${r.clase} ${r.campo}) {
        this.${r.campo} = ${r.campo};
    }

</#if>
</#list>
}
