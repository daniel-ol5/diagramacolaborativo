package com.modelcollab;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BackendApplication {

    public static void main(String[] args) {
        cargarDotEnvSiExiste();
        SpringApplication.run(BackendApplication.class, args);
    }

    /**
     * Carga variables desde un archivo .env local si existe en el directorio de trabajo,
     * en deploy/.env o en la raíz del proyecto.
     *
     * <p>Sólo define las propiedades en {@link System#setProperty} si no existen ya en las
     * variables de entorno del sistema o en propiedades de la JVM. Esto permite poner las claves
     * en el .env sin tener que escribirlas en la terminal a cada corrida.
     */
    static void cargarDotEnvSiExiste() {
        List<Path> posiblesRutas = List.of(
                Path.of(".env"),
                Path.of("deploy/.env"),
                Path.of("../deploy/.env"),
                Path.of("../.env"));

        for (Path ruta : posiblesRutas) {
            if (Files.isRegularFile(ruta)) {
                try {
                    List<String> lineas = Files.readAllLines(ruta, StandardCharsets.UTF_8);
                    for (String linea : lineas) {
                        String limpia = linea.strip();
                        if (limpia.isEmpty() || limpia.startsWith("#")) {
                            continue;
                        }
                        int separador = limpia.indexOf('=');
                        if (separador > 0) {
                            String clave = limpia.substring(0, separador).strip();
                            String valor = limpia.substring(separador + 1).strip();
                            if ((valor.startsWith("\"") && valor.endsWith("\""))
                                    || (valor.startsWith("'") && valor.endsWith("'"))) {
                                valor = valor.substring(1, valor.length() - 1);
                            }
                            if (System.getenv(clave) == null && System.getProperty(clave) == null) {
                                System.setProperty(clave, valor);
                            }
                        }
                    }
                    break;
                } catch (IOException ignored) {
                    // Si no se puede leer, continúa con la configuración estándar
                }
            }
        }
    }
}
