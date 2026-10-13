# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml mvnw ./
COPY .mvn .mvn
RUN sed -i 's/\r$//' mvnw
RUN --mount=type=cache,target=/root/.m2 sh ./mvnw -B -ntp dependency:go-offline
COPY src/main src/main
# Database tests are run separately against a dedicated test database.
RUN --mount=type=cache,target=/root/.m2 sh ./mvnw -B -ntp -Dmaven.test.skip=true package

FROM eclipse-temurin:17-jre-jammy AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home banking
WORKDIR /app
COPY --from=build --chown=banking:banking /workspace/target/banking-api-service-*.jar /app/app.jar
USER banking
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD curl --fail --silent http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
