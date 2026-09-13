package com.modelcollab.xmi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.config.JacksonConfig;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.ClassEntity;
import com.modelcollab.xmi.service.EnterpriseArchitectViewWriter;
import com.modelcollab.xmi.service.XmiExporterService;
import com.modelcollab.xmi.service.XmiImporterService;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * La <b>lámina</b> para Enterprise Architect, que es lo que faltaba en la primera prueba real.
 *
 * <h2>Qué prueba esto y qué no</h2>
 *
 * <p>Daniel abrió {@code inventario.xmi} en EA y pasaron dos cosas: importó el modelo —ve los
 * paquetes y sus clases— y <b>no tuvo ningún diagrama que abrir</b>. Exportábamos el modelo pero
 * no la vista.
 *
 * <p>Estos tests comprueban que la sección de vista <b>está y es coherente</b>: que hay una
 * lámina, que cada clase y cada conector aparecen en ella, que la geometría es la misma que ya
 * llevaba nuestra extensión y que ninguna referencia queda colgada. Eso es todo lo que se puede
 * comprobar desde acá.
 *
 * <p><b>Lo que NO prueban es que EA la abra.</b> El dialecto de EA no tiene esquema público
 * verificable, así que el único juez es una persona con la herramienta instalada. Un test verde
 * acá significa "el archivo dice lo que quisimos decir", no "el archivo se entiende". Es la misma
 * distinción que nos hizo descartar escribir nuestro propio XSD: validar contra lo que uno mismo
 * escribió no prueba nada sobre otra herramienta.
 */
class EnterpriseArchitectViewTest {

    private final ObjectMapper mapper = JacksonConfig.canonicalObjectMapper();
    private final XmiExporterService exportador =
            new XmiExporterService(new EnterpriseArchitectViewWriter());
    private final XmiImporterService importador = new XmiImporterService();

    private CanonicalModel leer(String recurso) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(recurso)) {
            assertThat(in).as("falta el fixture %s", recurso).isNotNull();
            return mapper.readValue(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8), CanonicalModel.class);
        }
    }

    // ------------------------------------------------------- las dos capas

    @Test
    @DisplayName("El archivo lleva las dos extensiones: la de EA y la nuestra")
    void conviven() throws Exception {
        Document doc = parsear(exportador.exportar(leer("reference-diagrams/inventario.json"), "X"));

        List<String> extenders = new ArrayList<>();
        NodeList extensiones =
                doc.getElementsByTagNameNS(XmiExporterService.NS_XMI, "Extension");
        for (int i = 0; i < extensiones.getLength(); i++) {
            extenders.add(((Element) extensiones.item(i)).getAttribute("extender"));
        }
        // La de EA se agrega, no reemplaza: la nuestra es la que garantiza que el viaje entre
        // dos ModelCollab no pierda nada, y sigue siendo la que lee nuestro importador.
        assertThat(extenders)
                .containsExactlyInAnyOrder("Enterprise Architect", "ModelCollab");
    }

    @Test
    @DisplayName("Agregar la vista de EA no cambia lo que vuelve del viaje de ida y vuelta")
    void elViajeSigueCerrando() throws Exception {
        CanonicalModel original = leer("reference-diagrams/inventario.json");

        CanonicalModel vuelto = importador.importar(
                exportador.exportar(original, "X").getBytes(StandardCharsets.UTF_8));

        // Nuestro importador tiene que ignorar la extensión ajena por completo. Si algún día
        // leyera de las dos, dos fuentes para el mismo dato es una para discrepar.
        com.fasterxml.jackson.databind.JsonNode arbolVuelto = mapper.valueToTree(vuelto);
        com.fasterxml.jackson.databind.JsonNode arbolOriginal = mapper.valueToTree(original);
        assertThat(arbolVuelto).isEqualTo(arbolOriginal);
    }

    // ------------------------------------------------------------ la lámina

    @Test
    @DisplayName("Hay una lámina y todas las clases aparecen dibujadas en ella")
    void todaClaseApareceEnLaLamina() throws Exception {
        CanonicalModel modelo = leer("reference-diagrams/inventario.json");

        Document doc = parsear(exportador.exportar(modelo, "Inventario"));
        Element diagrama = lamina(doc);

        assertThat(diagrama)
                .as("sin <diagram> EA importa los objetos y no tiene nada que abrir")
                .isNotNull();
        assertThat(primerHijo(diagrama, "properties").getAttribute("name"))
                .isEqualTo("Inventario");

        Set<String> dibujados = new HashSet<>();
        for (Element e : apariciones(diagrama)) {
            dibujados.add(e.getAttribute("subject"));
        }
        assertThat(dibujados)
                .as("una clase que existe en el modelo pero no en la lámina no se ve")
                .containsAll(modelo.classes().stream().map(ClassEntity::id).toList());
    }

    @Test
    @DisplayName("Los conectores también se dibujan: si no, las clases salen sueltas")
    void todoConectorApareceEnLaLamina() throws Exception {
        CanonicalModel modelo = leer("reference-diagrams/inventario.json");
        assertThat(modelo.relationships())
                .as("el fixture tiene que traer relaciones para que el test signifique algo")
                .isNotEmpty();

        Element diagrama = lamina(parsear(exportador.exportar(modelo, "X")));

        Set<String> dibujados = new HashSet<>();
        apariciones(diagrama).forEach(e -> dibujados.add(e.getAttribute("subject")));
        assertThat(dibujados)
                .containsAll(modelo.relationships().stream()
                        .map(com.modelcollab.metamodel.model.Relationship::id).toList());
    }

    @Test
    @DisplayName("La geometría de la lámina es la misma posición que lleva nuestra extensión")
    void laGeometriaCoincideConElModelo() throws Exception {
        CanonicalModel modelo = leer("reference-diagrams/inventario.json");
        ClassEntity clase = modelo.classes().get(0);

        Element diagrama = lamina(parsear(exportador.exportar(modelo, "X")));

        String geometria = apariciones(diagrama).stream()
                .filter(e -> clase.id().equals(e.getAttribute("subject")))
                .findFirst()
                .orElseThrow()
                .getAttribute("geometry");

        // El archivo dice lo mismo dos veces en dos idiomas; que los dos digan lo mismo es
        // justamente lo que hay que sostener.
        long x = Math.round(clase.position().x());
        long y = Math.round(clase.position().y());
        assertThat(geometria).isEqualTo(
                "Left=" + x + ";Top=" + y
                        + ";Right=" + (x + Math.round(clase.width()))
                        + ";Bottom=" + (y + Math.round(clase.height())) + ";");
    }

    @Test
    @DisplayName("Ninguna referencia de la sección de EA queda colgada")
    void lasReferenciasDeLaExtensionResuelven() throws Exception {
        Document doc = parsear(exportador.exportar(leer("reference-diagrams/barberia.json"), "X"));

        Set<String> ids = new HashSet<>();
        NodeList todos = doc.getElementsByTagName("*");
        for (int i = 0; i < todos.getLength(); i++) {
            String id = ((Element) todos.item(i))
                    .getAttributeNS(XmiExporterService.NS_XMI, "id");
            if (!id.isBlank()) {
                ids.add(id);
            }
        }

        Element extension = extensionDeEa(doc);
        NodeList dentro = extension.getElementsByTagName("*");
        for (int i = 0; i < dentro.getLength(); i++) {
            Element e = (Element) dentro.item(i);
            for (String atributo : List.of("subject", "package", "owner")) {
                String valor = e.getAttribute(atributo);
                if (!valor.isBlank()) {
                    assertThat(ids)
                            .as("%s=%s en <%s> no apunta a ningún xmi:id del documento",
                                    atributo, valor, e.getNodeName())
                            .contains(valor);
                }
            }
            String idref = e.getAttributeNS(XmiExporterService.NS_XMI, "idref");
            if (!idref.isBlank()) {
                assertThat(ids).as("xmi:idref=%s colgado en la sección de EA", idref)
                        .contains(idref);
            }
        }
    }

    @Test
    @DisplayName("Cada clase declara a qué paquete pertenece, y es el paquete de verdad")
    void cadaElementoDeclaraSuPaquete() throws Exception {
        CanonicalModel modelo = leer("reference-diagrams/inventario.json");
        Document doc = parsear(exportador.exportar(modelo, "X"));

        Element extension = extensionDeEa(doc);
        NodeList elementos = primerHijo(extension, "elements").getElementsByTagName("element");
        assertThat(elementos.getLength()).isEqualTo(modelo.classes().size());

        for (int i = 0; i < elementos.getLength(); i++) {
            Element e = (Element) elementos.item(i);
            String classId = e.getAttributeNS(XmiExporterService.NS_XMI, "idref");
            String paqueteDeclarado = primerHijo(e, "model").getAttribute("package");

            String paqueteReal = modelo.packages().stream()
                    .filter(p -> p.classIds().contains(classId))
                    .map(com.modelcollab.metamodel.model.Package::id)
                    .findFirst()
                    // Una clase suelta cuelga del modelo raíz, igual que en el UML estándar.
                    .orElse("modelcollab-model");
            assertThat(paqueteDeclarado)
                    .as("la clase %s dice pertenecer a un paquete que no es el suyo", classId)
                    .isEqualTo(paqueteReal);
        }
    }

    @Test
    @DisplayName("Sigue siendo determinista: la fecha del formato es constante, no now()")
    void sigueSiendoDeterminista() throws Exception {
        CanonicalModel modelo = leer("reference-diagrams/barberia.json");

        // El modelo canónico no guarda fechas. Poner now() sería inventar un dato y además
        // haría que dos exportaciones del mismo diagrama no se pudieran comparar.
        assertThat(exportador.exportar(modelo, "X")).isEqualTo(exportador.exportar(modelo, "X"));
    }

    // ------------------------------------------------------------ utilitarios

    private static Element extensionDeEa(Document doc) {
        NodeList extensiones =
                doc.getElementsByTagNameNS(XmiExporterService.NS_XMI, "Extension");
        for (int i = 0; i < extensiones.getLength(); i++) {
            Element e = (Element) extensiones.item(i);
            if (EnterpriseArchitectViewWriter.EXTENDER.equals(e.getAttribute("extender"))) {
                return e;
            }
        }
        throw new AssertionError("no hay xmi:Extension de Enterprise Architect");
    }

    private static Element lamina(Document doc) {
        NodeList diagramas = extensionDeEa(doc).getElementsByTagName("diagram");
        return diagramas.getLength() == 0 ? null : (Element) diagramas.item(0);
    }

    /** Los {@code <element>} de dentro de la lámina: las apariciones dibujadas. */
    private static List<Element> apariciones(Element diagrama) {
        List<Element> resultado = new ArrayList<>();
        NodeList hijos = primerHijo(diagrama, "elements").getElementsByTagName("element");
        for (int i = 0; i < hijos.getLength(); i++) {
            resultado.add((Element) hijos.item(i));
        }
        return resultado;
    }

    private static Element primerHijo(Element padre, String etiqueta) {
        NodeList hijos = padre.getChildNodes();
        for (int i = 0; i < hijos.getLength(); i++) {
            if (hijos.item(i) instanceof Element e && etiqueta.equals(e.getNodeName())) {
                return e;
            }
        }
        throw new AssertionError("falta <" + etiqueta + "> dentro de <" + padre.getNodeName() + ">");
    }

    private static Document parsear(String xml) throws Exception {
        var fabrica = DocumentBuilderFactory.newInstance();
        fabrica.setNamespaceAware(true);
        return fabrica.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
