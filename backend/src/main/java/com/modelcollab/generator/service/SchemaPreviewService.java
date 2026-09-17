package com.modelcollab.generator.service;

import com.modelcollab.generator.dto.SchemaColumn;
import com.modelcollab.generator.dto.SchemaPreview;
import com.modelcollab.generator.dto.SchemaTable;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.service.IdentifierSanitizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Arma el esquema físico que va a producir el diagrama, para la vista Entidad-Relación (CU15).
 *
 * <h2>Reusa el modelo del generador, no lo reimplementa</h2>
 *
 * <p>Toda la traducción de nombres —tabla, columna, clave foránea, tabla intermedia— sale de
 * {@link ModeloDeGeneracion}, <b>la misma estructura que renderizan las plantillas del ZIP</b>.
 * Esta clase no decide nada: lee ese modelo y lo traduce a la forma que el navegador dibuja.
 *
 * <p>Es deliberado. Un segundo cálculo "equivalente" es la forma exacta en que ya nos mordió tres
 * veces: el {@code @ManyToOne(mappedBy)}, el prefijo de ruta del cliente móvil y la
 * correspondencia ranura/DTO. Cada una fue una regla escrita dos veces que un día dejó de
 * coincidir, y las tres se manifestaron lejos de donde estaba la causa. Acá no hay dos.
 *
 * <p><b>Lo que sí puede divergir</b>, y por eso el test sigue haciendo falta: que una plantilla
 * ignore un campo del modelo. Compartir la fuente elimina la divergencia entre dos cuentas, no la
 * que hay entre el modelo y quien lo consume.
 */
@Service
public class SchemaPreviewService {

    /** Irrelevante para el esquema: ninguna decisión de nombres físicos depende del paquete. */
    private static final String PAQUETE_CUALQUIERA = "com.empresa.app";

    private final ModeloDeGeneracion modelador;

    public SchemaPreviewService(IdentifierSanitizer sanitizer) {
        this.modelador = new ModeloDeGeneracion(sanitizer);
    }

    public SchemaPreview calcular(CanonicalModel modelo, long mutationVersion) {
        List<Map<String, Object>> entidades = modelador.entidades(modelo, PAQUETE_CUALQUIERA);

        Map<String, Map<String, Object>> porClase = new LinkedHashMap<>();
        entidades.forEach(e -> porClase.put((String) e.get("clase"), e));

        List<SchemaTable> tablas = new ArrayList<>();
        // Las intermedias van al final: no son una clase del diagrama y conviene que el cliente
        // las dibuje aparte, no intercaladas entre las entidades.
        List<SchemaTable> intermedias = new ArrayList<>();

        for (Map<String, Object> entidad : entidades) {
            tablas.add(tablaDe(entidad, porClase, intermedias));
        }
        tablas.addAll(intermedias);
        return new SchemaPreview(mutationVersion, tablas);
    }

    @SuppressWarnings("unchecked")
    private SchemaTable tablaDe(
            Map<String, Object> entidad,
            Map<String, Map<String, Object>> porClase,
            List<SchemaTable> intermedias) {

        List<SchemaColumn> columnas = new ArrayList<>();
        columnas.add(clavePrimaria(entidad, porClase));

        for (Map<String, Object> campo : (List<Map<String, Object>>) entidad.get("campos")) {
            columnas.add(new SchemaColumn(
                    sinComillas((String) campo.get("columna")),
                    entrecomillada((String) campo.get("columna")),
                    (String) campo.get("tipoCanonico"),
                    (Integer) campo.get("length"),
                    (Integer) campo.get("precision"),
                    (Integer) campo.get("scale"),
                    false,
                    false,
                    Boolean.TRUE.equals(campo.get("nullable")),
                    Boolean.TRUE.equals(campo.get("unique")),
                    null));
        }

        for (Map<String, Object> relacion : (List<Map<String, Object>>) entidad.get("relaciones")) {
            if (relacion.get("joinTable") != null) {
                intermedias.add(tablaIntermedia(entidad, relacion, porClase));
                continue;
            }
            // Sin joinColumn esta clase NO lleva la clave foránea: la lleva la otra punta y acá
            // sólo hay un mappedBy, que no es una columna. Dibujarla igual inventaría una
            // columna que la base no va a tener.
            if (relacion.get("joinColumn") == null || Boolean.TRUE.equals(relacion.get("esColeccion"))) {
                continue;
            }
            Map<String, Object> otra = porClase.get((String) relacion.get("clase"));
            columnas.add(new SchemaColumn(
                    sinComillas((String) relacion.get("joinColumn")),
                    entrecomillada((String) relacion.get("joinColumn")),
                    tipoDeLaClave(otra, porClase),
                    null,
                    null,
                    null,
                    false,
                    false,
                    !Boolean.TRUE.equals(relacion.get("obligatorio")),
                    // Un uno-a-uno lleva la clave foránea con restricción de unicidad; sin ella
                    // sería un muchos-a-uno disfrazado y la base aceptaría dos filas apuntando
                    // al mismo destino.
                    "OneToOne".equals(relacion.get("anotacion")),
                    nombreDeLaTablaDe(otra)));
        }

        return new SchemaTable(
                (String) entidad.get("clase"),
                sinComillas((String) entidad.get("tabla")),
                entrecomillada((String) entidad.get("tabla")),
                false,
                Boolean.TRUE.equals(entidad.get("esAbstracta")),
                columnas);
    }

    /**
     * La clave primaria de la tabla, que en una subclase <b>es también clave foránea</b>.
     *
     * <p>La estrategia generada es {@code JOINED}: cada clase tiene su tabla y la de la hija lleva
     * una única columna que es a la vez su clave primaria y la referencia a la tabla del padre.
     * El nombre de esa columna lo hereda de la raíz de la jerarquía —es el valor por defecto de
     * Hibernate— y la referencia apunta al padre inmediato, que es contra quien se crea la clave
     * foránea de verdad.
     *
     * <p>Sin este caso, la tabla {@code dueno} de la veterinaria saldría <b>sin ninguna
     * columna de clave</b> y la vista ER mostraría una tabla imposible.
     */
    @SuppressWarnings("unchecked")
    private SchemaColumn clavePrimaria(
            Map<String, Object> entidad, Map<String, Map<String, Object>> porClase) {

        Map<String, Object> id = (Map<String, Object>) entidad.get("id");
        if (id != null) {
            return new SchemaColumn(
                    sinComillas((String) id.get("columna")),
                    entrecomillada((String) id.get("columna")),
                    (String) id.get("tipoCanonico"),
                    null,
                    null,
                    null,
                    true,
                    id.get("generacion") != null,
                    false,
                    false,
                    null);
        }

        Map<String, Object> raiz = conClave(entidad, porClase);
        Map<String, Object> idDeLaRaiz = (Map<String, Object>) raiz.get("id");
        Map<String, Object> padre = porClase.get((String) entidad.get("extiende"));

        return new SchemaColumn(
                sinComillas((String) idDeLaRaiz.get("columna")),
                entrecomillada((String) idDeLaRaiz.get("columna")),
                (String) idDeLaRaiz.get("tipoCanonico"),
                null,
                null,
                null,
                true,
                // No la genera la base: la hereda de la fila del padre, que ya existe.
                false,
                false,
                false,
                padre == null ? null : nombreDeLaTablaDe(padre));
    }

    /** La tabla intermedia de un muchos-a-muchos, con sus dos columnas y nada más. */
    private SchemaTable tablaIntermedia(
            Map<String, Object> entidad,
            Map<String, Object> relacion,
            Map<String, Map<String, Object>> porClase) {

        Map<String, Object> otra = porClase.get((String) relacion.get("clase"));
        return new SchemaTable(
                null,
                sinComillas((String) relacion.get("joinTable")),
                entrecomillada((String) relacion.get("joinTable")),
                true,
                false,
                List.of(
                        columnaDeLaIntermedia(
                                (String) relacion.get("joinColumn"),
                                tipoDeLaClave(entidad, porClase),
                                nombreDeLaTablaDe(entidad)),
                        columnaDeLaIntermedia(
                                (String) relacion.get("inverseJoinColumn"),
                                tipoDeLaClave(otra, porClase),
                                nombreDeLaTablaDe(otra))));
    }

    /** Las dos columnas de una intermedia forman su clave primaria compuesta. */
    private static SchemaColumn columnaDeLaIntermedia(
            String columna, String tipo, String tablaApuntada) {
        return new SchemaColumn(
                sinComillas(columna), entrecomillada(columna), tipo,
                null, null, null, true, false, false, false, tablaApuntada);
    }

    // ------------------------------------------------------------------ auxiliares

    /** El tipo canónico de la clave de una clase, subiendo por la herencia si hace falta. */
    @SuppressWarnings("unchecked")
    private String tipoDeLaClave(
            Map<String, Object> entidad, Map<String, Map<String, Object>> porClase) {

        if (entidad == null) {
            return null;
        }
        Map<String, Object> raiz = conClave(entidad, porClase);
        Map<String, Object> id = raiz == null ? null : (Map<String, Object>) raiz.get("id");
        return id == null ? null : (String) id.get("tipoCanonico");
    }

    /**
     * La clase de la jerarquía que declara la clave: la propia, o el ancestro que la tenga.
     *
     * <p>La guarda de 32 corta una herencia circular. El validador la rechaza, pero acá puede
     * llegar un diagrama que nadie validó y colgarse sería peor que devolver algo incompleto.
     */
    private static Map<String, Object> conClave(
            Map<String, Object> entidad, Map<String, Map<String, Object>> porClase) {

        Map<String, Object> actual = entidad;
        int guarda = 0;
        while (actual != null && actual.get("id") == null && guarda++ < 32) {
            actual = porClase.get((String) actual.get("extiende"));
        }
        return actual;
    }

    private static String nombreDeLaTablaDe(Map<String, Object> entidad) {
        return entidad == null ? null : sinComillas((String) entidad.get("tabla"));
    }

    /**
     * Quita el escapado que el modelo lleva para poder entrar en un literal Java.
     *
     * <p>{@code nombreDeTabla("Order")} devuelve {@code "order"} <i>con</i> comillas, porque el
     * DDL las necesita; el modelo del generador además le antepone barras para meterlo en una
     * anotación. Al navegador le va el nombre pelado y un booleano aparte: mostrar
     * {@code \"order\"} en una caja de la vista ER sería ruido, y la información de que hace falta
     * entrecomillarlo se transmite mejor como un dato que como puntuación.
     */
    private static String sinComillas(String valor) {
        return valor == null ? null : valor.replace("\\", "").replace("\"", "");
    }

    private static boolean entrecomillada(String valor) {
        return valor != null && valor.contains("\"");
    }
}
