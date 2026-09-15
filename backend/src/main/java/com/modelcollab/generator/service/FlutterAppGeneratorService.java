package com.modelcollab.generator.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.config.FreeMarkerConfig;
import com.modelcollab.generator.dto.IntentCatalog;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.service.IdentifierSanitizer;
import freemarker.template.Configuration;
import freemarker.template.Template;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * CU19: genera la app móvil Flutter con asistente de voz (RF-07, RF-08).
 *
 * <p>Devuelve un mapa de <em>ruta relativa → contenido</em>, igual que el generador de Spring
 * Boot y por la misma razón: así el arnés que corre {@code flutter analyze} y el ZIP que descarga
 * el usuario son <b>exactamente los mismos bytes</b>. Si el generador escribiera archivos por su
 * cuenta, el arnés estaría verificando algo distinto de lo que se entrega.
 *
 * <h2>El catálogo de intenciones viaja como dato, no como código</h2>
 *
 * <p>{@code assets/intents.json} se embarca como recurso y no se compila dentro de un
 * {@code .dart}. Es lo que pide §10.3 y además es lo correcto: el catálogo es lo único que cambia
 * cuando cambia el diagrama, y tenerlo separado permite regenerarlo sin volver a tocar el resto
 * de la app.
 */
@Service
public class FlutterAppGeneratorService {

    private final Configuration freemarker;
    private final NluIntentGeneratorService intenciones;
    private final IdentifierSanitizer sanitizer;
    private final ObjectMapper objectMapper;

    public FlutterAppGeneratorService(
            NluIntentGeneratorService intenciones,
            IdentifierSanitizer sanitizer,
            ObjectMapper objectMapper) {
        // Configuración propia apuntando a las plantillas de Flutter. No es un bean para no
        // dejar dos Configuration en el contexto, que volvería ambigua la inyección del
        // generador de Spring Boot.
        this.freemarker = FreeMarkerConfig.paraPlantillas(
                FreeMarkerConfig.RUTA_DE_PLANTILLAS_FLUTTER);
        this.intenciones = intenciones;
        this.sanitizer = sanitizer;
        this.objectMapper = objectMapper;
    }

    /**
     * Dirección que se usa si quien genera no especifica ninguna.
     *
     * <p>Es la del emulador de Android, no {@code localhost}, y la diferencia importa:
     * <b>{@code localhost} dentro de un teléfono es el teléfono</b>, no la máquina que corre el
     * backend. Una app generada con {@code localhost} no conecta ni estando las dos cosas en la
     * misma red. {@code 10.0.2.2} es el alias que el emulador de Android le da al host.
     */
    public static final String URL_POR_DEFECTO = "http://10.0.2.2:8080";

    /**
     * @param nombreDelDiagrama el nombre que ve el usuario, para los títulos de la app
     * @param urlDelBackend contra qué servidor habla la app generada
     * @return ruta relativa dentro del proyecto Flutter → contenido del archivo
     */
    public Map<String, String> generar(
            CanonicalModel modelo, String nombreDelDiagrama, String urlDelBackend) {

        String nombre = nombreDelDiagrama == null || nombreDelDiagrama.isBlank()
                ? "Asistente"
                : nombreDelDiagrama.strip();

        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("nombreDelDiagrama", nombre);
        datos.put("paquete", nombreDePaquete(nombre));
        datos.put("urlDelBackend", urlDeBackend(urlDelBackend));
        datos.put("entidades", entidades(modelo));

        Map<String, String> archivos = new LinkedHashMap<>();
        archivos.put("pubspec.yaml", render("pubspec.ftl", datos));
        archivos.put("analysis_options.yaml", render("analysis_options.ftl", datos));
        archivos.put("lib/main.dart", render("main.ftl", datos));
        archivos.put("lib/config.dart", render("config.ftl", datos));
        archivos.put("lib/nlu/catalogo.dart", render("catalogo.ftl", datos));
        archivos.put("lib/nlu/clasificador.dart", render("clasificador.ftl", datos));
        archivos.put("lib/nlu/extractor.dart", render("extractor.ftl", datos));
        archivos.put("lib/voz/servicio_de_voz.dart", render("voz.ftl", datos));
        archivos.put("lib/api/cliente_api.dart", render("api_cliente.ftl", datos));
        archivos.put("lib/datos/cola_local.dart", render("cola_local.ftl", datos));
        archivos.put("lib/ui/pantalla_dictado.dart", render("pantalla_dictado.ftl", datos));
        archivos.put("assets/intents.json", catalogo(modelo));
        return archivos;
    }

    /**
     * La dirección del backend, comprobada.
     *
     * <p><b>Es un parámetro de la generación y no una constante de la plantilla</b>, y eso es
     * una decisión, no un detalle. Quien descarga el ZIP tiene que poder decir contra qué
     * servidor habla su app — el suyo, el de AWS o el de su máquina para probar. Con la
     * dirección clavada en la plantilla, cambiar de servidor obliga a <b>regenerar y volver a
     * instalar</b>, que en un teléfono no es lo mismo que recargar una página.
     *
     * <p>Se rechaza lo que no sea una URL absoluta {@code http} o {@code https}. Una dirección
     * mal escrita no rompe la generación: rompe la app <b>en el teléfono del usuario final</b>,
     * con un error de red que no dice nada sobre su causa. Es barato comprobarlo acá.
     */
    private String urlDeBackend(String url) {
        String limpio = url == null || url.isBlank() ? URL_POR_DEFECTO : url.strip();
        // Sin barra final: las rutas se concatenan después y "http://x//api" no resuelve igual
        // en todos los servidores.
        while (limpio.endsWith("/")) {
            limpio = limpio.substring(0, limpio.length() - 1);
        }
        if (!limpio.startsWith("http://") && !limpio.startsWith("https://")) {
            throw new IllegalArgumentException(
                    "La dirección del backend tiene que empezar con http:// o https://, y vino: "
                            + limpio);
        }
        return limpio;
    }

    /**
     * El nombre del paquete Dart: minúsculas y guiones bajos.
     *
     * <p>Pasa por el saneador de identificadores y después baja a minúsculas porque un nombre de
     * paquete de Dart con mayúsculas o espacios <b>no es válido</b> y {@code pub get} falla antes
     * de resolver nada — y el nombre del diagrama lo escribe una persona: "Barbería Central"
     * tiene que dar algo instalable.
     */
    private String nombreDePaquete(String nombreDelDiagrama) {
        String limpio = sanitizer.nombreDeTabla(nombreDelDiagrama)
                .replace("\"", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]+", "_")
                .replaceAll("^_+|_+$", "");
        // Un paquete de Dart no puede empezar con un dígito.
        if (limpio.isBlank() || Character.isDigit(limpio.charAt(0))) {
            limpio = "app_" + limpio;
        }
        return limpio;
    }

    /**
     * Las entidades concretas con la ruta REST que expone el backend generado.
     *
     * <p>La ruta sale de {@link IdentifierSanitizer#rutaRest}, que es <b>la misma función</b> que
     * usa el generador de Spring Boot para su {@code @RequestMapping}. Calcularla acá por
     * separado sería tener la regla escrita dos veces, y el día que dejaran de coincidir el
     * síntoma no aparecería en ninguno de los dos proyectos: aparecería en el teléfono de un
     * usuario final como un 404 sin explicación.
     *
     * <p>Las abstractas quedan afuera porque el backend generado tampoco les da controlador: no
     * se instancian por sí solas.
     */
    private List<Map<String, String>> entidades(CanonicalModel modelo) {
        List<Map<String, String>> entidades = new java.util.ArrayList<>();
        for (com.modelcollab.metamodel.model.ClassEntity clase : modelo.classes()) {
            if (Boolean.TRUE.equals(clase.isAbstract())) {
                continue;
            }
            entidades.add(Map.of(
                    "nombre", clase.name(),
                    "ruta", sanitizer.rutaRest(clase.name())));
        }
        return entidades;
    }

    private String catalogo(CanonicalModel modelo) {
        IntentCatalog catalogo = intenciones.generar(modelo);
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(catalogo);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar el catálogo de intenciones", e);
        }
    }

    private String render(String plantilla, Map<String, Object> datos) {
        try {
            Template template = freemarker.getTemplate(plantilla);
            StringWriter salida = new StringWriter();
            template.process(datos, salida);
            return salida.toString();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "No se pudo generar " + plantilla + ": " + e.getMessage(), e);
        }
    }
}
