package com.modelcollab.xmi.service;

import com.modelcollab.metamodel.model.Attribute;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.ClassEntity;
import com.modelcollab.metamodel.model.Method;
import com.modelcollab.metamodel.model.Multiplicity;
import com.modelcollab.metamodel.model.Parameter;
import com.modelcollab.metamodel.model.Relationship;
import com.modelcollab.metamodel.model.RelationshipType;
import com.modelcollab.metamodel.model.Waypoint;
import java.io.StringWriter;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Exporta un diagrama a XMI 2.1 (§12).
 *
 * <h2>Dos capas en un mismo archivo, y por qué</h2>
 *
 * <p>El documento tiene <b>UML estándar</b> —paquetes, clases, atributos, operaciones,
 * asociaciones, generalizaciones— y una <b>{@code xmi:Extension}</b> propia. No es un atajo: es
 * la única forma de cumplir los dos objetivos a la vez.
 *
 * <ul>
 *   <li>La parte estándar es lo que <b>otra herramienta sabe leer</b>. Enterprise Architect abre
 *       el archivo y ve el modelo, porque no depende de nada nuestro.</li>
 *   <li>La extensión es lo que hace que el <b>viaje de ida y vuelta no pierda nada</b>. UML no
 *       tiene dónde guardar la posición que el usuario le dio a una clase, los waypoints de un
 *       conector, si un atributo es clave primaria, o de qué lado va la clave foránea. Sin
 *       extensión, volver del XMI dejaría los conectores rectos y las clases apiladas en el
 *       origen — información que el usuario puso a mano y que desaparecería en silencio.</li>
 * </ul>
 *
 * <p>Es el mismo mecanismo que usa Enterprise Architect para sus propios datos de diagrama
 * ({@code <xmi:Extension extender="Enterprise Architect">}), así que no es una rareza nuestra:
 * es para lo que existe el elemento.
 *
 * <p>Consecuencia asumida, y documentada: importar <b>nuestro</b> XMI es sin pérdida; importar el
 * de otra herramienta recupera la estructura estándar y aplica los defaults de §7 al resto.
 */
@Service
public class XmiExporterService {

    public static final String NS_XMI = "http://schema.omg.org/spec/XMI/2.1";
    public static final String NS_UML = "http://schema.omg.org/spec/UML/2.1";
    public static final String EXTENDER = "ModelCollab";

    /**
     * Namespace propio de la extensión. Declararlo no es una formalidad: un prefijo sin namespace
     * hace que el documento <b>no sea XML válido</b>, y cualquier parser lo rechaza antes de
     * llegar a mirar el contenido — incluido el nuestro al reimportar.
     */
    public static final String NS_MC = "http://modelcollab.dev/schema/xmi/1.0";

    /** Id del {@code uml:Model} raiz. */
    static final String ID_DEL_MODELO = "modelcollab-model";

    /**
     * Id del <b>paquete raiz</b>, que contiene absolutamente todo: sub-paquetes, clases y
     * relaciones.
     *
     * <h2>Por que existe, con la observacion que lo motivo</h2>
     *
     * <p>Enterprise Architect importa con <b>{@code Import Package from XMI}</b>, y eso trae
     * <b>un</b> paquete. La vuelta 2 dejaba los sub-paquetes y las asociaciones como hijos
     * directos del {@code uml:Model}, asi que EA se traia el PRIMER paquete y descartaba el
     * resto.
     *
     * <p>Al abrir {@code inventario.xmi} se vio exactamente eso, y los tres sintomas tienen esta
     * unica causa:
     *
     * <ul>
     *   <li>Entraron las 3 clases de {@code catalogo} —el primer paquete— y <b>no</b> las 2 de
     *       {@code operaciones}.</li>
     *   <li>Aparecio un {@code Package2} vacio en el arbol: es {@code operaciones}, del que EA
     *       dejo un muñon porque estaba fuera de lo importado.</li>
     *   <li><b>Ninguna relacion se dibujo</b>: las cuatro asociaciones colgaban del
     *       {@code uml:Model}, o sea fuera del paquete que se importo.</li>
     * </ul>
     *
     * <p>La sospecha que traiamos era que las asociaciones caian dentro de ese {@code Package2}.
     * <b>Es falsa</b> — el arbol del archivo muestra que colgaban del modelo— y si la hubieramos
     * arreglado sin mirar, las clases de {@code operaciones} habrian seguido faltando.
     */
    static final String ID_DEL_PAQUETE_RAIZ = "modelcollab-root-package";

    /** Tipos primitivos de UML a los que apuntan los atributos exportados. */
    private static final Map<String, String> PRIMITIVOS = Map.of(
            "INTEGER", "Integer",
            "BIGINT", "Integer",
            "VARCHAR", "String",
            "TEXT", "String",
            "DECIMAL", "Real",
            "BOOLEAN", "Boolean",
            "DATE", "String",
            "DATETIME", "String",
            "UUID", "String");

    private final EnterpriseArchitectViewWriter vistaEa;

    /**
     * Un solo constructor publico, a proposito. Con dos, Spring no sabe cual elegir, busca uno
     * sin argumentos, no lo encuentra y la aplicacion no arranca — con toda la suite en verde,
     * porque los tests construyen la clase a mano. Ya nos paso con LockManagerService.
     */
    public XmiExporterService(EnterpriseArchitectViewWriter vistaEa) {
        this.vistaEa = vistaEa;
    }

    public String exportar(CanonicalModel modelo, String nombreDelDiagrama) {
        try {
            var fabrica = DocumentBuilderFactory.newInstance();
            fabrica.setNamespaceAware(true);
            Document doc = fabrica.newDocumentBuilder().newDocument();

            Element raiz = doc.createElementNS(NS_XMI, "xmi:XMI");
            raiz.setAttribute("xmlns:xmi", NS_XMI);
            raiz.setAttribute("xmlns:uml", NS_UML);
            raiz.setAttribute("xmlns:mc", NS_MC);
            raiz.setAttributeNS(NS_XMI, "xmi:version", "2.1");
            doc.appendChild(raiz);

            // Le dice a Enterprise Architect en que dialecto esta escrita la seccion de
            // extension de mas abajo. NO dice quien escribio el archivo: eso lo dice el
            // comentario que va justo arriba, que viaja en el documento y cualquiera puede
            // leer. Es la distincion entre declarar un formato y atribuirse una autoria.
            //
            // Se agrega en la vuelta 2 del ajuste con EA: la vuelta 1 no lo llevaba —por no
            // querer declarar algo falso— y EA ignoro la seccion de diagramas por completo.
            raiz.appendChild(doc.createComment(
                    " Archivo producido por ModelCollab. El atributo exporter de la linea "
                            + "siguiente declara el DIALECTO de la xmi:Extension, no el autor. "));
            Element documentacion = doc.createElementNS(NS_XMI, "xmi:Documentation");
            documentacion.setAttribute("exporter", EnterpriseArchitectViewWriter.EXTENDER);
            documentacion.setAttribute("exporterVersion", "6.5");
            raiz.appendChild(documentacion);

            Element model = doc.createElementNS(NS_UML, "uml:Model");
            model.setAttributeNS(NS_XMI, "xmi:type", "uml:Model");
            model.setAttributeNS(NS_XMI, "xmi:id", ID_DEL_MODELO);
            model.setAttribute("name", nombreDelDiagrama == null ? "Diagrama" : nombreDelDiagrama);
            raiz.appendChild(model);

            // TODO lo demas cuelga de un unico paquete raiz, no del modelo: ver
            // ID_DEL_PAQUETE_RAIZ. Es lo que hace que "Import Package from XMI" traiga el
            // diagrama entero y no solo su primer paquete.
            Element paqueteRaiz = doc.createElement("packagedElement");
            paqueteRaiz.setAttributeNS(NS_XMI, "xmi:type", "uml:Package");
            paqueteRaiz.setAttributeNS(NS_XMI, "xmi:id", ID_DEL_PAQUETE_RAIZ);
            paqueteRaiz.setAttribute(
                    "name", nombreDelDiagrama == null ? "Diagrama" : nombreDelDiagrama);
            model.appendChild(paqueteRaiz);

            escribirModelo(doc, paqueteRaiz, modelo);
            // Primero la de EA y despues la nuestra. El orden entre extensiones no deberia
            // importarle a ningun lector —cada una se identifica por su atributo extender, que es
            // lo que hace nuestro propio importador— pero si algun lector mira solo la primera,
            // que sea la que le habla a el.
            raiz.appendChild(
                    vistaEa.escribir(doc, modelo, nombreDelDiagrama, ID_DEL_PAQUETE_RAIZ));
            raiz.appendChild(extension(doc, modelo));

            return serializar(doc);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo exportar el diagrama a XMI: " + e.getMessage(), e);
        }
    }

    // ----------------------------------------------------------- UML estándar

    /** {@code model} es el paquete raiz, no el {@code uml:Model}: ver ID_DEL_PAQUETE_RAIZ. */
    private void escribirModelo(Document doc, Element model, CanonicalModel modelo) {
        Map<String, ClassEntity> porId = new LinkedHashMap<>();
        modelo.classes().forEach(c -> porId.put(c.id(), c));

        // Las clases que pertenecen a un paquete van dentro de él; el resto, sueltas en el modelo.
        Set<String> enPaquetes = new HashSet<>();
        for (com.modelcollab.metamodel.model.Package paquete : modelo.packages()) {
            Element pkg = doc.createElement("packagedElement");
            pkg.setAttributeNS(NS_XMI, "xmi:type", "uml:Package");
            pkg.setAttributeNS(NS_XMI, "xmi:id", paquete.id());
            pkg.setAttribute("name", paquete.name());
            model.appendChild(pkg);

            for (String classId : paquete.classIds()) {
                ClassEntity clase = porId.get(classId);
                if (clase != null && enPaquetes.add(classId)) {
                    pkg.appendChild(clase(doc, clase, modelo));
                }
            }
        }
        for (ClassEntity clase : modelo.classes()) {
            if (!enPaquetes.contains(clase.id())) {
                model.appendChild(clase(doc, clase, modelo));
            }
        }
        for (Relationship r : modelo.relationships()) {
            // La generalización se escribe dentro de la clase hija, no acá: así lo define UML.
            if (r.type() != RelationshipType.INHERITANCE) {
                model.appendChild(relacion(doc, r, modelo));
            }
        }
    }

    private Element clase(Document doc, ClassEntity clase, CanonicalModel modelo) {
        Element e = doc.createElement("packagedElement");
        e.setAttributeNS(NS_XMI, "xmi:type", "uml:Class");
        e.setAttributeNS(NS_XMI, "xmi:id", clase.id());
        e.setAttribute("name", clase.name());
        e.setAttribute("visibility", clase.visibility().name().toLowerCase());
        if (Boolean.TRUE.equals(clase.isAbstract())) {
            e.setAttribute("isAbstract", "true");
        }
        for (Attribute atributo : clase.attributes()) {
            e.appendChild(atributo(doc, atributo));
        }
        for (Method metodo : clase.methods()) {
            e.appendChild(metodo(doc, metodo));
        }
        // Generalizaciones: UML las anida en la clase que hereda.
        modelo.relationships().stream()
                .filter(r -> r.type() == RelationshipType.INHERITANCE
                        && r.sourceClassId().equals(clase.id()))
                .forEach(r -> {
                    Element g = doc.createElement("generalization");
                    g.setAttributeNS(NS_XMI, "xmi:type", "uml:Generalization");
                    g.setAttributeNS(NS_XMI, "xmi:id", r.id());
                    g.setAttribute("general", r.targetClassId());
                    e.appendChild(g);
                });
        return e;
    }

    private Element atributo(Document doc, Attribute atributo) {
        Element e = doc.createElement("ownedAttribute");
        e.setAttributeNS(NS_XMI, "xmi:type", "uml:Property");
        e.setAttributeNS(NS_XMI, "xmi:id", atributo.id());
        e.setAttribute("name", atributo.name());
        e.setAttribute("visibility", atributo.visibility().name().toLowerCase());
        // CONTRA EL "{bag}" QUE ENSUCIA LA LAMINA EN EA.
        //
        // Daniel abrio el archivo y cada atributo salia con "{bag}" pegado al final. No rompe
        // nada, pero en una defensa se ve. En UML, una propiedad sin orden y sin unicidad ES una
        // bolsa, y EA lo anota; el valor por defecto de isUnique en la especificacion es true,
        // pero al no escribirlo EA lo toma como false. Declarandolo, la propiedad pasa a ser un
        // conjunto y EA no le pone nada.
        //
        // NO VERIFICADO: es la explicacion que mejor encaja con el sintoma, no una observacion.
        // Va en la misma vuelta que el paquete raiz para que se compruebe de una sola pasada.
        e.setAttribute("isUnique", "true");
        e.setAttribute("isOrdered", "false");

        Element tipo = doc.createElement("type");
        tipo.setAttributeNS(NS_XMI, "xmi:type", "uml:PrimitiveType");
        tipo.setAttribute("href",
                NS_UML + "/uml.xml#" + PRIMITIVOS.getOrDefault(atributo.type().name(), "String"));
        e.appendChild(tipo);

        if (atributo.defaultValue() != null) {
            Element valor = doc.createElement("defaultValue");
            valor.setAttributeNS(NS_XMI, "xmi:type", "uml:LiteralString");
            valor.setAttributeNS(NS_XMI, "xmi:id", atributo.id() + "-default");
            valor.setAttribute("value", atributo.defaultValue());
            e.appendChild(valor);
        }
        // isNullable se expresa como multiplicidad UML: 0..1 opcional, 1..1 obligatorio.
        e.appendChild(literal(doc, "lowerValue",
                atributo.id() + "-lower", Boolean.TRUE.equals(atributo.isNullable()) ? "0" : "1"));
        e.appendChild(literal(doc, "upperValue", atributo.id() + "-upper", "1"));
        return e;
    }

    private Element metodo(Document doc, Method metodo) {
        Element e = doc.createElement("ownedOperation");
        e.setAttributeNS(NS_XMI, "xmi:type", "uml:Operation");
        e.setAttributeNS(NS_XMI, "xmi:id", metodo.id());
        e.setAttribute("name", metodo.name());
        e.setAttribute("visibility", metodo.visibility().name().toLowerCase());

        int i = 0;
        for (Parameter parametro : metodo.parameters()) {
            Element p = doc.createElement("ownedParameter");
            p.setAttributeNS(NS_XMI, "xmi:type", "uml:Parameter");
            p.setAttributeNS(NS_XMI, "xmi:id", metodo.id() + "-p" + i++);
            p.setAttribute("name", parametro.name());
            p.setAttribute("direction", "in");
            // El tipo va en NUESTRO namespace y no como un 'type' suelto: en UML ese atributo
            // es una referencia a un elemento tipo, y el canónico guarda texto libre como
            // "List<Cita>". Escribirlo ahí dejaría una referencia colgada que otra herramienta
            // intentaría resolver y no encontraría.
            p.setAttributeNS(NS_MC, "mc:type", parametro.type());
            e.appendChild(p);
        }
        if (metodo.returnType() != null) {
            Element retorno = doc.createElement("ownedParameter");
            retorno.setAttributeNS(NS_XMI, "xmi:type", "uml:Parameter");
            retorno.setAttributeNS(NS_XMI, "xmi:id", metodo.id() + "-return");
            retorno.setAttribute("direction", "return");
            retorno.setAttributeNS(NS_MC, "mc:type", metodo.returnType());
            e.appendChild(retorno);
        }
        return e;
    }

    private Element relacion(Document doc, Relationship r, CanonicalModel modelo) {
        if (r.type() == RelationshipType.DEPENDENCY) {
            Element e = doc.createElement("packagedElement");
            e.setAttributeNS(NS_XMI, "xmi:type", "uml:Dependency");
            e.setAttributeNS(NS_XMI, "xmi:id", r.id());
            e.setAttribute("client", r.sourceClassId());
            e.setAttribute("supplier", r.targetClassId());
            return e;
        }

        Element e = doc.createElement("packagedElement");
        e.setAttributeNS(NS_XMI, "xmi:type", "uml:Association");
        e.setAttributeNS(NS_XMI, "xmi:id", r.id());

        String finOrigen = r.id() + "-src";
        String finDestino = r.id() + "-tgt";
        e.appendChild(idref(doc, "memberEnd", finOrigen));
        e.appendChild(idref(doc, "memberEnd", finDestino));

        // La agregación va en el extremo del TODO, que en UML es el opuesto a la parte.
        e.appendChild(extremo(doc, finOrigen, r.targetClassId(), r.targetMultiplicity(),
                r.targetRole(), agregacion(r.type())));
        e.appendChild(extremo(doc, finDestino, r.sourceClassId(), r.sourceMultiplicity(),
                r.sourceRole(), "none"));
        return e;
    }

    private Element extremo(
            Document doc, String id, String tipo, Multiplicity multiplicidad, String rol,
            String agregacion) {

        Element e = doc.createElement("ownedEnd");
        e.setAttributeNS(NS_XMI, "xmi:type", "uml:Property");
        e.setAttributeNS(NS_XMI, "xmi:id", id);
        if (rol != null) {
            e.setAttribute("name", rol);
        }
        e.setAttribute("type", tipo);
        e.setAttribute("aggregation", agregacion);
        if (multiplicidad != null) {
            e.appendChild(literal(doc, "lowerValue", id + "-lower",
                    multiplicidad == Multiplicity.ZERO_ONE || multiplicidad == Multiplicity.ZERO_MANY
                            ? "0" : "1"));
            e.appendChild(literal(doc, "upperValue", id + "-upper",
                    multiplicidad.isCollection() ? "*" : "1"));
        }
        return e;
    }

    private static String agregacion(RelationshipType tipo) {
        return switch (tipo) {
            case AGGREGATION -> "shared";
            case COMPOSITION -> "composite";
            default -> "none";
        };
    }

    // ------------------------------------------------------ xmi:Extension

    /**
     * Todo lo que UML no sabe guardar. Sin esto el viaje de vuelta perdería la disposición del
     * lienzo y la mitad de la semántica que el generador necesita.
     */
    private Element extension(Document doc, CanonicalModel modelo) {
        Element ext = doc.createElementNS(NS_XMI, "xmi:Extension");
        ext.setAttribute("extender", EXTENDER);
        ext.setAttribute("schemaVersion", modelo.schemaVersion());

        Element diagrama = doc.createElementNS(NS_MC, "mc:diagram");
        diagrama.setAttribute("mutationVersion", String.valueOf(modelo.mutationVersion()));
        // El ORDEN de las listas no lo expresa UML: al anidar las clases en sus paquetes, el
        // documento las reordena, y al volver quedarían en otra secuencia. Parece cosmético y no
        // lo es — el orden decide qué clase se dibuja encima de cuál cuando se superponen.
        diagrama.setAttribute("classOrder", modelo.classes().stream()
                .map(ClassEntity::id).reduce((a, b) -> a + " " + b).orElse(""));
        diagrama.setAttribute("relationshipOrder", modelo.relationships().stream()
                .map(Relationship::id).reduce((a, b) -> a + " " + b).orElse(""));
        ext.appendChild(diagrama);

        for (ClassEntity clase : modelo.classes()) {
            Element c = doc.createElementNS(NS_MC, "mc:class");
            c.setAttribute("id", clase.id());
            c.setAttribute("x", numero(clase.position() == null ? 0 : clase.position().x()));
            c.setAttribute("y", numero(clase.position() == null ? 0 : clase.position().y()));
            c.setAttribute("width", numero(clase.width()));
            c.setAttribute("height", numero(clase.height()));
            diagrama.appendChild(c);

            for (Attribute a : clase.attributes()) {
                Element ea = doc.createElementNS(NS_MC, "mc:attribute");
                ea.setAttribute("id", a.id());
                ea.setAttribute("type", a.type().name());
                ea.setAttribute("length", String.valueOf(a.length()));
                ea.setAttribute("precision", String.valueOf(a.precision()));
                ea.setAttribute("scale", String.valueOf(a.scale()));
                ea.setAttribute("isPrimaryKey", String.valueOf(a.isPrimaryKey()));
                ea.setAttribute("isUnique", String.valueOf(a.isUnique()));
                diagrama.appendChild(ea);
            }
        }
        for (Relationship r : modelo.relationships()) {
            Element er = doc.createElementNS(NS_MC, "mc:relationship");
            er.setAttribute("id", r.id());
            er.setAttribute("type", r.type().name());
            er.setAttribute("owningSide", r.owningSide().name());
            er.setAttribute("isNavigable", String.valueOf(r.isNavigable()));
            if (r.joinTableName() != null) {
                er.setAttribute("joinTableName", r.joinTableName());
            }
            if (r.sourceMultiplicity() != null) {
                er.setAttribute("sourceMultiplicity", r.sourceMultiplicity().getValue());
            }
            if (r.targetMultiplicity() != null) {
                er.setAttribute("targetMultiplicity", r.targetMultiplicity().getValue());
            }
            if (r.sourceRole() != null) {
                er.setAttribute("sourceRole", r.sourceRole());
            }
            if (r.targetRole() != null) {
                er.setAttribute("targetRole", r.targetRole());
            }
            // Los waypoints son la posición que el usuario le dio al conector a mano. Es lo
            // primero que se pierde en una implementación ingenua, y se nota de inmediato:
            // al volver, todos los conectores quedan rectos.
            for (Waypoint w : r.waypoints()) {
                Element ew = doc.createElementNS(NS_MC, "mc:waypoint");
                ew.setAttribute("x", numero(w.x()));
                ew.setAttribute("y", numero(w.y()));
                er.appendChild(ew);
            }
            diagrama.appendChild(er);
        }
        for (com.modelcollab.metamodel.model.Package p : modelo.packages()) {
            Element ep = doc.createElementNS(NS_MC, "mc:package");
            ep.setAttribute("id", p.id());
            ep.setAttribute("classIds", String.join(" ", p.classIds()));
            diagrama.appendChild(ep);
        }
        return ext;
    }

    // ------------------------------------------------------------- utilitarios

    private static Element literal(Document doc, String etiqueta, String id, String valor) {
        Element e = doc.createElement(etiqueta);
        e.setAttributeNS(NS_XMI, "xmi:type",
                "*".equals(valor) ? "uml:LiteralUnlimitedNatural" : "uml:LiteralInteger");
        e.setAttributeNS(NS_XMI, "xmi:id", id);
        e.setAttribute("value", valor);
        return e;
    }

    private static Element idref(Document doc, String etiqueta, String id) {
        Element e = doc.createElement(etiqueta);
        e.setAttributeNS(NS_XMI, "xmi:idref", id);
        return e;
    }

    /**
     * Un double sin cola decimal cuando es entero: {@code 240} y no {@code 240.0}. El archivo lo
     * lee una persona cuando algo sale mal, y además así el mismo diagrama produce siempre los
     * mismos bytes.
     */
    private static String numero(Double valor) {
        double d = valor == null ? 0 : valor;
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static String numero(double valor) {
        return numero(Double.valueOf(valor));
    }

    private static String serializar(Document doc) throws Exception {
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        StringWriter salida = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(salida));
        return salida.toString();
    }

    /** Los tipos primitivos que entiende el importador al volver. */
    static List<String> primitivosConocidos() {
        return List.copyOf(PRIMITIVOS.values());
    }
}
