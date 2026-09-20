package com.modelcollab.xmi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.config.JacksonConfig;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.RelationshipType;
import com.modelcollab.xmi.service.EnterpriseArchitectViewWriter;
import com.modelcollab.xmi.service.XmiExporterService;
import com.modelcollab.xmi.service.XmiImporterService;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * CU18: <b>el criterio es el viaje de ida y vuelta, no la exportación.</b>
 *
 * <p>Exportar solo no demuestra nada: un exportador que tira la mitad de los datos produce un
 * archivo perfectamente válido. Lo que hay que probar es que el modelo <b>vuelve igual</b>.
 *
 * <p>El criterio del plan es "exportar → abrir en Enterprise Architect → reimportar", y acá no
 * hay Enterprise Architect. Estas dos verificaciones juntas lo aproximan: el viaje interno prueba
 * que no se pierde información, y las comprobaciones estructurales prueban que el archivo cumple
 * la forma que otra herramienta espera. Ninguna alcanza sola, y las dos juntas siguen sin
 * reemplazar abrir el archivo en la herramienta real.
 */
class XmiRoundTripTest {

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

    private CanonicalModel idaYVuelta(CanonicalModel original) {
        String xmi = exportador.exportar(original, "Diagrama de prueba");
        return importador.importar(xmi.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------- el viaje de ida y vuelta

    @ParameterizedTest(name = "{0} sobrevive el viaje sin perder nada")
    @ValueSource(strings = {
        "reference-diagrams/barberia.json",
        "reference-diagrams/veterinaria.json",
        "reference-diagrams/inventario.json",
        "reference-diagrams/recursivas.json",
        "edge-cases.json",
    })
    @DisplayName("Exportar y reimportar devuelve EXACTAMENTE el mismo modelo canónico")
    void idaYVueltaSinPerdidas(String fixture) throws Exception {
        CanonicalModel original = leer(fixture);

        CanonicalModel vuelto = idaYVuelta(original);

        // Se comparan los árboles JSON y no los objetos: así un campo que se pierde aparece en el
        // diff con su nombre, en vez de dar un "no son iguales" sin decir qué.
        JsonNode arbolOriginal = mapper.valueToTree(original);
        JsonNode arbolVuelto = mapper.valueToTree(vuelto);

        assertThat(arbolVuelto)
                .as("el viaje por XMI perdió o alteró información en %s", fixture)
                .isEqualTo(arbolOriginal);
    }

    @Test
    @DisplayName("Los waypoints sobreviven: es lo primero que pierde una implementación ingenua")
    void losWaypointsSobreviven() throws Exception {
        CanonicalModel original = leer("reference-diagrams/barberia.json");
        long conWaypoints = original.relationships().stream()
                .filter(r -> !r.waypoints().isEmpty()).count();
        assertThat(conWaypoints)
                .as("el fixture tiene que traer waypoints para que este test signifique algo")
                .isPositive();

        CanonicalModel vuelto = idaYVuelta(original);

        // Si se perdieran, al volver los conectores quedarían rectos y el usuario vería
        // deshecho el trabajo que hizo a mano acomodando el diagrama.
        for (int i = 0; i < original.relationships().size(); i++) {
            assertThat(vuelto.relationships().get(i).waypoints())
                    .isEqualTo(original.relationships().get(i).waypoints());
        }
    }

    @Test
    @DisplayName("Los paquetes y su contenido sobreviven")
    void losPaquetesSobreviven() throws Exception {
        CanonicalModel original = leer("reference-diagrams/inventario.json");
        assertThat(original.packages())
                .as("el inventario existe desde el ciclo 1 justamente porque tiene dos paquetes")
                .hasSize(2);

        CanonicalModel vuelto = idaYVuelta(original);

        assertThat(vuelto.packages()).isEqualTo(original.packages());
    }

    @Test
    @DisplayName("Las posiciones y los tamaños sobreviven")
    void laDisposicionSobrevive() throws Exception {
        CanonicalModel original = leer("reference-diagrams/veterinaria.json");

        CanonicalModel vuelto = idaYVuelta(original);

        for (int i = 0; i < original.classes().size(); i++) {
            var antes = original.classes().get(i);
            var despues = vuelto.classes().get(i);
            assertThat(despues.position()).isEqualTo(antes.position());
            assertThat(despues.width()).isEqualTo(antes.width());
            assertThat(despues.height()).isEqualTo(antes.height());
        }
    }

    @Test
    @DisplayName("Los seis tipos de relación vuelven como salieron")
    void losTiposDeRelacionSobreviven() throws Exception {
        // ASSOCIATION y MANY_TO_MANY tienen la MISMA agregación UML (none): sin la extensión
        // serían indistinguibles al volver. Es el caso que obliga a tener extensión.
        CanonicalModel original = leer("edge-cases.json");
        CanonicalModel vuelto = idaYVuelta(original);

        assertThat(vuelto.relationships()).extracting(r -> r.type())
                .isEqualTo(original.relationships().stream().map(r -> r.type()).toList());
        assertThat(original.relationships()).extracting(r -> r.type())
                .contains(RelationshipType.MANY_TO_MANY, RelationshipType.ASSOCIATION,
                        RelationshipType.COMPOSITION, RelationshipType.DEPENDENCY);
    }

    @Test
    @DisplayName("La herencia vuelve como herencia, no como asociación")
    void laHerenciaSobrevive() throws Exception {
        CanonicalModel original = leer("reference-diagrams/veterinaria.json");
        CanonicalModel vuelto = idaYVuelta(original);

        assertThat(vuelto.relationships())
                .filteredOn(r -> r.type() == RelationshipType.INHERITANCE)
                .hasSize(2)
                .allSatisfy(r -> assertThat(r.sourceClassId()).isNotBlank());
    }

    // ------------------------------------ la forma que espera otra herramienta

    @Test
    @DisplayName("El documento declara los namespaces y la versión que manda §12")
    void laEnvolturaEsXmi21() throws Exception {
        Document doc = parsear(exportador.exportar(leer("reference-diagrams/barberia.json"), "X"));

        Element raiz = doc.getDocumentElement();
        assertThat(raiz.getLocalName()).isEqualTo("XMI");
        assertThat(raiz.getNamespaceURI()).isEqualTo("http://schema.omg.org/spec/XMI/2.1");
        assertThat(raiz.getAttribute("xmlns:uml")).isEqualTo("http://schema.omg.org/spec/UML/2.1");
        assertThat(raiz.getAttributeNS(XmiExporterService.NS_XMI, "version")).isEqualTo("2.1");
    }

    @Test
    @DisplayName("Todo elemento del modelo lleva xmi:id, y ninguno se repite")
    void losIdentificadoresSonUnicosYCompletos() throws Exception {
        Document doc = parsear(exportador.exportar(leer("edge-cases.json"), "X"));

        List<String> ids = new java.util.ArrayList<>();
        NodeList todos = doc.getElementsByTagName("*");
        for (int i = 0; i < todos.getLength(); i++) {
            Element e = (Element) todos.item(i);
            String id = e.getAttributeNS(XmiExporterService.NS_XMI, "id");
            // Los elementos del modelo llevan id; los de la extensión y los idref, no.
            if (!id.isBlank()) {
                ids.add(id);
            }
        }
        // Un xmi:id repetido hace que otra herramienta resuelva mal las referencias, y el
        // sintoma es un modelo que se abre con relaciones apuntando a donde no corresponde.
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(ids).hasSizeGreaterThan(20);
    }

    @Test
    @DisplayName("Las clases se anidan dentro de su uml:Package")
    void lasClasesVivenEnSuPaquete() throws Exception {
        Document doc = parsear(exportador.exportar(leer("reference-diagrams/inventario.json"), "X"));

        NodeList elementos = doc.getElementsByTagName("packagedElement");
        boolean hayClaseDentroDePaquete = false;
        for (int i = 0; i < elementos.getLength(); i++) {
            Element e = (Element) elementos.item(i);
            if ("uml:Class".equals(e.getAttributeNS(XmiExporterService.NS_XMI, "type"))
                    && e.getParentNode() instanceof Element padre
                    && "uml:Package".equals(
                            padre.getAttributeNS(XmiExporterService.NS_XMI, "type"))) {
                hayClaseDentroDePaquete = true;
            }
        }
        assertThat(hayClaseDentroDePaquete)
                .as("una herramienta que lee UML espera las clases dentro de su paquete")
                .isTrue();
    }

    @Test
    @DisplayName("Toda referencia apunta a un xmi:id que existe en el documento")
    void lasReferenciasResuelven() throws Exception {
        Document doc = parsear(exportador.exportar(leer("reference-diagrams/barberia.json"), "X"));

        java.util.Set<String> ids = new java.util.HashSet<>();
        NodeList todos = doc.getElementsByTagName("*");
        for (int i = 0; i < todos.getLength(); i++) {
            String id = ((Element) todos.item(i))
                    .getAttributeNS(XmiExporterService.NS_XMI, "id");
            if (!id.isBlank()) {
                ids.add(id);
            }
        }
        // Una referencia colgada es lo que hace que otra herramienta abra el archivo y muestre
        // un modelo incompleto sin decir por qué.
        for (int i = 0; i < todos.getLength(); i++) {
            Element e = (Element) todos.item(i);
            // Sólo la parte UML estándar. Dentro de CUALQUIER xmi:Extension el atributo "type"
            // significa lo que decida su extender y no es una referencia: en la nuestra es el
            // tipo canónico ("UUID") y en la de Enterprise Architect es la clase de elemento
            // ("Class", "Logical"). La regla de referencias aplica a lo que otra herramienta va
            // a intentar resolver — el UML estándar—, no al contenido privado de una extensión.
            if (dentroDeUnaExtension(e)) {
                continue;
            }
            for (String atributo : List.of("type", "general", "client", "supplier")) {
                String valor = e.getAttribute(atributo);
                if (!valor.isBlank() && !valor.startsWith("uml:") && !valor.contains("#")) {
                    assertThat(ids)
                            .as("%s=%s en <%s> no apunta a ningún xmi:id",
                                    atributo, valor, e.getNodeName())
                            .contains(valor);
                }
            }
            String idref = e.getAttributeNS(XmiExporterService.NS_XMI, "idref");
            if (!idref.isBlank()) {
                assertThat(ids).as("xmi:idref=%s colgado", idref).contains(idref);
            }
        }
    }

    @Test
    @DisplayName("El mismo diagrama produce siempre el mismo archivo")
    void laExportacionEsDeterminista() throws Exception {
        CanonicalModel modelo = leer("reference-diagrams/barberia.json");
        // Sin esto, dos exportaciones del mismo diagrama darían archivos distintos y no se
        // podrían comparar ni versionar.
        assertThat(exportador.exportar(modelo, "X")).isEqualTo(exportador.exportar(modelo, "X"));
    }

    // ---------------------------------- importar lo que NO salio de aca

    @Test
    @DisplayName("Un XMI ajeno (sin nuestra extensión) se importa parcial, con los defaults de §7")
    void xmiAjenoSeImportaParcial() {
        // Es lo que produciría Enterprise Architect: UML estándar y su propia extensión, que no
        // sabemos leer. Se recupera la estructura; posiciones y tipos exactos no viajan.
        String ajeno = """
                <?xml version="1.0" encoding="UTF-8"?>
                <xmi:XMI xmi:version="2.1" xmlns:xmi="http://schema.omg.org/spec/XMI/2.1"
                         xmlns:uml="http://schema.omg.org/spec/UML/2.1">
                  <uml:Model xmi:type="uml:Model" xmi:id="m1" name="De otra herramienta">
                    <packagedElement xmi:type="uml:Class" xmi:id="c1" name="Cliente">
                      <ownedAttribute xmi:type="uml:Property" xmi:id="a1" name="nombre">
                        <type xmi:type="uml:PrimitiveType" href="uml.xml#String"/>
                      </ownedAttribute>
                    </packagedElement>
                    <packagedElement xmi:type="uml:Class" xmi:id="c2" name="Pedido"/>
                    <packagedElement xmi:type="uml:Association" xmi:id="r1">
                      <ownedEnd xmi:type="uml:Property" xmi:id="e1" type="c2" aggregation="composite">
                        <upperValue xmi:type="uml:LiteralUnlimitedNatural" xmi:id="u1" value="*"/>
                      </ownedEnd>
                      <ownedEnd xmi:type="uml:Property" xmi:id="e2" type="c1" aggregation="none"/>
                    </packagedElement>
                  </uml:Model>
                  <xmi:Extension extender="Enterprise Architect">
                    <elements><element idref="c1" xy="100,200"/></elements>
                  </xmi:Extension>
                </xmi:XMI>
                """;

        CanonicalModel modelo = importador.importar(ajeno.getBytes(StandardCharsets.UTF_8));

        assertThat(modelo.classes()).extracting(c -> c.name()).containsExactly("Cliente", "Pedido");
        assertThat(modelo.relationships()).singleElement()
                .satisfies(r -> {
                    assertThat(r.type()).isEqualTo(RelationshipType.COMPOSITION);
                    assertThat(r.sourceClassId()).isEqualTo("c1");
                    assertThat(r.targetClassId()).isEqualTo("c2");
                });
        // Sin posiciones en el archivo, las clases entran en grilla en vez de apiladas en 0,0.
        assertThat(modelo.classes().get(0).position()).isNotNull();
        assertThat(modelo.classes().get(1).position().x())
                .isNotEqualTo(modelo.classes().get(0).position().x());
        // Y los defaults de §7 se aplican solos.
        assertThat(modelo.classes().get(0).width()).isEqualTo(240d);
    }

    @Test
    @DisplayName("Un XML que no es XMI se rechaza con 400, no con una traza")
    void archivoQueNoEsXmi() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> importador.importar("no es xml".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> importador.importar(
                        "<?xml version=\"1.0\"?><vacio/>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("ninguna clase");
    }

    @Test
    @DisplayName("Un XMI con entidades externas no lee archivos del servidor (XXE)")
    void noHayXxe() {
        // El endpoint acepta archivos de cualquiera: sin esto, un XMI con una entidad externa
        // podria hacer que el servidor leyera /etc/passwd y lo devolviera dentro del diagrama.
        String conEntidad = """
                <?xml version="1.0"?>
                <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <xmi:XMI xmlns:xmi="http://schema.omg.org/spec/XMI/2.1">&xxe;</xmi:XMI>
                """;

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> importador.importar(conEntidad.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    /** Si el elemento cuelga de una {@code xmi:Extension}, sea de quien sea. */
    private static boolean dentroDeUnaExtension(org.w3c.dom.Node nodo) {
        for (org.w3c.dom.Node n = nodo; n != null; n = n.getParentNode()) {
            if ("Extension".equals(n.getLocalName())) {
                return true;
            }
        }
        return false;
    }

    private static Document parsear(String xml) throws Exception {
        var fabrica = DocumentBuilderFactory.newInstance();
        fabrica.setNamespaceAware(true);
        return fabrica.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
