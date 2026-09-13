package com.modelcollab.vision.service;

import java.util.List;

/**
 * El lector simulado de pizarras: devuelve siempre la misma lectura, sin mirar la foto.
 *
 * <h2>No mira la imagen, y no puede disimularlo</h2>
 *
 * <p>Reconocer una pizarra escrita a mano no se aproxima con reglas: o hay un modelo multimodal o
 * no hay nada. Así que este cliente <b>no intenta parecer</b> que lee. Devuelve un borrador fijo
 * y la respuesta del endpoint viaja con {@code simulated = true} para que la interfaz lo diga.
 *
 * <p>La tentación era derivar el borrador del hash de la imagen, para que dos fotos distintas
 * dieran resultados distintos y la demostración quedara más vistosa. Es exactamente lo que no hay
 * que hacer: pareceria un reconocimiento que no ocurrió, y una demostración que engaña al que
 * mira no es una demostración. Con el borrador fijo y el aviso, lo que se demuestra es verdad —
 * el circuito completo de revisión y confirmación, que es lo nuestro— y lo que falta se ve que
 * falta.
 *
 * <h2>Por qué la lectura simulada trae errores a propósito</h2>
 *
 * <p>Uno de los tipos es ilegible y una de las líneas va a una clase que no está. Es lo que pasa
 * de verdad con una foto de pizarra, y es lo que hace que el modal de revisión tenga algo que
 * mostrar: si la lectura simulada fuera perfecta, el circuito <i>human-in-the-loop</i> se
 * demostraría con la persona apretando "aceptar" sin nada que revisar, que es justamente el
 * human-in-the-loop de mentira que hay que evitar.
 */
public class FakeVisionModelClient implements VisionModelClient {

    /**
     * Una pizarra de pedidos, con sus imperfecciones. {@code "decmal"} no es un descuido de
     * tipeo: es el tipo que el validador va a convertir a VARCHAR con su aviso. Y la línea a
     * "Deposito" apunta a una clase que no está dibujada, como una flecha que se sale del borde
     * de la foto.
     */
    private static final String LECTURA_FIJA = """
        {"classes":[
          {"name":"Pedido","attributes":[
            {"name":"numero","type":"VARCHAR"},
            {"name":"fecha","type":"DATE"},
            {"name":"total","type":"decmal"}]},
          {"name":"ItemPedido","attributes":[
            {"name":"cantidad","type":"INTEGER"},
            {"name":"precioUnitario","type":"DECIMAL"}]},
          {"name":"Producto","attributes":[
            {"name":"nombre","type":"VARCHAR"},
            {"name":"stock","type":"INTEGER"}]}],
         "relationships":[
          {"from":"Pedido","to":"ItemPedido","type":"COMPOSITION",
           "sourceMultiplicity":"1..1","targetMultiplicity":"1..*"},
          {"from":"ItemPedido","to":"Producto","type":"ASSOCIATION",
           "sourceMultiplicity":"0..*","targetMultiplicity":"1..1"},
          {"from":"Producto","to":"Deposito","type":"ASSOCIATION",
           "sourceMultiplicity":"0..*","targetMultiplicity":"1..1"}]}
        """;

    @Override
    public String leer(byte[] imagen, String tipoMime) {
        return LECTURA_FIJA;
    }

    @Override
    public String descripcion() {
        return "simulado (lectura fija, no analiza la imagen)";
    }

    @Override
    public boolean esSimulado() {
        return true;
    }

    /** Lo que la interfaz le tiene que decir a la persona antes de que revise nada. */
    public static String aviso() {
        return "Sin clave de IA configurada: esta lectura es un ejemplo fijo y NO corresponde a "
                + "la imagen que subiste. Sirve para probar el circuito de revisión.";
    }

    /** Los tipos que el simulado deja mal a propósito, para que el modal tenga qué mostrar. */
    public static List<String> imperfeccionesDeliberadas() {
        return List.of("decmal", "Deposito");
    }
}
