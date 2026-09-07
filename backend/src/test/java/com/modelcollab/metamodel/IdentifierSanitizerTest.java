package com.modelcollab.metamodel;

import static org.assertj.core.api.Assertions.assertThat;

import com.modelcollab.metamodel.service.IdentifierSanitizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * §11.2.1: las dos sanitizaciones son independientes.
 *
 * <p>Este test existe sobre todo para congelar esa separación. Un generador con una sola lista de
 * palabras reservadas parece funcionar hasta que aparece un diagrama con la clase {@code Order} o
 * el atributo {@code class} — y entonces falla por un lado o por el otro, nunca por los dos a la
 * vez, que es lo que hace que el error sobreviva a las pruebas superficiales.
 */
class IdentifierSanitizerTest {

    private final IdentifierSanitizer sanitizer = new IdentifierSanitizer();

    // ------------------------------- reservadas de SQL que son válidas en Java

    @ParameterizedTest(name = "la clase {0} conserva su nombre en Java")
    @ValueSource(strings = {"Order", "Group", "Table", "User", "Select"})
    @DisplayName("Una reservada de SQL NO cambia el nombre de la clase Java")
    void reservadaDeSqlNoTocaLaClase(String nombre) {
        // §11.2.1(a): se escapa en la anotación y se conserva el nombre original en Java.
        assertThat(sanitizer.nombreDeClase(nombre)).isEqualTo(nombre);
    }

    @Test
    @DisplayName("La tabla se escapa sólo si el nombre es reservado en PostgreSQL")
    void tablaEscapadaSoloSiHaceFalta() {
        // Las comillas van dentro del valor: el generador después lo vuelca como literal Java
        // escapado, produciendo @Table(name = "\\"order\\"") tal como muestra §11.2.1.
        assertThat(sanitizer.nombreDeTabla("Order")).isEqualTo("\"order\"");
        assertThat(sanitizer.nombreDeTabla("Group")).isEqualTo("\"group\"");
        assertThat(sanitizer.nombreDeTabla("Table")).isEqualTo("\"table\"");

        // Y no se escapa lo que no hace falta: identificadores citados de más obligan a citarlos
        // en cada consulta escrita a mano después.
        assertThat(sanitizer.nombreDeTabla("Cliente")).isEqualTo("cliente");
        assertThat(sanitizer.nombreDeTabla("MovimientoStock")).isEqualTo("movimiento_stock");
    }

    // ------------------------------- reservadas de Java que son válidas en SQL

    @ParameterizedTest(name = "el atributo {0} pasa a ser {1} en Java")
    @CsvSource({
        "class,   _class",
        "public,  _public",
        "int,     _int",
        "new,     _new",
        "total,   total",
        "nombreCompleto, nombreCompleto",
    })
    @DisplayName("Una reservada de Java se prefija con guion bajo (§11.2.1b)")
    void reservadaDeJavaSePrefija(String atributo, String esperado) {
        assertThat(sanitizer.nombreDeCampo(atributo)).isEqualTo(esperado);
    }

    @Test
    @DisplayName("'class' es reservada de Java pero NO de PostgreSQL: la columna va sin comillas")
    void classEsElCasoQueSeparaLasDosListas() {
        // Es exactamente el ejemplo de §11.2.1: @Column(name = "class"), sin escapar.
        assertThat(sanitizer.nombreDeCampo("class")).isEqualTo("_class");
        assertThat(sanitizer.nombreDeColumna("class")).isEqualTo("class");
        assertThat(sanitizer.esReservadaDeJava("class")).isTrue();
        assertThat(sanitizer.esReservadaDeSql("class")).isFalse();
    }

    @Test
    @DisplayName("'int' cae en las dos listas: se prefija en Java Y se escapa en SQL")
    void intCaeEnLasDos() {
        assertThat(sanitizer.nombreDeCampo("int")).isEqualTo("_int");
        assertThat(sanitizer.nombreDeColumna("int")).isEqualTo("\"int\"");
    }

    @Test
    @DisplayName("'Order' cae sólo en la de SQL: nombre de clase intacto, tabla escapada")
    void orderCaeSoloEnSql() {
        assertThat(sanitizer.esReservadaDeJava("Order")).isFalse();
        assertThat(sanitizer.esReservadaDeSql("Order")).isTrue();
        assertThat(sanitizer.nombreDeClase("Order")).isEqualTo("Order");
        assertThat(sanitizer.nombreDeTabla("Order")).isEqualTo("\"order\"");
    }

    // ----------------------------------------------------- formatos y bordes

    @ParameterizedTest(name = "{0} -> clase {1}")
    @CsvSource({
        "cliente,            Cliente",
        "movimiento stock,   MovimientoStock",
        "Detalle-de-Orden,   DetalleDeOrden",
        "'Órden Especial',   OrdenEspecial",
        "2Fase,              _2Fase",
    })
    @DisplayName("Los nombres con espacios, guiones o acentos se vuelven identificadores válidos")
    void nombresRaros(String original, String esperado) {
        // El modelador no tiene por qué conocer las reglas de Java para dibujar una clase.
        assertThat(sanitizer.nombreDeClase(original)).isEqualTo(esperado);
    }

    @ParameterizedTest(name = "{0} -> columna {1}")
    @CsvSource({
        "nombreCompleto,   nombre_completo",
        "fechaDeAlta,      fecha_de_alta",
        "total,            total",
        "IVA,              iva",
    })
    @DisplayName("Las columnas van en snake_case")
    void columnasEnSnakeCase(String original, String esperado) {
        assertThat(sanitizer.nombreDeColumna(original)).isEqualTo(esperado);
    }

    @Test
    @DisplayName("Una sigla no se convierte en algo ilegible")
    void siglas() {
        // "IVA" -> "iVA" seria valido pero horrible de leer en el codigo generado.
        assertThat(sanitizer.nombreDeCampo("IVA")).isEqualTo("IVA");
    }

    @Test
    @DisplayName("Un nombre vacío o de puro símbolo no rompe el generador")
    void nombresDegenerados() {
        assertThat(sanitizer.nombreDeClase("***")).isEqualTo("Entidad");
        assertThat(sanitizer.nombreDeCampo("")).isEqualTo("campo");
        assertThat(sanitizer.nombreDeColumna(null)).isEqualTo("campo");
    }

    @Test
    @DisplayName("La tabla intermedia de un M2M también se escapa si hace falta")
    void tablaIntermedia() {
        assertThat(sanitizer.nombreDeTablaIntermedia("Cita", "Servicio"))
                .isEqualTo("cita_servicio");
        assertThat(sanitizer.nombreDeTablaIntermedia("Order", "Table"))
                .isEqualTo("order_table");
    }

    @Test
    @DisplayName("El getter se arma sobre el nombre ya saneado")
    void getters() {
        assertThat(sanitizer.enMayusculaInicial(sanitizer.nombreDeCampo("class")))
                .isEqualTo("_class");
        assertThat(sanitizer.enMayusculaInicial(sanitizer.nombreDeCampo("total")))
                .isEqualTo("Total");
    }
}
