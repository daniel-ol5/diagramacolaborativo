package com.modelcollab.generator.service;

import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.service.IdentifierSanitizer;
import freemarker.template.Configuration;
import freemarker.template.Template;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Genera el proyecto Spring Boot a partir de un diagrama (§11.1): las <b>cinco capas</b> —
 * entidades JPA, repositorios, DTO con mapper, servicios transaccionales y controladores REST—
 * más el esqueleto del proyecto.
 *
 * <p>Devuelve un mapa de <em>ruta relativa -> contenido</em> en lugar de escribir en disco. Eso
 * deja que el empaquetador ZIP del Día 11 y el test de compilación usen exactamente el mismo
 * código: si el generador escribiera archivos por su cuenta, el test tendría que verificar algo
 * distinto de lo que el usuario descarga, que es la forma más fácil de que la garantía de "100%
 * compila" valga sólo para el test.
 */
@Service
public class SpringBootGeneratorService {

    private final Configuration freemarker;
    private final ModeloDeGeneracion modelador;

    public SpringBootGeneratorService(Configuration freemarker, IdentifierSanitizer sanitizer) {
        this.freemarker = freemarker;
        this.modelador = new ModeloDeGeneracion(sanitizer);
    }

    /**
     * @param paquete paquete base del proyecto generado, por ejemplo {@code com.empresa.app}
     * @return ruta relativa dentro del proyecto -> contenido del archivo
     */
    public Map<String, String> generar(CanonicalModel modelo, String paquete, String artefacto) {
        Map<String, String> archivos = new LinkedHashMap<>();
        String rutaDelPaquete = "src/main/java/" + paquete.replace('.', '/');

        List<Map<String, Object>> entidades = modelador.entidades(modelo, paquete);

        for (Map<String, Object> entidad : entidades) {
            String clase = (String) entidad.get("clase");
            archivos.put(
                    rutaDelPaquete + "/domain/model/" + clase + ".java",
                    render("entity.ftl", entidad));

            // Las clases abstractas no llevan repositorio ni CRUD: no se instancian ni se
            // consultan por sí solas, se llega a ellas a través de sus subclases. Generarles un
            // controlador seria exponer una entidad que no se puede crear.
            if (!Boolean.TRUE.equals(entidad.get("esAbstracta"))) {
                archivos.put(
                        rutaDelPaquete + "/repository/" + clase + "Repository.java",
                        render("repository.ftl", modeloDeRepositorio(entidad)));
                archivos.put(
                        rutaDelPaquete + "/service/dto/" + clase + "RequestDTO.java",
                        render("request-dto.ftl", entidad));
                archivos.put(
                        rutaDelPaquete + "/service/dto/" + clase + "ResponseDTO.java",
                        render("response-dto.ftl", entidad));
                archivos.put(
                        rutaDelPaquete + "/service/dto/mapper/" + clase + "Mapper.java",
                        render("mapper.ftl", entidad));
                archivos.put(
                        rutaDelPaquete + "/service/" + clase + "Service.java",
                        render("service.ftl", entidad));
                archivos.put(
                        rutaDelPaquete + "/service/impl/" + clase + "ServiceImpl.java",
                        render("service-impl.ftl", entidad));
                archivos.put(
                        rutaDelPaquete + "/controller/" + clase + "Controller.java",
                        render("controller.ftl", entidad));
            }
        }

        Map<String, Object> proyecto = new LinkedHashMap<>();
        proyecto.put("paquete", paquete);
        proyecto.put("artefacto", artefacto);
        archivos.put(rutaDelPaquete + "/Application.java", render("application.ftl", proyecto));
        archivos.put(rutaDelPaquete + "/config/GlobalExceptionHandler.java",
                render("exception-handler.ftl", proyecto));
        archivos.put("pom.xml", render("pom.ftl", proyecto));
        archivos.put("src/main/resources/application.yml", render("application-yml.ftl", proyecto));
        archivos.put("README.md", render("readme.ftl", proyecto));
        archivos.putAll(mavenWrapper());

        return archivos;
    }

    /**
     * El proyecto generado se lleva su propio Maven Wrapper.
     *
     * <p>El criterio del Día 11 es <i>"descargar el ZIP y compilarlo con {@code mvn clean
     * compile}"</i>, y eso da por sentado que quien lo recibe tiene Maven instalado. No es cierto
     * ni siquiera en la máquina donde se desarrolla esto —por eso el propio ModelCollab usa el
     * wrapper—, así que sin esto la invariante "100% compila" no se puede verificar acá ni
     * cumplir allá. Con el wrapper, un {@code ./mvnw clean compile} funciona sobre una máquina
     * que sólo tenga un JDK.
     *
     * <p>Los archivos se copian tal cual, sin pasar por FreeMarker: son scripts de shell llenos
     * de {@code ${...}} propios, que la plantilla intentaría interpolar.
     */
    private Map<String, String> mavenWrapper() {
        return Map.of(
                "mvnw", recurso("mvnw"),
                "mvnw.cmd", recurso("mvnw.cmd"),
                ".mvn/wrapper/maven-wrapper.properties", recurso("maven-wrapper.properties"));
    }

    private String recurso(String nombre) {
        String ruta = "/generator/wrapper/" + nombre;
        try (var in = SpringBootGeneratorService.class.getResourceAsStream(ruta)) {
            if (in == null) {
                throw new IllegalStateException("Falta el recurso " + ruta);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("No se pudo leer " + ruta, e);
        }
    }

    private Map<String, Object> modeloDeRepositorio(Map<String, Object> entidad) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("paquete", entidad.get("paquete"));
        r.put("clase", entidad.get("clase"));
        r.put("nombreOriginal", entidad.get("nombreOriginal"));
        r.put("tipoDelId", entidad.get("tipoDelIdEfectivo"));
        r.put("importeDelId", entidad.get("importeDelIdEfectivo"));
        return r;
    }

    private String render(String plantilla, Object modelo) {
        try {
            Template template = freemarker.getTemplate(plantilla);
            StringWriter salida = new StringWriter();
            template.process(modelo, salida);
            // FreeMarker respeta los saltos de línea de la plantilla; se normalizan a LF para que
            // el mismo diagrama produzca archivos idénticos en Windows y en Linux, y el ZIP sea
            // reproducible.
            return salida.toString().replace("\r\n", "\n");
        } catch (Exception e) {
            // Una plantilla rota no puede producir un archivo a medias: el usuario lo
            // descubriría al compilar, sin ninguna pista de qué falló.
            throw new IllegalStateException(
                    "No se pudo renderizar la plantilla " + plantilla + ": " + e.getMessage(), e);
        }
    }
}
