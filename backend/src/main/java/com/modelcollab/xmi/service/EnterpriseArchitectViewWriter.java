package com.modelcollab.xmi.service;

import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.ClassEntity;
import com.modelcollab.metamodel.model.Multiplicity;
import com.modelcollab.metamodel.model.Relationship;
import com.modelcollab.metamodel.model.RelationshipType;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Escribe la <b>lámina</b> del diagrama en el dialecto de Enterprise Architect.
 *
 * <h2>Por qué existe esta clase</h2>
 *
 * <p>Daniel abrió nuestro {@code inventario.xmi} en Enterprise Architect y el resultado fue
 * mitad y mitad: EA <b>sí</b> importó el modelo —ve los paquetes y sus clases en el explorador—
 * pero <b>no</b> tiene ningún diagrama que abrir. En UML son dos cosas distintas y nosotros
 * exportábamos sólo la primera:
 *
 * <ul>
 *   <li>el <b>modelo</b>: qué clases existen, qué atributos tienen, cómo se relacionan;</li>
 *   <li>la <b>vista</b>: qué se dibuja en una lámina, en qué coordenadas y con qué trazado.</li>
 * </ul>
 *
 * <p>Lo incómodo es que la información nunca faltó. Nuestra {@code xmi:Extension} lleva las
 * posiciones, los tamaños y los waypoints —por eso el viaje de ida y vuelta entre dos
 * ModelCollab no pierde nada— pero está en <b>nuestro</b> namespace, y EA no tiene por qué
 * saber leerlo. El dato viajaba; el consumidor no lo entendía.
 *
 * <p>Así que esta clase emite los mismos datos <b>otra vez</b>, en la forma que EA espera, en una
 * segunda {@code xmi:Extension} con {@code extender="Enterprise Architect"}. No reemplaza nada:
 * la nuestra sigue siendo la que garantiza el viaje sin pérdida, y XMI permite tantas extensiones
 * como herramientas quieran anotar el documento. Es para lo que existe el elemento.
 *
 * <h2>Advertencia honesta sobre este archivo</h2>
 *
 * <p><b>El dialecto de EA no está publicado como esquema verificable.</b> Lo de acá está
 * reconstruido a partir de la forma de los XMI que EA exporta, y la única manera de saber si
 * sirve es que una persona abra el archivo con la herramienta real. Por eso está aislado en una
 * clase propia y no mezclado en {@link XmiExporterService}: si hay que corregir el dialecto —o
 * tirarlo entero y documentar la limitación— se toca sólo esto y nada del camino que ya funciona.
 *
 * <p>Lo que <b>no</b> hace: adivinar. No se inventan cadenas de estilo ({@code style1},
 * {@code style2}) cuyos nombres de clave no podemos comprobar, porque una clave mal escrita puede
 * ocultar atributos en la lámina y sería peor que no poner nada. Lo que se emite es lo que se
 * puede justificar: identidad, pertenencia, geometría y extremos de los conectores.
 */
@Component
public class EnterpriseArchitectViewWriter {

    /** Valor del atributo {@code extender} que le dice a EA que esta sección es suya. */
    public static final String EXTENDER = "Enterprise Architect";

    /** Versión del esquema de extensión de EA que estamos imitando. */
    private static final String EXTENDER_ID = "6.5";

    /** Id de la lámina que se crea. Uno solo: un diagrama nuestro es una lámina suya. */
    static final String ID_DE_LA_LAMINA = "modelcollab-diagram";

    /**
     * Fecha fija, y a propósito.
     *
     * <p>El modelo canónico de §7 no guarda cuándo se creó un diagrama, así que poner
     * {@code now()} sería <b>inventar un dato</b> y además haría que el mismo diagrama exportado
     * dos veces diera archivos distintos, con lo que dejaría de poder compararse un export contra
     * otro. Una constante evidente dice lo que es: un relleno que el formato exige.
     */
    private static final String FECHA_PLACEHOLDER = "2026-01-01 00:00:00";

    /**
     * Construye la {@code xmi:Extension} de EA completa.
     *
     * @param idDelModelo id del {@code uml:Model} raíz, que hace de paquete contenedor para las
     *     clases sueltas y de dueño de la lámina
     */
    public Element escribir(
            Document doc, CanonicalModel modelo, String nombreDelDiagrama, String idDelModelo) {

        Element ext = doc.createElementNS(XmiExporterService.NS_XMI, "xmi:Extension");
        ext.setAttribute("extender", EXTENDER);
        ext.setAttribute("extenderID", EXTENDER_ID);

        Map<String, String> paquetePorClase = paquetePorClase(modelo, idDelModelo);
        Map<String, String> nombrePorClase = new LinkedHashMap<>();
        modelo.classes().forEach(c -> nombrePorClase.put(c.id(), c.name()));

        ext.appendChild(elementos(doc, modelo, paquetePorClase));
        ext.appendChild(conectores(doc, modelo, nombrePorClase));
        ext.appendChild(laminas(doc, modelo, nombreDelDiagrama, idDelModelo));
        return ext;
    }

    // ------------------------------------------------------------- elementos

    /**
     * Un {@code <element>} por clase. EA toma de acá la <b>pertenencia</b>: qué paquete es dueño
     * de cada clase. La lámina después referencia estos ids por {@code subject}, así que sin esta
     * sección los {@code subject} de la lámina apuntarían a elementos que EA no tiene fichados.
     */
    private Element elementos(
            Document doc, CanonicalModel modelo, Map<String, String> paquetePorClase) {

        Element elementos = doc.createElement("elements");
        int localId = 1;
        for (ClassEntity clase : modelo.classes()) {
            Element e = doc.createElement("element");
            e.setAttributeNS(XmiExporterService.NS_XMI, "xmi:idref", clase.id());
            e.setAttributeNS(XmiExporterService.NS_XMI, "xmi:type", "uml:Class");
            e.setAttribute("name", clase.name());
            e.setAttribute("scope", "public");

            Element model = doc.createElement("model");
            model.setAttribute("package", paquetePorClase.get(clase.id()));
            model.setAttribute("ea_localid", String.valueOf(localId++));
            model.setAttribute("ea_eleType", "element");
            e.appendChild(model);

            Element propiedades = doc.createElement("properties");
            propiedades.setAttribute("isSpecification", "false");
            propiedades.setAttribute("sType", "Class");
            propiedades.setAttribute("nType", "0");
            propiedades.setAttribute("scope", "public");
            propiedades.setAttribute("isRoot", "false");
            propiedades.setAttribute("isLeaf", "false");
            propiedades.setAttribute("isAbstract", String.valueOf(clase.isAbstract()));
            e.appendChild(propiedades);

            Element proyecto = doc.createElement("project");
            proyecto.setAttribute("author", "ModelCollab");
            proyecto.setAttribute("version", "1.0");
            proyecto.setAttribute("created", FECHA_PLACEHOLDER);
            proyecto.setAttribute("modified", FECHA_PLACEHOLDER);
            e.appendChild(proyecto);

            elementos.appendChild(e);
        }
        return elementos;
    }

    // ------------------------------------------------------------ conectores

    /**
     * Un {@code <connector>} por relación, con sus dos extremos.
     *
     * <p>EA dibuja el conector en la lámina sólo si lo tiene fichado acá: la lámina aporta el
     * trazado, esta sección aporta qué une con qué. Las dos hacen falta.
     */
    private Element conectores(
            Document doc, CanonicalModel modelo, Map<String, String> nombrePorClase) {

        Element conectores = doc.createElement("connectors");
        for (Relationship r : modelo.relationships()) {
            Element c = doc.createElement("connector");
            c.setAttributeNS(XmiExporterService.NS_XMI, "xmi:idref", r.id());

            c.appendChild(extremo(doc, "source", r.sourceClassId(),
                    nombrePorClase.get(r.sourceClassId()), r.sourceMultiplicity(),
                    r.sourceRole(), "none"));
            // La agregación va en el extremo del TODO, igual que en el UML estándar que ya
            // emitimos: si acá se pusiera del otro lado, el mismo archivo diría dos cosas
            // distintas sobre el mismo rombo.
            c.appendChild(extremo(doc, "target", r.targetClassId(),
                    nombrePorClase.get(r.targetClassId()), r.targetMultiplicity(),
                    r.targetRole(), agregacion(r.type())));

            Element propiedades = doc.createElement("properties");
            propiedades.setAttribute("ea_type", tipoEa(r.type()));
            propiedades.setAttribute("direction",
                    r.isNavigable() ? "Source -> Destination" : "Unspecified");
            c.appendChild(propiedades);

            conectores.appendChild(c);
        }
        return conectores;
    }

    private Element extremo(
            Document doc, String etiqueta, String classId, String nombreDeLaClase,
            Multiplicity multiplicidad, String rol, String agregacion) {

        Element e = doc.createElement(etiqueta);
        e.setAttributeNS(XmiExporterService.NS_XMI, "xmi:idref", classId);

        Element model = doc.createElement("model");
        model.setAttribute("type", "Class");
        if (nombreDeLaClase != null) {
            model.setAttribute("name", nombreDeLaClase);
        }
        e.appendChild(model);

        Element role = doc.createElement("role");
        if (rol != null) {
            role.setAttribute("name", rol);
        }
        role.setAttribute("visibility", "Public");
        e.appendChild(role);

        Element type = doc.createElement("type");
        if (multiplicidad != null) {
            type.setAttribute("multiplicity", multiplicidad.getValue());
        }
        type.setAttribute("aggregation", agregacion);
        e.appendChild(type);

        return e;
    }

    /** Los nombres que EA le da a cada clase de conector. */
    private static String tipoEa(RelationshipType tipo) {
        return switch (tipo) {
            case INHERITANCE -> "Generalization";
            case DEPENDENCY -> "Dependency";
            // EA no distingue agregación de composición por el tipo de conector sino por el
            // atributo aggregation del extremo: las dos son "Aggregation".
            case AGGREGATION, COMPOSITION -> "Aggregation";
            default -> "Association";
        };
    }

    private static String agregacion(RelationshipType tipo) {
        return switch (tipo) {
            case AGGREGATION -> "shared";
            case COMPOSITION -> "composite";
            default -> "none";
        };
    }

    // --------------------------------------------------------------- láminas

    /**
     * La lámina propiamente dicha: <b>esto es lo que le faltaba al archivo</b>.
     *
     * <p>Cada {@code <element>} de acá dentro no es una clase: es la <i>aparición</i> de una clase
     * en el dibujo, con su rectángulo. La distinción importa porque en EA la misma clase puede
     * aparecer en varias láminas, y cada aparición tiene su propia geometría.
     */
    private Element laminas(
            Document doc, CanonicalModel modelo, String nombreDelDiagrama, String idDelModelo) {

        Element diagramas = doc.createElement("diagrams");
        Element diagrama = doc.createElement("diagram");
        diagrama.setAttributeNS(XmiExporterService.NS_XMI, "xmi:id", ID_DE_LA_LAMINA);

        // De quien cuelga la lamina. En EA una lamina vive dentro de un PAQUETE, y hasta la
        // vuelta 1 esto apuntaba al uml:Model raiz, que no es un paquete. Si EA resuelve el
        // dueno y no encuentra un paquete, puede descartar la lamina entera — que es
        // exactamente el sintoma que reporto Daniel: importa las clases y no abre ningun
        // diagrama.
        //
        // Cuando el modelo tiene paquetes se usa el primero; cuando no tiene ninguno no queda
        // otra que el modelo raiz, y ahi este candidato no aplica.
        String duenio = modelo.packages().isEmpty()
                ? idDelModelo
                : modelo.packages().get(0).id();

        Element model = doc.createElement("model");
        model.setAttribute("package", duenio);
        model.setAttribute("localID", "1");
        model.setAttribute("owner", duenio);
        diagrama.appendChild(model);

        Element propiedades = doc.createElement("properties");
        propiedades.setAttribute("name",
                nombreDelDiagrama == null || nombreDelDiagrama.isBlank()
                        ? "Diagrama de Clases" : nombreDelDiagrama);
        propiedades.setAttribute("type", "Logical");
        diagrama.appendChild(propiedades);

        Element proyecto = doc.createElement("project");
        proyecto.setAttribute("author", "ModelCollab");
        proyecto.setAttribute("version", "1.0");
        proyecto.setAttribute("created", FECHA_PLACEHOLDER);
        proyecto.setAttribute("modified", FECHA_PLACEHOLDER);
        diagrama.appendChild(proyecto);

        Element elementos = doc.createElement("elements");
        int seqno = 1;
        for (ClassEntity clase : modelo.classes()) {
            Element e = doc.createElement("element");
            e.setAttribute("geometry", geometria(clase));
            e.setAttribute("subject", clase.id());
            e.setAttribute("seqno", String.valueOf(seqno++));
            elementos.appendChild(e);
        }
        // Los conectores también son apariciones en la lámina. Sin su entrada acá, EA abre el
        // diagrama con las clases sueltas y ninguna línea entre ellas.
        for (Relationship r : modelo.relationships()) {
            Element e = doc.createElement("element");
            e.setAttribute("geometry", "SX=0;SY=0;EX=0;EY=0;EDGE=1;");
            e.setAttribute("subject", r.id());
            e.setAttribute("seqno", String.valueOf(seqno++));
            elementos.appendChild(e);
        }
        diagrama.appendChild(elementos);

        diagramas.appendChild(diagrama);
        return diagramas;
    }

    /**
     * El rectángulo de la clase en el formato de cadena que usa EA.
     *
     * <p>Es exactamente la misma posición y el mismo tamaño que ya van en nuestra extensión: el
     * archivo dice lo mismo dos veces, en dos idiomas.
     */
    private static String geometria(ClassEntity clase) {
        long x = Math.round(clase.position() == null ? 0 : clase.position().x());
        long y = Math.round(clase.position() == null ? 0 : clase.position().y());
        long ancho = Math.round(clase.width() == null ? ClassEntity.DEFAULT_WIDTH : clase.width());
        long alto = Math.round(clase.height() == null ? ClassEntity.DEFAULT_HEIGHT : clase.height());
        return "Left=" + x + ";Top=" + y + ";Right=" + (x + ancho) + ";Bottom=" + (y + alto) + ";";
    }

    /**
     * Qué paquete es dueño de cada clase. Las que no están en ninguno cuelgan del modelo raíz,
     * que es lo mismo que hace el UML estándar que emitimos: una clase suelta es hija directa
     * del {@code uml:Model}.
     */
    private static Map<String, String> paquetePorClase(CanonicalModel modelo, String idDelModelo) {
        Map<String, String> destino = new HashMap<>();
        for (com.modelcollab.metamodel.model.Package paquete : modelo.packages()) {
            for (String classId : paquete.classIds()) {
                destino.putIfAbsent(classId, paquete.id());
            }
        }
        for (ClassEntity clase : modelo.classes()) {
            destino.putIfAbsent(clase.id(), idDelModelo);
        }
        return destino;
    }
}
