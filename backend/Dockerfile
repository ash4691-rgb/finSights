# Multi-stage build — Render (and most container hosts) need a Dockerfile for Java;
# there's no native JVM runtime the way there is for Node/Python/Go.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -q dependency:go-offline
COPY src/ src/
RUN ./mvnw -q clean package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
# eclipse-temurin runs as root by default (flagged by SonarQube) — drop to an unprivileged
# user for the actual running container. The build stage above is fine as root: it's
# discarded and never ships, only this final stage's filesystem/user become the running image.
RUN groupadd --system spring && useradd --system --gid spring --no-create-home spring
COPY --from=build --chown=spring:spring /app/target/portfolio-api-0.1.0.jar app.jar
USER spring
# Render assigns $PORT at runtime; application.yml already reads it (server.port: ${PORT:8080}).
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
