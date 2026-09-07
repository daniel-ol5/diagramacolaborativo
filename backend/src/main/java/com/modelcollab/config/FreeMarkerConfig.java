package com.modelcollab.config;

import freemarker.template.Configuration;
import freemarker.template.TemplateExceptionHandler;
import org.springframework.context.annotation.Bean;

/**
 * FreeMarker configurado para <b>generar archivos fuente</b>, no para renderizar páginas web.
 *
 * <p>La diferencia manda en dos ajustes:
 *
 * <ul>
 *   <li>{@code RETHROW_HANDLER}: ante cualquier error de plantilla se lanza la excepción en vez
 *       de escribir el mensaje en la salida. En una página web un error incrustado se ve feo;
 *       en un archivo {@code .java} produce un archivo roto que el usuario descubre recién al
 *       compilar, y para entonces ya no hay rastro de qué plantilla falló.</li>
 *   <li>{@code setLogTemplateExceptions(false)}: sin esto cada fallo se registra dos veces, una
 *       por FreeMarker y otra por quien atrapa la excepción.</li>
 * </ul>
 *
 * <p>No se usa {@code spring-boot-starter-freemarker} justamente porque configura además un
 * {@code ViewResolver} para vistas MVC, que este proyecto no tiene.
 */
@org.springframework.context.annotation.Configuration
public class FreeMarkerConfig {

    public static final String RUTA_DE_PLANTILLAS = "/templates/springboot";

    @Bean
    public Configuration freemarkerConfiguration() {
        Configuration configuration = new Configuration(Configuration.VERSION_2_3_34);
        configuration.setClassForTemplateLoading(FreeMarkerConfig.class, RUTA_DE_PLANTILLAS);
        configuration.setDefaultEncoding("UTF-8");
        configuration.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        configuration.setLogTemplateExceptions(false);
        configuration.setWrapUncheckedExceptions(true);
        // Un ${campo} nulo tiene que fallar, no imprimir "null" dentro del código generado.
        configuration.setFallbackOnNullLoopVariable(false);
        return configuration;
    }
}
