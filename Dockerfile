# ---------- build stage ----------
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace

# Cache dependencies first
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline

# Build
COPY src/ src/
RUN ./mvnw -q -B -DskipTests package \
    && mkdir -p target/extracted \
    && java -Djarmode=layertools -jar target/*.jar extract --destination target/extracted

# ---------- runtime stage ----------
FROM eclipse-temurin:17-jre-jammy

# Run as a non-root user. UID/GID are pinned rather than auto-assigned so a host
# directory bind-mounted for uploads can be chowned to a known owner — otherwise
# the container writes as an unpredictable system UID and fails with EACCES.
RUN groupadd --system --gid 1001 app \
    && useradd --system --uid 1001 --gid 1001 --create-home --home /home/app app
USER app
WORKDIR /app

# Copy Spring Boot layers (deps change least → app code most)
COPY --from=build --chown=app:app /workspace/target/extracted/dependencies/         ./
COPY --from=build --chown=app:app /workspace/target/extracted/spring-boot-loader/   ./
COPY --from=build --chown=app:app /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /workspace/target/extracted/application/          ./

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

# Deployed images run production config unless explicitly overridden. Critically this
# keeps classpath:db/dev (seed ADMIN/SUPER_ADMIN accounts) off the Flyway path.
ENV SPRING_PROFILES_ACTIVE=prod

# Uploads. LOCAL is the only implemented storage provider, so this directory IS the
# media store and a host volume must be mounted on it — anything written to the
# container's own layer is gone on the next redeploy. /opt/uploads is the one path the
# image default, the VOLUME and both compose mounts agree on; changing it in only one
# place is how uploads silently disappear.
ENV STORAGE_LOCAL_DIR=/opt/uploads
USER root
RUN mkdir -p /opt/uploads && chown app:app /opt/uploads
USER app
VOLUME ["/opt/uploads"]

EXPOSE 8080

# Readiness, not the aggregate /actuator/health: the aggregate includes third-party
# indicators (mail, etc.) whose failure would mark an otherwise-serving container
# unhealthy. Readiness is scoped to the database in application-prod.properties.
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
