#!/usr/bin/env bash
#
# One-command deploy of CampusCoffeeConsumption to Google Cloud Run, against managed Cloud SQL.
#
# Usage:
#   DB_PASSWORD=... scripts/deploy-cloudrun.sh [compose-file]   # first deploy (DB_PASSWORD = Cloud SQL password)
#   scripts/deploy-cloudrun.sh [compose-file]                   # later deploys (reuses deploy.env)
#
# compose-file defaults to compose.prod.yaml (the prod profile, real members, no seed data). The service
# scales to zero when idle; the startup CPU boost (below) shortens the resulting cold start.
#
# One-time prerequisites:
#   gcloud auth login
#   gcloud config set project <your-project-id>
#   gcloud config set run/region <region>            # e.g. europe-west3
#   gcloud components install beta
#   # a Cloud SQL Postgres instance named in application.yaml's prod datasource URL, the Cloud SQL Admin API
#   # enabled, and the Cloud Run runtime service account granted the Cloud SQL Client role
#   # the least-privilege app role provisioned once (scripts/sql/create-app-role.sql), so the service runs as
#   # campus_coffee_app rather than the Cloud SQL master postgres superuser
#
# Hardening: deploy.env is the simple path, but the three secrets (JWT_SECRET, LOGIN_PRIVATE_KEY_PEM,
# DB_PASSWORD) are better held in Secret Manager and bound after the compose deploy with
#   gcloud run services update "$service" --set-secrets=DB_PASSWORD=db-app-password:latest,JWT_SECRET=jwt-secret:latest,LOGIN_PRIVATE_KEY_PEM=login-key:latest
# so they never sit in a plaintext file or the build context.
#
# `gcloud run compose up` has no flag to set environment variables, so all secrets and the public URL are
# supplied through the Compose file's `env_file: deploy.env`. This script generates deploy.env on first run
# (a random JWT secret and a random bootstrap-admin password, printed once) and reuses it afterwards, so
# JWTs and the admin credential survive a redeploy. deploy.env is gitignored and kept out of the build
# context. The public base URL is a chicken-and-egg with Cloud Run's assigned URL, so the first run deploys
# once to learn the URL, writes it back, and redeploys. `--allow-unauthenticated` grants public invocation;
# app-level authentication still gates every write.

set -euo pipefail

# deploy.env carries the JWT secret, the RSA login key, the DB password, and the admin credential, so every
# file this script creates (deploy.env and the mktemp work file) must be owner-only. umask 077 makes that the
# default; an explicit chmod below is the belt-and-suspenders.
umask 077

cd "$(dirname "$0")/.."

# The compose file selects the deployment (compose.prod.yaml by default). The Cloud Run
# service name is the Compose project `name:`.
compose="${1:-compose.prod.yaml}"
[ -f "$compose" ] || { echo "compose file not found: $compose" >&2; exit 1; }
service="$(sed -n 's/^name: *//p' "$compose" | head -1)"
[ -n "$service" ] || { echo "could not read 'name:' from $compose" >&2; exit 1; }

# --- deploy.env: created once, reused afterwards ------------------------------------------------------------
if [[ ! -f deploy.env ]]; then
  : "${DB_PASSWORD:?Set DB_PASSWORD to your Cloud SQL password for the first deploy, e.g. DB_PASSWORD=... $0}"
  admin_password="$(openssl rand -hex 16)"
  # The login-payload RSA key as a single line with literal \n separators (an env_file value cannot span
  # lines); the app turns the \n back into real newlines.
  login_private_key_pem="$(openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 2>/dev/null \
    | awk 'BEGIN{ORS="\\n"}{print}' | sed 's/\\n$//')"
  {
    printf 'JWT_SECRET=%s\n' "$(openssl rand -hex 32)"
    printf 'LOGIN_PRIVATE_KEY_PEM=%s\n' "$login_private_key_pem"
    printf 'DB_USERNAME=%s\n' "${DB_USERNAME:-campus_coffee_app}"
    printf 'DB_PASSWORD=%s\n' "$DB_PASSWORD"
    printf 'CAMPUS_COFFEE_APP_BASE_URL=%s\n' "https://pending.invalid"
    printf 'BOOTSTRAP_ADMIN_LOGIN=%s\n' "admin"
    printf 'BOOTSTRAP_ADMIN_PASSWORD=%s\n' "$admin_password"
    printf 'BOOTSTRAP_ADMIN_EMAIL=%s\n' "admin@se.uni-heidelberg.de"
  } > deploy.env
  chmod 600 deploy.env
  echo "Generated deploy.env (gitignored, mode 600)."
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

# Set the runtime resource limits and the startup CPU boost in one update. `gcloud beta run compose up` has
# no flags for these, so they are applied here after the deploy.
#   --memory/--cpu       the per-instance limits; 1Gi pairs with the JVM's MaxRAMPercentage=75 (Dockerfile).
#   --concurrency        requests served per instance before a new one starts.
#   --max-instances      caps fan-out (and the Cloud SQL connection count); override with MAX_INSTANCES.
#   --cpu-boost          extra CPU during container startup so the JVM and Spring context boot faster,
#                        shortening the scale-to-zero cold start. Not separately billed.
# The service still scales to zero when idle (min-instances stays the default 0).
max_instances="${MAX_INSTANCES:-4}"
echo "Setting resource limits (memory 1Gi, cpu 1, concurrency 80, max-instances ${max_instances}) and startup CPU boost on ${service}..."
gcloud run services update "$service" \
  --memory=1Gi \
  --cpu=1 \
  --concurrency=80 \
  --max-instances="$max_instances" \
  --cpu-boost

echo "Service URL: ${url}"
