package com.modelcollab.xmi.service;

import com.modelcollab.metamodel.model.Attribute;
import com.modelcollab.metamodel.model.AttributeType;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.ClassEntity;
import com.modelcollab.metamodel.model.Method;
import com.modelcollab.metamodel.model.Multiplicity;
import com.modelcollab.metamodel.model.OwningSide;
import com.modelcollab.metamodel.model.Parameter;
import com.modelcollab.metamodel.model.Position;
import com.modelcollab.metamodel.model.Relationship;
import com.modelcollab.metamodel.model.RelationshipType;
import com.modelcollab.metamodel.model.Visibility;
import com.modelcollab.metamodel.model.Waypoint;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Importa un documento XMI 2.1 al modelo canónico (§12).
 *
 * <h2>Dos niveles de fidelidad, declarados</h2>
 *
 * <ol>
 *   <li><b>Nuestro propio XMI: sin pérdida.</b> La {@code xmi:Extension} trae posiciones,
 *       waypoints, tipos exactos, claves primarias y el lado dueño de cada relación, así que el
 *       modelo vuelve idéntico al que salió.</li>
 *   <li><b>XMI de otra herramienta: parcial y documentado.</b> Se recupera todo lo que UML
 *       expresa —clases, atributos, operaciones, asociaciones, herencia, paquetes— y al resto se
 *       le aplican los defaults de §7. Las clases entran en una grilla en vez de en la posición
 *       que tenían, porque esa información no viaja en UML estándar.</li>
 * </ol>
 *
 * <p>Prometer más que eso sería mentir: no hay forma de recuperar un dato que el archivo no trae.
 */
@Service
public class XmiImporterService {

    /** Disposición en grilla para lo que llega sin coordenadas. */
    private static final double ANCHO_DE_CELDA = 320;
    private static final double ALTO_DE_CELDA = 260;
    private static final int COLUMNAS = 4;

    public CanonicalModel importar(byte[] contenido) {
        Document doc = parsear(contenido);

        Map<String, Element> extensionClases = new HashMap<>();
        Map<String, Element> extensionAtributos = new HashMap<>();
        Map<String, Element> extensionRelaciones = new HashMap<>();
        Map<String, Element> extensionPaquetes = new HashMap<>();
        leerExtension(doc, extensionClases, extensionAtributos, extensionRelaciones,
                extensionPaquetes);

        List<ClassEntity> clases = new ArrayList<>();
        List<Relationship> relaciones = new ArrayList<>();
        Map<String, List<String>> clasesPorPaquete = new LinkedHashMap<>();
        Map<String, String> nombresDePaquete = new LinkedHashMap<>();

        int indice = 0;
        for (Element elemento : hijosPorEtiqueta(doc.getDocumentElement(), "packagedElement", true)) {
            String tipo = atributoXmi(elemento, "type");
            if ("uml:Package".equals(tipo)) {
                // El paquete raiz es un envoltorio para Enterprise Architect, no un paquete del
                // modelo: existe para que "Import Package from XMI" se traiga el diagrama entero
                // (ver XmiExporterService.ID_DEL_PAQUETE_RAIZ). Si entrara como paquete, cada
                // viaje de ida y vuelta agregaria uno que el usuario nunca dibujo, y dos viajes
                // seguidos los irian anidando. Lo detecto el arnes de ida y vuelta.
                if (XmiExporterService.ID_DEL_PAQUETE_RAIZ.equals(id(elemento))) {
                    continue;
                }
                nombresDePaquete.put(id(elemento), atributo(elemento, "name"));
                clasesPorPaquete.computeIfAbsent(id(elemento), k -> new ArrayList<>());
            } else if ("uml:Class".equals(tipo)) {
                clases.add(clase(elemento, extensionClases, extensionAtributos, indice++));
                Element padre = padreDirecto(elemento);
                if (padre != null && "uml:Package".equals(atributoXmi(padre, "type"))) {
                    clasesPorPaquete.computeIfAbsent(id(padre), k -> new ArrayList<>())
                            .add(id(elemento));
                }
                relaciones.addAll(generalizaciones(elemento, extensionRelaciones));
            } else if ("uml:Association".equals(tipo)) {
                relaciones.add(asociacion(elemento, extensionRelaciones));
            } else if ("uml:Dependency".equals(tipo) || "uml:Usage".equals(tipo)) {
                relaciones.add(dependencia(elemento, extensionRelaciones));
            }
        }

        List<com.modelcollab.metamodel.model.Package> paquetes = new ArrayList<>();
        for (Map.Entry<String, String> entrada : nombresDePaquete.entrySet()) {
            Element ext = extensionPaquetes.get(entrada.getKey());
            List<String> ids = ext != null && !atributo(ext, "classIds").isBlank()
                    // La extensión conserva el ORDEN en que estaban, que el anidamiento no
                    // garantiza; si no está, se usa el anidamiento del XML.
                    ? List.of(atributo(ext, "classIds").split(" "))
                    : clasesPorPaquete.getOrDefault(entrada.getKey(), List.of());
            paquetes.add(new com.modelcollab.metamodel.model.Package(
                    entrada.getKey(), entrada.getValue(), ids));
        }

        if (clases.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El archivo XMI no contiene ninguna clase que importar");
        }
        Element diagrama = diagramaDeLaExtension(doc);
        ordenar(clases, ClassEntity::id, diagrama, "classOrder");
        ordenar(relaciones, Relationship::id, diagrama, "relationshipOrder");

        return new CanonicalModel(
                CanonicalModel.CURRENT_SCHEMA_VERSION, 1L, paquetes, clases, relaciones);
    }

    // ------------------------------------------------------------------ clases

    private ClassEntity clase(
            Element elemento, Map<String, Element> extClases, Map<String, Element> extAtributos,
            int indice) {

        String id = id(elemento);
        Element ext = extClases.get(id);

        // Sin extensión no hay posiciones: se arma una grilla para que el diagrama importado sea
        // usable en vez de aparecer con todo apilado en el origen.
        double x = ext != null ? decimal(ext, "x", 0) : (indice % COLUMNAS) * ANCHO_DE_CELDA + 40;
        double y = ext != null ? decimal(ext, "y", 0) : (indice / COLUMNAS) * ALTO_DE_CELDA + 40;

        List<Attribute> atributos = new ArrayList<>();
        for (Element a : hijosPorEtiqueta(elemento, "ownedAttribute", false)) {
            atributos.add(atributoDe(a, extAtributos));
        }
        List<Method> metodos = new ArrayList<>();
        for (Element m : hijosPorEtiqueta(elemento, "ownedOperation", false)) {
            metodos.add(metodoDe(m));
        }
        return new ClassEntity(
                id,
                atributo(elemento, "name"),
                visibilidad(atributo(elemento, "visibility"), Visibility.PUBLIC),
                Boolean.parseBoolean(atributo(elemento, "isAbstract")),
                new Position(x, y),
                ext != null ? decimal(ext, "width", ClassEntity.DEFAULT_WIDTH) : null,
                ext != null ? decimal(ext, "height", ClassEntity.DEFAULT_HEIGHT) : null,
                atributos,
                metodos);
    }

    private Attribute atributoDe(Element elemento, Map<String, Element> extAtributos) {
        String id = id(elemento);
        Element ext = extAtributos.get(id);

        AttributeType tipo = ext != null
                ? enumeracion(atributo(ext, "type"), AttributeType.VARCHAR)
                : tipoDesdeUml(elemento);

        Element valor = primerHijo(elemento, "defaultValue");
        Element lower = primerHijo(elemento, "lowerValue");

        return new Attribute(
                id,
                atributo(elemento, "name"),
                tipo,
                ext != null ? entero(ext, "length") : null,
                ext != null ? entero(ext, "precision") : null,
                ext != null ? entero(ext, "scale") : null,
                visibilidad(atributo(elemento, "visibility"), Visibility.PRIVATE),
                ext != null && Boolean.parseBoolean(atributo(ext, "isPrimaryKey")),
                lower == null || "0".equals(atributo(lower, "value")),
                ext != null && Boolean.parseBoolean(atributo(ext, "isUnique")),
                valor == null ? null : atributo(valor, "value"));
    }

    /**
     * Tipo de un atributo cuando el archivo no es nuestro: se deduce del primitivo UML al que
     * apunta. Es una aproximación declarada — UML no distingue un VARCHAR de un TEXT ni de un
     * UUID, los tres son String.
     */
    private AttributeType tipoDesdeUml(Element elemento) {
        Element tipo = primerHijo(elemento, "type");
        String href = tipo == null ? "" : atributo(tipo, "href");
        String nombre = href.contains("#") ? href.substring(href.indexOf('#') + 1) : href;
        return switch (nombre) {
            case "Integer" -> AttributeType.INTEGER;
            case "Boolean" -> AttributeType.BOOLEAN;
            case "Real" -> AttributeType.DECIMAL;
            default -> AttributeType.VARCHAR;
        };
    }

    private Method metodoDe(Element elemento) {
        List<Parameter> parametros = new ArrayList<>();
        String retorno = null;
        for (Element p : hijosPorEtiqueta(elemento, "ownedParameter", false)) {
            if ("return".equals(atributo(p, "direction"))) {
                retorno = tipoDeParametro(p);
            } else {
                parametros.add(new Parameter(atributo(p, "name"), tipoDeParametro(p)));
            }
        }
        return new Method(
                id(elemento),
                atributo(elemento, "name"),
                retorno == null || retorno.isBlank() ? "void" : retorno,
                visibilidad(atributo(elemento, "visibility"), Visibility.PUBLIC),
                parametros);
    }

    /**
     * Tipo de un parametro. Se prefiere {@code mc:type} —texto libre exacto— y si no esta se cae
     * al {@code type} estandar, que en un archivo ajeno referencia a otro elemento.
     */
    private static String tipoDeParametro(Element p) {
        String propio = p.getAttributeNS(XmiExporterService.NS_MC, "type");
        if (propio != null && !propio.isBlank()) {
            return propio;
        }
        String estandar = atributo(p, "type");
        return estandar.isBlank() ? "void" : estandar;
    }

    // -------------------------------------------------------------- relaciones

    private List<Relationship> generalizaciones(Element clase, Map<String, Element> ext) {
        List<Relationship> resultado = new ArrayList<>();
        for (Element g : hijosPorEtiqueta(clase, "generalization", false)) {
            // Una generalizacion tambien puede tener waypoints: el usuario acomoda la flecha de
            // herencia igual que cualquier otro conector. Olvidarse de leer su extension aca los
            // perdia en silencio, y solo se notaba al volver y ver la flecha recta.
            resultado.add(new Relationship(
                    id(g), id(clase), atributo(g, "general"), RelationshipType.INHERITANCE,
                    OwningSide.SOURCE, null, null, null, null, null, true,
                    waypointsDe(ext.get(id(g)))));
        }
        return resultado;
    }

    private Relationship dependencia(Element elemento, Map<String, Element> ext) {
        return new Relationship(
                id(elemento),
                atributo(elemento, "client"),
                atributo(elemento, "supplier"),
                RelationshipType.DEPENDENCY,
                OwningSide.SOURCE, null, null, null, null, null, true,
                waypointsDe(ext.get(id(elemento))));
    }

    private static List<Waypoint> waypointsDe(Element extension) {
        if (extension == null) {
            return List.of();
        }
        List<Waypoint> waypoints = new ArrayList<>();
        for (Element w : hijosPorEtiqueta(extension, "mc:waypoint", false)) {
            waypoints.add(new Waypoint(decimal(w, "x", 0), decimal(w, "y", 0)));
        }
        return waypoints;
    }

    private Relationship asociacion(Element elemento, Map<String, Element> extRelaciones) {
        String id = id(elemento);
        Element ext = extRelaciones.get(id);

        List<Element> extremos = hijosPorEtiqueta(elemento, "ownedEnd", false);
        // El primer ownedEnd apunta al destino y el segundo al origen (ver el exportador): el
        // extremo lleva el tipo de la clase del OTRO lado, que es como lo define UML.
        String destino = extremos.size() > 0 ? atributo(extremos.get(0), "type") : "";
        String origen = extremos.size() > 1 ? atributo(extremos.get(1), "type") : "";

        RelationshipType tipo = ext != null
                ? enumeracion(atributo(ext, "type"), RelationshipType.ASSOCIATION)
                : tipoDesdeAgregacion(extremos);

        Multiplicity ms = ext != null
                ? multiplicidad(atributo(ext, "sourceMultiplicity"))
                : (extremos.size() > 1 ? multiplicidadDeUml(extremos.get(1)) : null);
        Multiplicity mt = ext != null
                ? multiplicidad(atributo(ext, "targetMultiplicity"))
                : (extremos.size() > 0 ? multiplicidadDeUml(extremos.get(0)) : null);

        return new Relationship(
                id,
                origen,
                destino,
                tipo,
                ext != null ? enumeracion(atributo(ext, "owningSide"), OwningSide.SOURCE)
                        : OwningSide.SOURCE,
                ext != null && !atributo(ext, "joinTableName").isBlank()
                        ? atributo(ext, "joinTableName") : null,
                ms,
                mt,
                ext != null && !atributo(ext, "sourceRole").isBlank()
                        ? atributo(ext, "sourceRole")
                        : (extremos.size() > 1 ? nombreONulo(extremos.get(1)) : null),
                ext != null && !atributo(ext, "targetRole").isBlank()
                        ? atributo(ext, "targetRole")
                        : (extremos.size() > 0 ? nombreONulo(extremos.get(0)) : null),
                ext == null || Boolean.parseBoolean(atributo(ext, "isNavigable")),
                waypointsDe(ext));
    }

    /**
     * Sin extensión, el tipo se deduce de la agregación UML. <b>Es ambiguo a propósito y hay que
     * saberlo:</b> UML no distingue una asociación simple de una muchos-a-muchos —las dos tienen
     * {@code aggregation="none"}— así que un M2M de otra herramienta entra como ASSOCIATION.
     */
    private RelationshipType tipoDesdeAgregacion(List<Element> extremos) {
        for (Element extremo : extremos) {
            String agregacion = atributo(extremo, "aggregation");
            if ("composite".equals(agregacion)) {
                return RelationshipType.COMPOSITION;
            }
            if ("shared".equals(agregacion)) {
                return RelationshipType.AGGREGATION;
            }
        }
        boolean ambosMuchos = extremos.size() == 2
                && extremos.stream().allMatch(e -> {
                    Element upper = primerHijo(e, "upperValue");
                    return upper != null && "*".equals(atributo(upper, "value"));
                });
        return ambosMuchos ? RelationshipType.MANY_TO_MANY : RelationshipType.ASSOCIATION;
    }

    private Multiplicity multiplicidadDeUml(Element extremo) {
        Element lower = primerHijo(extremo, "lowerValue");
        Element upper = primerHijo(extremo, "upperValue");
        if (lower == null && upper == null) {
            return null;
        }
        String inferior = lower == null ? "1" : atributo(lower, "value");
        String superior = upper == null ? "1" : atributo(upper, "value");
        return multiplicidad(inferior + ".." + superior);
    }

    /**
     * Restaura el orden que las listas tenian en el modelo original.
     *
     * <p>El documento XMI reordena: las clases quedan anidadas en sus paquetes y las
     * generalizaciones dentro de la clase hija, asi que al recorrerlo salen en otra secuencia. El
     * orden no es cosmetico — decide que elemento se dibuja encima de cual cuando se superponen —
     * y UML no lo expresa, por eso viaja en la extension.
     *
     * <p>Lo que no aparezca en la lista queda al final, en el orden en que venia: asi un archivo
     * ajeno, que no trae la lista, no pierde nada.
     */
    private static <T> void ordenar(
            List<T> elementos, java.util.function.Function<T, String> idDe, Element diagrama,
            String atributo) {

        if (diagrama == null) {
            return;
        }
        String orden = diagrama.getAttribute(atributo);
        if (orden == null || orden.isBlank()) {
            return;
        }
        List<String> ids = List.of(orden.split(" "));
        elementos.sort(java.util.Comparator.comparingInt(e -> {
            int i = ids.indexOf(idDe.apply(e));
            return i < 0 ? Integer.MAX_VALUE : i;
        }));
    }

    private Element diagramaDeLaExtension(Document doc) {
        NodeList nodos = doc.getElementsByTagNameNS(XmiExporterService.NS_MC, "diagram");
        return nodos.getLength() == 0 ? null : (Element) nodos.item(0);
    }

    // ------------------------------------------------------------- extensión

    private void leerExtension(
            Document doc, Map<String, Element> clases, Map<String, Element> atributos,
            Map<String, Element> relaciones, Map<String, Element> paquetes) {

        NodeList extensiones = doc.getElementsByTagNameNS(XmiExporterService.NS_XMI, "Extension");
        for (int i = 0; i < extensiones.getLength(); i++) {
            Element ext = (Element) extensiones.item(i);
            if (!XmiExporterService.EXTENDER.equals(ext.getAttribute("extender"))) {
                // La extensión de otra herramienta —la de Enterprise Architect, por ejemplo— se
                // ignora: su contenido es privado y no lo sabemos interpretar.
                continue;
            }
            recolectar(ext, "mc:class", clases);
            recolectar(ext, "mc:attribute", atributos);
            recolectar(ext, "mc:relationship", relaciones);
            recolectar(ext, "mc:package", paquetes);
        }
    }

    private void recolectar(Element raiz, String etiqueta, Map<String, Element> destino) {
        NodeList nodos = raiz.getElementsByTagName(etiqueta);
        for (int i = 0; i < nodos.getLength(); i++) {
            Element e = (Element) nodos.item(i);
            destino.put(e.getAttribute("id"), e);
        }
    }

    // ------------------------------------------------------------ utilitarios

    private Document parsear(byte[] contenido) {
        try {
            var fabrica = DocumentBuilderFactory.newInstance();
            fabrica.setNamespaceAware(true);
            // Un XMI viene de afuera: deshabilitar DTDs y entidades externas cierra XXE, que es
            // la vulnerabilidad clásica de todo endpoint que acepta XML subido por el usuario.
            fabrica.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            fabrica.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            fabrica.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            fabrica.setXIncludeAware(false);
            fabrica.setExpandEntityReferences(false);

            return fabrica.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(contenido));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El archivo no es un XML válido: " + e.getMessage());
        }
    }

    /** Busca por nombre local, ignorando el prefijo: cada herramienta usa el suyo. */
    private static List<Element> hijosPorEtiqueta(
            Element raiz, String etiqueta, boolean enProfundidad) {

        List<Element> resultado = new ArrayList<>();
        String local = etiqueta.contains(":") ? etiqueta.substring(etiqueta.indexOf(':') + 1) : etiqueta;
        recorrer(raiz, local, enProfundidad, resultado, true);
        return resultado;
    }

    private static void recorrer(
            Node nodo, String local, boolean enProfundidad, List<Element> destino, boolean esRaiz) {

        NodeList hijos = nodo.getChildNodes();
        for (int i = 0; i < hijos.getLength(); i++) {
            Node hijo = hijos.item(i);
            if (hijo.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element e = (Element) hijo;
            if (local.equals(e.getLocalName()) || local.equals(sinPrefijo(e.getNodeName()))) {
                destino.add(e);
            }
            if (enProfundidad) {
                recorrer(e, local, true, destino, false);
            }
        }
    }

    private static String sinPrefijo(String nombre) {
        return nombre.contains(":") ? nombre.substring(nombre.indexOf(':') + 1) : nombre;
    }

    private static Element primerHijo(Element raiz, String etiqueta) {
        List<Element> encontrados = hijosPorEtiqueta(raiz, etiqueta, false);
        return encontrados.isEmpty() ? null : encontrados.get(0);
    }

    private static Element padreDirecto(Element e) {
        Node padre = e.getParentNode();
        return padre instanceof Element p ? p : null;
    }

    /** {@code xmi:id}, o uno nuevo si el archivo no lo trae. */
    private static String id(Element e) {
        String id = atributoXmi(e, "id");
        return id.isBlank() ? UUID.randomUUID().toString() : id;
    }

    /**
     * Atributo <b>plano</b>, sin namespace.
     *
     * <p>Separado del de {@code xmi:} a proposito: un {@code ownedEnd} tiene AL MISMO TIEMPO un
     * {@code xmi:type="uml:Property"} y un {@code type="c1"} que apunta a la clase del otro
     * extremo. Un unico lector que prefiriera el de XMI devolveria "uml:Property" donde se
     * espera el id de una clase, y la relacion quedaria apuntando a la nada.
     */
    private static String atributo(Element e, String nombre) {
        String valor = e.getAttribute(nombre);
        return valor == null ? "" : valor;
    }

    /** Atributo del namespace de XMI, con la variante sin namespace que usan algunos dialectos. */
    private static String atributoXmi(Element e, String nombre) {
        String valor = e.getAttributeNS(XmiExporterService.NS_XMI, nombre);
        if (valor == null || valor.isEmpty()) {
            valor = e.getAttribute("xmi:" + nombre);
        }
        return valor == null ? "" : valor;
    }

    private static double decimal(Element e, String nombre, double porDefecto) {
        String valor = atributo(e, nombre);
        try {
            return valor.isBlank() ? porDefecto : Double.parseDouble(valor);
        } catch (NumberFormatException ex) {
            return porDefecto;
        }
    }

    private static Integer entero(Element e, String nombre) {
        String valor = atributo(e, nombre);
        try {
            return valor.isBlank() ? null : Integer.valueOf(valor);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String nombreONulo(Element e) {
        String nombre = e.getAttribute("name");
        return nombre == null || nombre.isBlank() ? null : nombre;
    }

    private static Visibility visibilidad(String valor, Visibility porDefecto) {
        return enumeracion(valor == null ? null : valor.toUpperCase(), porDefecto);
    }

    private static Multiplicity multiplicidad(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        try {
            return Multiplicity.fromValue(valor);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static <T extends Enum<T>> T enumeracion(String valor, T porDefecto) {
        if (valor == null || valor.isBlank()) {
            return porDefecto;
        }
        try {
            return Enum.valueOf(porDefecto.getDeclaringClass(), valor);
        } catch (IllegalArgumentException e) {
            return porDefecto;
        }
    }
}
