# Runtime image for one backend service. Build the jars first with `mvn clean install`;
# docker-compose.yml builds this once per module by passing MODULE.
FROM eclipse-temurin:21-jre
ARG MODULE
WORKDIR /app
COPY ${MODULE}/target/${MODULE}-0.0.1-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
