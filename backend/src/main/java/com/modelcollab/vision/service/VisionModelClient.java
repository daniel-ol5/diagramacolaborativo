package com.modelcollab.vision.service;

/**
 * La frontera con el modelo multimodal. Devuelve <b>texto crudo</b>, igual que en CU16 y por la
 * misma razón: si devolviera un objeto ya armado, una respuesta malformada no se podría ni
 * representar y por lo tanto tampoco probar.
 *
 * <p>La imagen entra como bytes y no como archivo: el archivo no se guarda en ningún lado, así
 * que no hay ninguna ruta que pasar.
 */
public interface VisionModelClient {

    /**
     * @param imagen los bytes de la foto
     * @param tipoMime el tipo ya comprobado contra el contenido real, no el que declaró el cliente
     * @return el texto que devolvió el modelo, sin tocar
     */
    String leer(byte[] imagen, String tipoMime);

    String descripcion();

    boolean esSimulado();
}
