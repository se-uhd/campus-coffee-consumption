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
RUN mkdir /opt/app
COPY --from=build /app/application/build/libs/application.jar /opt/app
WORKDIR /opt/app
ENTRYPOINT ["java", "-jar", "application.jar"]
EXPOSE 8080
