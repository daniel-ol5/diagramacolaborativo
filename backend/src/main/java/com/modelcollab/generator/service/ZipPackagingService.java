package com.modelcollab.generator.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.springframework.stereotype.Service;

/**
 * Empaqueta los archivos generados en un ZIP descargable.
 *
 * <p>Trabaja en memoria y no sobre disco. Un proyecto generado son unas decenas de archivos de
 * texto —los tres dominios de referencia dan menos de 100 KB comprimidos—, así que escribir en un
 * temporal sólo agregaría limpieza que hacer y un modo de fallo nuevo: el archivo que queda
 * huérfano cuando la petición se cancela a la mitad.
 */
@Service
public class ZipPackagingService {

    /**
     * El {@code mvnw} tiene que salir del ZIP <b>ejecutable</b>. Sin este bit, quien lo
     * descomprima en Linux o macOS se topa con {@code ./mvnw: Permission denied} en el primer
     * comando del README — y enviar el wrapper para que no haga falta instalar Maven queda a
     * medias justo en la plataforma donde más importa.
     *
     * <p>Es la razón de usar Commons Compress: {@code java.util.zip} no sabe escribir los
     * permisos POSIX de una entrada.
     */
    private static final int PERMISOS_EJECUTABLE = 0100755;
    private static final int PERMISOS_NORMAL = 0100644;

    public byte[] empaquetar(Map<String, String> archivos) {
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(salida)) {
            zip.setEncoding(StandardCharsets.UTF_8.name());
            for (Map.Entry<String, String> archivo : archivos.entrySet()) {
                ZipArchiveEntry entrada = new ZipArchiveEntry(archivo.getKey());
                entrada.setUnixMode(esEjecutable(archivo.getKey())
                        ? PERMISOS_EJECUTABLE
                        : PERMISOS_NORMAL);
                zip.putArchiveEntry(entrada);
                zip.write(archivo.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeArchiveEntry();
            }
        } catch (IOException e) {
            // Escribir en memoria no puede fallar por E/S; si pasa, algo está muy mal.
            throw new IllegalStateException("No se pudo armar el ZIP del proyecto generado", e);
        }
        return salida.toByteArray();
    }

    private static boolean esEjecutable(String ruta) {
        return "mvnw".equals(ruta);
    }
}
