#!/usr/bin/env bash
# Run the Playwright end-to-end suite against the real Spring Boot jar WITHOUT coverage instrumentation.
# This is the FAST path used as the per-push/PR gate: it bundles the production (minified) SPA, launches the
# jar plainly (no JaCoCo agent), and runs `npm run e2e` (no PW_COVERAGE, so no V8 capture or monocart
# teardown). The full-coverage variant (backend JaCoCo + frontend V8) is scripts/run-e2e-coverage.sh, which
# runs nightly.
#
# Prerequisites (the script does NOT provision these):
#   - A PostgreSQL reachable at localhost:5432 (user/password postgres), the dev profile's datasource.
#   - Node on PATH (run via `mise exec --`), Playwright's chromium installed (`npx playwright install`).
#
# Env knobs:
#   APP_PORT     the port the jar listens on (default 8080; the Playwright baseURL is :8080).
#   SKIP_BUILD   if "1", skip the SPA+jar build and reuse the existing application/build/libs jar.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"

APP_PORT="${APP_PORT:-8080}"
JAR="${ROOT}/application/build/libs/application.jar"
HEALTH_URL="http://localhost:${APP_PORT}/actuator/health"
LOG="${ROOT}/application/build/e2e-app.log"

log() { echo "[e2e] $*" >&2; }

run_node() {
  # Prefer mise's Node so the version matches the toolchain; fall back to PATH node in CI images that
  # already activated mise.
  if command -v mise >/dev/null 2>&1; then
    mise exec -- "$@"
  else
    "$@"
  fi
}

# --- 1. Build the production SPA and the jar ----------------------------------------------------------
# Build the production (minified) Angular bundle, then assemble the jar with -PskipFrontendBuild so bootJar
# bundles that bundle instead of re-running the frontend build. This mirrors run-e2e-coverage.sh and keeps
# the single (job- or guard-installed) npm ci, rather than letting bootJar's frontend task npm ci again.
if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
  log "Building the production SPA (npm run build)…"
  ( cd frontend && { [[ -d node_modules ]] || run_node npm ci; } && run_node npm run build )

  log "Assembling the application jar (gradle :application:bootJar -PskipFrontendBuild)…"
  gradle :application:bootJar -PskipFrontendBuild --console=plain
else
  log "SKIP_BUILD=1: reusing the existing jar at ${JAR}"
fi
[[ -f "$JAR" ]] || { log "ERROR: jar not found at ${JAR}"; exit 1; }

mkdir -p "$(dirname "$LOG")"

# --- 2. Launch the jar (dev profile, no coverage agent) ----------------------------------------------
# Skip the demo-data seeding: the suite resets to the 5-user fixtures per test, so it is wasted startup work.
log "Starting the app (dev profile)…"
java -jar "$JAR" --spring.profiles.active=dev --campus-coffee.fixtures.demo-data-on-startup=false \
  --server.port="${APP_PORT}" > "$LOG" 2>&1 &
APP_PID=$!
log "App PID ${APP_PID}; logs at application/build/e2e-app.log"

cleanup() {
  if kill -0 "$APP_PID" 2>/dev/null; then
    log "Stopping the app (PID ${APP_PID})…"
    kill -TERM "$APP_PID" 2>/dev/null || true
    for _ in $(seq 1 30); do kill -0 "$APP_PID" 2>/dev/null || break; sleep 1; done
    kill -KILL "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

# --- 3. Wait for health ------------------------------------------------------------------------------
log "Waiting for ${HEALTH_URL} …"
for _ in $(seq 1 60); do
  if curl -fsS "$HEALTH_URL" >/dev/null 2>&1; then
    log "App is healthy."
    break
  fi
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    log "ERROR: the app exited before becoming healthy; tail of the log:"; tail -40 "$LOG" >&2; exit 1
  fi
  sleep 1
done
curl -fsS "$HEALTH_URL" >/dev/null 2>&1 || { log "ERROR: app never became healthy"; tail -40 "$LOG" >&2; exit 1; }

# --- 4. Run the e2e (no coverage) --------------------------------------------------------------------
# Playwright reuses this already-running app (reuseExistingServer: true in playwright.config.ts).
log "Running the Playwright e2e (no coverage)…"
E2E_STATUS=0
( cd frontend && CI=1 run_node npm run e2e ) || E2E_STATUS=$?

cleanup
trap - EXIT
exit "$E2E_STATUS"
