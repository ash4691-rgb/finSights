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
COPY --from=build /app/target/portfolio-api-0.1.0.jar app.jar
# Render assigns $PORT at runtime; application.yml already reads it (server.port: ${PORT:8080}).
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
