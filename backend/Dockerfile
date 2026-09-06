# Build multi-etapa: compila con el Maven Wrapper (Maven no está en el PATH del equipo
# de desarrollo, así que la imagen usa la misma vía que el flujo local).
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

COPY mvnw mvnw.cmd pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline

COPY src src
RUN ./mvnw -B clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN apk add --no-cache wget
COPY --from=build /app/target/backend-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

# NO EJECUTADO NUNCA (no hay Docker en la maquina de desarrollo, ver D-01).
# Revisado el 06/09/2026: nombre/ruta del jar (backend-0.0.1-SNAPSHOT.jar) y
# JDK 21 coinciden con pom.xml. Primera corrida real prevista para el
# despliegue en AWS del 13/09.
