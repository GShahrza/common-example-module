# syntax=docker/dockerfile:1

# ---- build stage: compiles the Spring Boot jar (no local Java/Gradle needed) ----
FROM gradle:8.14.3-jdk21 AS build
WORKDIR /workspace
COPY . .
# The cache mount keeps downloaded dependencies between builds
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle :mvc-streaming:bootJar --no-daemon -x test

# ---- runtime stage: only a JRE and the jar ----
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app app
COPY --from=build /workspace/mvc-streaming/build/libs/mvc-streaming-1.0.0.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
