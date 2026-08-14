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

# Uploads when STORAGE_PROVIDER=LOCAL. Mount a volume here, or use S3 in prod —
# container-local writes are lost on redeploy.
ENV STORAGE_LOCAL_DIR=/var/uploads
USER root
RUN mkdir -p /var/uploads && chown app:app /var/uploads
USER app
VOLUME ["/var/uploads"]

EXPOSE 8080

# Readiness, not the aggregate /actuator/health: the aggregate includes third-party
# indicators (mail, etc.) whose failure would mark an otherwise-serving container
# unhealthy. Readiness is scoped to the database in application-prod.properties.
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
