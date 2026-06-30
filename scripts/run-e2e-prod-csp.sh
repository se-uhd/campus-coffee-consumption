#!/usr/bin/env bash
# Boot the PRODUCTION Angular build under the prod Spring profile and prod Content-Security-Policy, then run
# only the prod-csp Playwright smoke (frontend/e2e/prod-csp.spec.ts). This is the one check that exercises the
# production SPA under the strict prod CSP in a real browser; a backend test cannot catch a CSP or
# inline-critical-CSS regression. Opt-in (a dedicated CI job, not part of `gradle build`).
#
# Prerequisites (NOT provisioned here):
#   - A PostgreSQL reachable at localhost:5432 (user/password postgres).
#   - Node on PATH (run via `mise exec --`), Playwright's chromium installed (`npx playwright install`).
#
# Env knobs:
#   APP_PORT    the port the jar listens on (default 8081, matching the Playwright baseURL; passed to the prod
#               jar via --server.port since the prod profile itself does not default to :8081).
#   SKIP_BUILD  if "1", reuse the existing application/build/libs jar instead of rebuilding it.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"

APP_PORT="${APP_PORT:-8081}"
JAR="${ROOT}/application/build/libs/application.jar"
HEALTH_URL="http://localhost:${APP_PORT}/actuator/health"
LOG="${ROOT}/application/build/prod-csp-app.log"

log() { echo "[e2e-prod-csp] $*" >&2; }
run_node() {
  if command -v mise >/dev/null 2>&1; then mise exec -- "$@"; else "$@"; fi
}

# --- 1. Build the PRODUCTION SPA + jar ----------------------------------------------------------------
# bootJar runs the production frontend build (npm run build, inlineCritical:false) and bundles it; do NOT pass
# -PskipFrontendBuild, so the jar carries the real production SPA (not the coverage build).
if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
  log "Assembling the application jar with the production SPA (gradle :application:bootJar)…"
  gradle :application:bootJar --console=plain
else
  log "SKIP_BUILD=1: reusing the existing jar at ${JAR}"
fi
[[ -f "$JAR" ]] || { log "ERROR: jar not found at ${JAR}"; exit 1; }

# --- 2. Fresh prod secrets (NOT the committed dev fallbacks, which WeakDevSecretGuard refuses) ---------
export SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL:-jdbc:postgresql://localhost:${DB_PORT:-5432}/postgres}"
export SPRING_DATASOURCE_USERNAME="${SPRING_DATASOURCE_USERNAME:-postgres}"
export SPRING_DATASOURCE_PASSWORD="${SPRING_DATASOURCE_PASSWORD:-postgres}"
JWT_SECRET="$(openssl rand -hex 32)"; export JWT_SECRET
LOGIN_PRIVATE_KEY_PEM="$(openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 2>/dev/null)"
export LOGIN_PRIVATE_KEY_PEM
# a public https origin so PublicBaseUrlGuard accepts the boot; the browser still connects to localhost
export CAMPUS_COFFEE_APP_BASE_URL="${CAMPUS_COFFEE_APP_BASE_URL:-https://coffee.example.com}"

# --- 3. Launch the jar under the prod profile ---------------------------------------------------------
log "Starting the app under --spring.profiles.active=prod…"
java -jar "$JAR" --spring.profiles.active=prod --server.port="${APP_PORT}" > "$LOG" 2>&1 &
APP_PID=$!
log "App PID ${APP_PID}; logs at ${LOG}"

cleanup() {
  if kill -0 "$APP_PID" 2>/dev/null; then
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
  if curl -fsS "$HEALTH_URL" >/dev/null 2>&1; then log "App is healthy."; break; fi
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    log "ERROR: the app exited before becoming healthy; tail of the log:"; tail -40 "$LOG" >&2; exit 1
  fi
  sleep 2
done
curl -fsS "$HEALTH_URL" >/dev/null 2>&1 || { log "ERROR: app never became healthy"; tail -40 "$LOG" >&2; exit 1; }

# --- 5. Run only the prod-csp smoke -------------------------------------------------------------------
log "Running the prod-csp Playwright smoke…"
E2E_STATUS=0
( cd frontend && CI=1 PW_PROD_CSP=1 run_node npm run e2e -- --grep @prod-csp ) || E2E_STATUS=$?

cleanup
trap - EXIT
exit "$E2E_STATUS"
