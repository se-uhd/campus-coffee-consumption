#!/usr/bin/env bash
#
# One-command deploy of CampusCoffeeConsumption (prod profile) to Google Cloud Run, against managed Cloud SQL.
#
# Usage:
#   DB_PASSWORD=... scripts/deploy-cloudrun.sh        # first deploy (DB_PASSWORD is the Cloud SQL password)
#   scripts/deploy-cloudrun.sh                        # later deploys (reuses deploy.env)
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

service="campus-coffee-consumption-prod"

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

echo "Deploying ${service} to Cloud Run..."
gcloud beta run compose up compose.prod.yaml --allow-unauthenticated

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
  gcloud beta run compose up compose.prod.yaml --allow-unauthenticated
fi

echo "Service URL: ${url}"
