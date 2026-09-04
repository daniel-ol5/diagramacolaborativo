package com.modelcollab.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * ObjectMapper único del sistema. Todo lo que serialice o deserialice el modelo
 * canónico (REST, STOMP, JSONB, XMI, generadores y tests) debe usar esta configuración:
 * si dos capas usan mappers distintos, el contrato de §7 deja de estar congelado.
 */
@Configuration
public class JacksonConfig {

    /**
     * Construye el mapper canónico. Se expone también como método estático para que los
     * tests unitarios no necesiten levantar el contexto de Spring.
     */
    public static ObjectMapper canonicalObjectMapper() {
        return JsonMapper.builder()
                .addModule(new ParameterNamesModule())
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                // Estricto a propósito: un campo desconocido significa que alguien se
                // desvió del schema canónico y queremos enterarnos en el acto.
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return canonicalObjectMapper();
    }
}
