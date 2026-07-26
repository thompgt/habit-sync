# Build stage — runs the Gradle wrapper against a full JDK.
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /src

# Copy build definitions first so dependency resolution caches independently of
# source changes. Editing a .java file must not invalidate the dependency layer.
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts ./
COPY sync-core/build.gradle.kts ./sync-core/
COPY server/build.gradle.kts ./server/
RUN chmod +x gradlew && ./gradlew :server:dependencies --no-daemon -q || true

COPY sync-core/src ./sync-core/src
COPY server/src ./server/src
RUN ./gradlew :server:bootJar --no-daemon -x test

# Runtime stage — JRE only, non-root.
FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=build /src/server/build/libs/*.jar app.jar
USER app
EXPOSE 8080

# MaxRAMPercentage rather than a fixed -Xmx so the container adapts to whatever
# memory limit the orchestrator imposes.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
