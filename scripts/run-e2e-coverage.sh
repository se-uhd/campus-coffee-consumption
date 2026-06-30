#!/usr/bin/env bash
# Run the Playwright end-to-end suite against the real Spring Boot jar with TWO coverage collectors wired:
#
#   1. Backend JVM coverage (JaCoCo): the jar is launched under the JaCoCo agent, so every line the e2e
#      HTTP traffic exercises is recorded into coverage/build/jacoco/e2e.exec. The :coverage Gradle gate
#      picks that .exec up (see coverage/build.gradle.kts) and merges it into the aggregate coverage gate.
#   2. Frontend (browser) coverage: the SPA is built with source maps and the Playwright run is started
#      with PW_COVERAGE=1, so the per-test fixture captures Chromium V8 coverage and the global teardown
#      writes a source-mapped lcov report under frontend/coverage-e2e/ (coverage JaCoCo can never see).
#
# Prerequisites (the script does NOT provision these):
#   - A PostgreSQL reachable at localhost:5432 (user/password postgres), the dev profile's datasource.
#   - Node on PATH (run via `mise exec --`), Playwright's chromium installed (`npx playwright install`).
#   - The application jar built with the source-mapped ("coverage") SPA, which this script builds.
#   - The JaCoCo agent jar path passed as $JACOCO_AGENT_JAR (the :coverage Gradle task resolves and passes
#     it); if unset, the script tries to locate it under the Gradle/Maven caches as a fallback.
#
# Env knobs:
#   JACOCO_AGENT_JAR   absolute path to org.jacoco.agent-<ver>-runtime.jar (required in CI; auto-detected
#                      locally if unset).
#   APP_PORT           the port the jar listens on (default 8081, the dev profile's port; the Playwright baseURL is :8081).
#   SKIP_BUILD         if "1", skip the SPA+jar build and reuse the existing application/build/libs jar
#                      (useful when iterating; the jar must already be source-mapped + agent-launchable).
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"

APP_PORT="${APP_PORT:-8081}"
EXEC_OUT="${ROOT}/coverage/build/jacoco/e2e.exec"
JAR="${ROOT}/application/build/libs/application.jar"
HEALTH_URL="http://localhost:${APP_PORT}/actuator/health"

log() { echo "[e2e-coverage] $*" >&2; }

run_node() {
  # Prefer mise's Node so the version matches the toolchain; fall back to PATH node in CI images that
  # already activated mise.
  if command -v mise >/dev/null 2>&1; then
    mise exec -- "$@"
  else
    "$@"
  fi
}

# --- 1. Build the source-mapped SPA and the jar -------------------------------------------------------
if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
  log "Building the source-mapped SPA (npm run build:coverage)…"
  # Install deps only when missing: CI runs `npm ci` before invoking this script, so re-running it here just
  # repeats a slow install. Locally a present node_modules is reused.
  ( cd frontend && { [[ -d node_modules ]] || run_node npm ci; } && run_node npm run build:coverage )

  log "Assembling the application jar (gradle :application:bootJar -PskipFrontendBuild)…"
  # bootJar copies frontend/dist/frontend/browser into the jar's static resources. We pass
  # -PskipFrontendBuild so bootJar does NOT re-run the production SPA build and overwrite the
  # source-mapped one we just produced (the application build script honors this flag; see below).
  gradle :application:bootJar -PskipFrontendBuild --console=plain
else
  log "SKIP_BUILD=1: reusing the existing jar at ${JAR}"
fi

[[ -f "$JAR" ]] || { log "ERROR: jar not found at ${JAR}"; exit 1; }

# --- 2. Resolve the JaCoCo agent jar ------------------------------------------------------------------
if [[ -z "${JACOCO_AGENT_JAR:-}" ]]; then
  log "JACOCO_AGENT_JAR unset: searching the Gradle cache for the agent runtime jar…"
  JACOCO_AGENT_JAR="$(find "${HOME}/.gradle/caches" -name 'org.jacoco.agent-*-runtime.jar' 2>/dev/null | sort | tail -1 || true)"
fi
[[ -n "${JACOCO_AGENT_JAR}" && -f "${JACOCO_AGENT_JAR}" ]] || {
  log "ERROR: could not resolve the JaCoCo agent jar (set JACOCO_AGENT_JAR)"; exit 1; }
log "Using JaCoCo agent: ${JACOCO_AGENT_JAR}"

mkdir -p "$(dirname "$EXEC_OUT")"
rm -f "$EXEC_OUT"

# --- 3. Launch the jar under the JaCoCo agent ---------------------------------------------------------
log "Starting the app under the JaCoCo agent (destfile=${EXEC_OUT})…"
java \
  "-javaagent:${JACOCO_AGENT_JAR}=destfile=${EXEC_OUT},output=file,append=false" \
  -jar "$JAR" \
  --spring.profiles.active=dev \
  --server.port="${APP_PORT}" \
  > "${ROOT}/coverage/build/jacoco/e2e-app.log" 2>&1 &
APP_PID=$!
log "App PID ${APP_PID}; logs at coverage/build/jacoco/e2e-app.log"

# Always stop the app so the JaCoCo agent flushes e2e.exec, even on a failing e2e run.
cleanup() {
  if kill -0 "$APP_PID" 2>/dev/null; then
    log "Stopping the app (PID ${APP_PID}) so the agent flushes ${EXEC_OUT}…"
    # SIGTERM lets Spring shut down gracefully; the agent's shutdown hook writes the exec on exit.
    kill -TERM "$APP_PID" 2>/dev/null || true
    for _ in $(seq 1 30); do kill -0 "$APP_PID" 2>/dev/null || break; sleep 1; done
    kill -KILL "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

# --- 4. Wait for health -------------------------------------------------------------------------------
log "Waiting for ${HEALTH_URL} …"
for _ in $(seq 1 60); do
  if curl -fsS "$HEALTH_URL" >/dev/null 2>&1; then
    log "App is healthy."
    break
  fi
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    log "ERROR: the app exited before becoming healthy; tail of the log:"; tail -40 "${ROOT}/coverage/build/jacoco/e2e-app.log" >&2; exit 1
  fi
  sleep 2
done
curl -fsS "$HEALTH_URL" >/dev/null 2>&1 || { log "ERROR: app never became healthy"; tail -40 "${ROOT}/coverage/build/jacoco/e2e-app.log" >&2; exit 1; }

# --- 5. Run the e2e with browser coverage on ----------------------------------------------------------
log "Running the Playwright e2e (PW_COVERAGE=1)…"
# Capture the e2e exit code without tripping `set -e`: a failing e2e must still fall through to the cleanup
# below (stop the app so the agent flushes the exec) and exit with the real e2e status. `|| E2E_STATUS=$?`
# keeps the failure from aborting the script while recording the code; a success leaves E2E_STATUS at 0.
E2E_STATUS=0
( cd frontend && PW_COVERAGE=1 CI=1 run_node npm run e2e ) || E2E_STATUS=$?

# --- 6. Stop the app (trap) so the agent flushes the exec ---------------------------------------------
cleanup
trap - EXIT

[[ -f "$EXEC_OUT" ]] && log "Wrote backend e2e coverage: ${EXEC_OUT}" || log "WARNING: ${EXEC_OUT} was not written"
[[ -f "${ROOT}/frontend/coverage-e2e/lcov.info" ]] && log "Wrote frontend e2e coverage: frontend/coverage-e2e/lcov.info" || true

exit "$E2E_STATUS"
