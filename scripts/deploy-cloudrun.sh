#!/usr/bin/env bash
#
# One-command deploy of CampusCoffeeConsumption to Google Cloud Run, against managed Cloud SQL.
#
# Usage:
#   DB_PASSWORD=... scripts/deploy-cloudrun.sh [compose-file]   # first deploy (DB_PASSWORD = Cloud SQL password)
#   scripts/deploy-cloudrun.sh [compose-file]                   # later deploys (reuses deploy.env)
#
# compose-file defaults to compose.prod.yaml (the prod profile, real members, no seed data). Pass
# compose.demo.yaml for the public demo (the prod,demo profiles: prod hardening + the fixture/demo seed data,
# cleared and reseeded each boot); set MAX_INSTANCES=1 for it so the boot-time reset runs on one instance,
# and MIN_INSTANCES=1 to keep that instance always warm (no cold start, no idle-wake reseed).
#
# One-time prerequisites:
#   gcloud auth login
#   gcloud config set project <your-project-id>
#   gcloud config set run/region <region>            # e.g. europe-west3
#   gcloud components install beta
#   # a Cloud SQL Postgres instance named in application.yaml's prod datasource URL, the Cloud SQL Admin API
#   # enabled, and the Cloud Run runtime service account granted the Cloud SQL Client role
#
# `gcloud run compose up` has no flag to set environment variables, so all secrets and the public URL are
# supplied through the Compose file's `env_file: deploy.env`. This script generates deploy.env on first run
# (a random JWT secret and a random bootstrap-admin password, printed once) and reuses it afterwards, so
# JWTs and the admin credential survive a redeploy. deploy.env is gitignored and kept out of the build
# context. The public base URL is a chicken-and-egg with Cloud Run's assigned URL, so the first run deploys
# once to learn the URL, writes it back, and redeploys. `--allow-unauthenticated` grants public invocation;
# app-level authentication still gates every write.

set -euo pipefail

cd "$(dirname "$0")/.."

# The compose file selects the deployment: compose.prod.yaml (default) or compose.demo.yaml. The Cloud Run
# service name is the Compose project `name:`.
compose="${1:-compose.prod.yaml}"
[ -f "$compose" ] || { echo "compose file not found: $compose" >&2; exit 1; }
service="$(sed -n 's/^name: *//p' "$compose" | head -1)"
[ -n "$service" ] || { echo "could not read 'name:' from $compose" >&2; exit 1; }

# --- deploy.env: created once, reused afterwards ------------------------------------------------------------
if [[ ! -f deploy.env ]]; then
  : "${DB_PASSWORD:?Set DB_PASSWORD to your Cloud SQL password for the first deploy, e.g. DB_PASSWORD=... $0}"
  admin_password="$(openssl rand -hex 16)"
  {
    printf 'JWT_SECRET=%s\n' "$(openssl rand -hex 32)"
    printf 'DB_PASSWORD=%s\n' "$DB_PASSWORD"
    printf 'CAMPUS_COFFEE_APP_BASE_URL=%s\n' "https://pending.invalid"
    printf 'BOOTSTRAP_ADMIN_LOGIN=%s\n' "admin"
    printf 'BOOTSTRAP_ADMIN_PASSWORD=%s\n' "$admin_password"
    printf 'BOOTSTRAP_ADMIN_EMAIL=%s\n' "admin@se.uni-heidelberg.de"
  } > deploy.env
  echo "Generated deploy.env (gitignored)."
  echo "  First-admin login:    admin"
  echo "  First-admin password: $admin_password   (printed once; store it now)"
fi

echo "Deploying ${service} from ${compose} to Cloud Run..."
gcloud beta run compose up "$compose" --allow-unauthenticated

# --- resolve the real service URL and redeploy if the base URL is still the placeholder --------------------
url="$(gcloud run services describe "$service" --format='value(status.url)')"
if grep -q 'CAMPUS_COFFEE_APP_BASE_URL=https://pending.invalid' deploy.env; then
  echo "Setting CAMPUS_COFFEE_APP_BASE_URL=${url} and redeploying so capability QR links are correct..."
  # portable in-place edit (no GNU/BSD sed -i divergence): rewrite the one line
  tmp="$(mktemp)"
  while IFS= read -r line; do
    case "$line" in
      CAMPUS_COFFEE_APP_BASE_URL=*) printf 'CAMPUS_COFFEE_APP_BASE_URL=%s\n' "$url" ;;
      *) printf '%s\n' "$line" ;;
    esac
  done < deploy.env > "$tmp"
  mv "$tmp" deploy.env
  gcloud beta run compose up "$compose" --allow-unauthenticated
fi

# Optional single-instance cap. The demo resets the database on boot, so it must run exactly one instance
# (two instances resetting at once would race); pass MAX_INSTANCES=1 for it.
if [[ -n "${MAX_INSTANCES:-}" ]]; then
  echo "Capping ${service} at ${MAX_INSTANCES} instance(s)..."
  gcloud run services update "$service" --max-instances="$MAX_INSTANCES"
fi

# Optional warm floor. With the default min of 0 the service scales to zero when idle, so the next request
# cold-starts (and, for the demo, reruns the boot-time reseed). Pass MIN_INSTANCES=1 to keep one instance
# always warm (it bills continuously). For the demo, pair it with MAX_INSTANCES=1.
if [[ -n "${MIN_INSTANCES:-}" ]]; then
  echo "Keeping ${service} warm at a floor of ${MIN_INSTANCES} instance(s)..."
  gcloud run services update "$service" --min-instances="$MIN_INSTANCES"
fi

echo "Service URL: ${url}"
