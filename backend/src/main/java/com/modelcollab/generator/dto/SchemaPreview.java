package com.modelcollab.generator.dto;

import java.util.List;

/**
 * El esquema físico que el diagrama va a producir, <b>ya ensamblado</b> (CU15).
 *
 * <h2>Por qué esto y no las funciones que lo calculan</h2>
 *
 * <p>La vista Entidad-Relación necesita los nombres reales: tabla, columna, columna de clave
 * foránea, tabla intermedia. Los calcula {@link com.modelcollab.metamodel.service
 * .IdentifierSanitizer} y hasta ahora no salían por ningún lado, así que el navegador tenía dos
 * caminos: reimplementarlos en TypeScript, o pedirlos.
 *
 * <p><b>Exponer las cuatro funciones no habría alcanzado.</b> El cliente seguiría teniendo que
 * saber cómo se ensamblan: qué columna se sintetiza de qué relación, de qué lado según
 * {@code owningSide}, cuándo aparece una tabla intermedia y cuándo no. Eso es la misma
 * divergencia mudada de las funciones a la composición — el problema movido, no resuelto. Acá
 * viaja <b>el resultado</b>: el cliente dibuja, no calcula.
 *
 * <h2>Es el mismo cálculo que el del ZIP, no uno paralelo</h2>
 *
 * <p>Esto se arma desde {@code ModeloDeGeneracion}, <b>la misma estructura que consumen las
 * plantillas FreeMarker</b>. No hay una segunda cuenta que pueda desincronizarse: si la vista
 * previa dijera {@code movimiento_stock} y el DDL creara otra cosa, sería porque una plantilla
 * ignora el modelo, y para eso está {@code VistaPreviaCoincideConElZipTest}, que compara esto
 * contra el Java realmente renderizado.
 *
 * <p>Es la lección de tres defectos ya pagados —el {@code @ManyToOne(mappedBy)}, el prefijo de
 * ruta de la app móvil y la correspondencia ranura/DTO—: una regla escrita dos veces se
 * desincroniza, y el síntoma aparece lejos de la causa.
 *
 * <h2>Esta forma es contrato público</h2>
 *
 * <p>Sale por {@code GET /api/v1/diagrams/{id}/schema-preview} y la dibuja el navegador. Cambiarla
 * rompe la vista ER de un cliente que no se recompila con este backend: agregar campos es seguro,
 * quitarlos o renombrarlos no.
 *
 * @param mutationVersion versión del diagrama sobre la que se calculó, para que el cliente sepa
 *     si lo que está dibujando quedó viejo
 * @param tables las tablas en orden de lectura: primero las de las clases, después las
 *     intermedias
 */
public record SchemaPreview(long mutationVersion, List<SchemaTable> tables) {

    public SchemaPreview {
        tables = tables == null ? List.of() : List.copyOf(tables);
    }
}
