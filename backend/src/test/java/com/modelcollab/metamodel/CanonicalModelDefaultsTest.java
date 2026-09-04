package com.modelcollab.metamodel;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.config.JacksonConfig;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.Multiplicity;
import com.modelcollab.metamodel.model.OwningSide;
import com.modelcollab.metamodel.model.Visibility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifica que los defaults declarados en §7 se apliquen al deserializar cuando el
 * campo viene ausente. El lienzo web manda payloads mínimos: si el backend no
 * normaliza aquí, el generador de código recibe nulls.
 */
class CanonicalModelDefaultsTest {

    private final ObjectMapper mapper = JacksonConfig.canonicalObjectMapper();

    @Test
    @DisplayName("Los campos ausentes toman el default del schema canónico")
    void aplicaDefaultsDelSchema() throws Exception {
        String json = """
                {
                  "schemaVersion": "1.0.0",
                  "mutationVersion": 1,
                  "classes": [
                    {
                      "id": "11111111-1111-4111-8111-111111111111",
                      "name": "Minima",
                      "position": { "x": 0, "y": 0 },
                      "attributes": [
                        {
                          "id": "22222222-2222-4222-8222-222222222222",
                          "name": "codigo",
                          "type": "VARCHAR"
                        }
                      ]
                    }
                  ],
                  "relationships": [
                    {
                      "id": "33333333-3333-4333-8333-333333333333",
                      "sourceClassId": "11111111-1111-4111-8111-111111111111",
                      "targetClassId": "11111111-1111-4111-8111-111111111111",
                      "type": "ASSOCIATION"
                    }
                  ]
                }
                """;

        CanonicalModel modelo = mapper.readValue(json, CanonicalModel.class);

        assertThat(modelo.packages()).isEmpty();

        var clase = modelo.classes().getFirst();
        assertThat(clase.width()).isEqualTo(240d);
        assertThat(clase.height()).isEqualTo(180d);
        assertThat(clase.isAbstract()).isFalse();
        assertThat(clase.visibility()).isEqualTo(Visibility.PUBLIC);
        assertThat(clase.methods()).isEmpty();

        var atributo = clase.attributes().getFirst();
        assertThat(atributo.length()).isEqualTo(255);
        assertThat(atributo.precision()).isEqualTo(10);
        assertThat(atributo.scale()).isEqualTo(2);
        assertThat(atributo.isPrimaryKey()).isFalse();
        assertThat(atributo.isNullable()).isTrue();
        assertThat(atributo.isUnique()).isFalse();
        assertThat(atributo.defaultValue()).isNull();

        var relacion = modelo.relationships().getFirst();
        assertThat(relacion.owningSide()).isEqualTo(OwningSide.SOURCE);
        assertThat(relacion.isNavigable()).isTrue();
        assertThat(relacion.waypoints()).isEmpty();
        assertThat(relacion.joinTableName()).isNull();
    }

    @Test
    @DisplayName("Las multiplicidades usan los literales UML del schema, no el nombre de la constante")
    void multiplicidadSerializaConLiteralUml() throws Exception {
        assertThat(mapper.writeValueAsString(Multiplicity.ZERO_MANY)).isEqualTo("\"0..*\"");
        assertThat(mapper.readValue("\"1..*\"", Multiplicity.class))
                .isEqualTo(Multiplicity.ONE_MANY);
        assertThat(Multiplicity.ONE_ONE.isCollection()).isFalse();
        assertThat(Multiplicity.ZERO_MANY.isCollection()).isTrue();
    }
}
