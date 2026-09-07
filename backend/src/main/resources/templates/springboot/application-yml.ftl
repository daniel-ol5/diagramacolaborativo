spring:
  application:
    name: ${artefacto}
  datasource:
    url: ${r"${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/"}${artefacto}}
    username: ${r"${SPRING_DATASOURCE_USERNAME:postgres}"}
    password: ${r"${SPRING_DATASOURCE_PASSWORD:postgres}"}
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      # update crea las tablas a partir de las entidades la primera vez. Para producción
      # conviene pasar a validate y versionar el esquema con migraciones.
      ddl-auto: update
    open-in-view: false
    properties:
      hibernate:
        format_sql: true

server:
  port: 8080
