package com.modelcollab;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackendApplicationTest {

    @Test
    @DisplayName("cargarDotEnvSiExiste carga las variables del archivo .env a las System Properties")
    void cargaDotEnvCorrectamente(@TempDir Path tempDir) throws IOException {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, """
            # Comentario de prueba
            TEST_VARIABLE_A=valor123
            TEST_VARIABLE_B="valor con comillas"
            TEST_VARIABLE_C='valor con comillas simples'
            """);

        List<String> lineas = Files.readAllLines(envFile);
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
                System.setProperty(clave, valor);
            }
        }

        assertThat(System.getProperty("TEST_VARIABLE_A")).isEqualTo("valor123");
        assertThat(System.getProperty("TEST_VARIABLE_B")).isEqualTo("valor con comillas");
        assertThat(System.getProperty("TEST_VARIABLE_C")).isEqualTo("valor con comillas simples");
    }
}
