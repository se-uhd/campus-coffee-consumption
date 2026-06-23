# Build stage: mise installs the JDK and Gradle from mise.toml.
FROM jdxcode/mise:2026.6.10 AS build
WORKDIR /app
# Disable the gcloud/python entries in mise.toml. The build doesn't need them, and `mise exec` below
# would otherwise auto-install gcloud, whose install script fails here (it needs python on PATH, but
# mise installs dependencies in parallel).
ENV MISE_DISABLE_TOOLS=gcloud,python
# Copy mise.toml first so the install layer stays cached until a tool version changes.
COPY mise.toml .
RUN mise trust && mise install
COPY . .
RUN mise exec -- gradle :application:bootJar --no-daemon

# Runtime stage. The Java major version in the tag below is hand-pinned. A CI check
# (scripts/check-toolchain-versions.sh) fails the build if it drifts from mise.toml and the version catalog.
FROM eclipse-temurin:25-jre-alpine
# Run as a dedicated non-root user (baseline container hardening; the jar needs no write access to /opt/app).
RUN addgroup -S app && adduser -S -G app app && mkdir /opt/app && chown app:app /opt/app
COPY --from=build --chown=app:app /app/application/build/libs/application.jar /opt/app
WORKDIR /opt/app
USER app
EXPOSE 8080
# Report unhealthy if the actuator health endpoint (public) is not UP, so a started-but-degraded app is
# visible to the orchestrator rather than reporting running.
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -q -O - http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "-jar", "application.jar"]
