# ── Stage 1: Build ──────────────────────────────────────────────────────────
# eclipse-temurin:17-jdk supports linux/amd64 and linux/arm64 (Apple Silicon)
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

# Copy Maven wrapper and pom first (layer cache for dependencies)
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
RUN ./mvnw dependency:go-offline -q

# Copy source and build (skip tests — tests use in-memory store, no Redis needed)
COPY src src
RUN ./mvnw package -DskipTests -q

# ── Stage 2: Runtime ─────────────────────────────────────────────────────────
# eclipse-temurin:17-jre supports linux/amd64 and linux/arm64
FROM eclipse-temurin:17-jre
WORKDIR /app

# Non-root user for security
RUN groupadd -r schemaguard && useradd -r -g schemaguard schemaguard
USER schemaguard

COPY --from=build /app/target/SchemaGuard-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
