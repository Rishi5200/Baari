# Baari images (PLAN Phase 9): build with the Maven wrapper, run on a JRE as a non-root user,
# with the Spring Boot jar split into layers so a code change only rebuilds the small top layer.
#
#   docker build -t baari-app .                       the application (default, last stage)
#   docker build --target driver -t baari-driver .     the demo activity driver (simulator "drive")

# ---- build ----------------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Dependencies first: this layer is cached until a pom changes.
COPY mvnw ./
COPY .mvn .mvn
COPY pom.xml ./
COPY baari-app/pom.xml baari-app/
COPY baari-simulator/pom.xml baari-simulator/
RUN chmod +x mvnw && ./mvnw -B -ntp -q dependency:go-offline

COPY baari-simulator/src baari-simulator/src
COPY baari-app/src baari-app/src
# Tests run in CI (they need Docker for Testcontainers); the image build only packages.
RUN ./mvnw -B -ntp -q package -DskipTests -Djacoco.skip=true \
    && cp baari-app/target/baari-app-*.jar app.jar \
    && cp baari-simulator/target/baari-simulator-*.jar simulator.jar \
    && java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# ---- demo driver ------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre AS driver
RUN groupadd --system baari && useradd --system --gid baari --no-create-home baari
WORKDIR /driver
COPY --from=build /src/simulator.jar ./
COPY sim/demo-clinic.json ./
USER baari
ENTRYPOINT ["java", "-cp", "simulator.jar", "com.baari.simulator.SimulatorMain", "drive", "--config", "demo-clinic.json"]

# ---- app (default) --------------------------------------------------------------------------
FROM eclipse-temurin:21-jre
RUN groupadd --system baari && useradd --system --gid baari --no-create-home baari
WORKDIR /app
COPY --from=build /src/extracted/dependencies/ ./
COPY --from=build /src/extracted/spring-boot-loader/ ./
COPY --from=build /src/extracted/snapshot-dependencies/ ./
COPY --from=build /src/extracted/application/ ./
USER baari

# 8080: the app (behind Caddy). 8086: health and metrics, for the compose network only.
EXPOSE 8080 8086
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
