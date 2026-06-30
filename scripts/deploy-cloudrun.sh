#!/usr/bin/env bash
#
# One-command deploy of CampusCoffeeConsumption to Google Cloud Run, against managed Cloud SQL.
#
# Secrets are authored locally in deploy.prod.env (gitignored, the source of truth) and SYNCED into Google
# Secret Manager by this script; the running service reads them from Secret Manager, bound as environment
# variables with --set-secrets. So the editable copy stays on the operator's machine while the runtime gets
# Secret Manager's encryption at rest, access audit, and versioning, and no secret rides in the build context.
# Non-secret config (the base URL, DB user, and bootstrap-admin identity) also comes from deploy.prod.env and
# is passed with --set-env-vars.
#
# This deploys with `gcloud run deploy` rather than `gcloud beta run compose up`: compose up cannot bind a
# Secret Manager secret as an environment variable (its `secrets:` only uploads a local file), and the prod
# profile fails fast without the secrets, so a compose-up revision would crash before they could be bound.
#
# Usage:
#   scripts/deploy-cloudrun.sh        # reads deploy.prod.env (copy deploy.env.example to start one)
#
# One-time prerequisites:
#   gcloud auth login
#   gcloud config set project <your-project-id>
#   gcloud config set run/region <region>            # e.g. europe-west3
#   gcloud services enable run.googleapis.com cloudbuild.googleapis.com secretmanager.googleapis.com sqladmin.googleapis.com
#   # A Cloud SQL Postgres instance whose connection name you set as CLOUD_SQL_INSTANCE in deploy.prod.env.
#   # The prod profile connects
#   # through the Cloud SQL socket factory (Admin API + IAM), so no --add-cloudsql-instances mount is needed.
#   # Grant the Cloud Run runtime service account BOTH:
#   #   roles/cloudsql.client                (connect through the socket factory)
#   #   roles/secretmanager.secretAccessor   (read the secrets bound below)

set -euo pipefail
umask 077
cd "$(dirname "$0")/.."

env_file="deploy.prod.env"
service="campus-coffee-consumption-prod"
[ -f "$env_file" ] || {
  echo "$env_file not found. Copy deploy.env.example to $env_file and fill it in." >&2
  exit 1
}

# Read a single-line value from deploy.prod.env without sourcing it (the multi-line PEM cannot be sourced).
val() { grep "^$1=" "$env_file" | head -1 | cut -d= -f2-; }
# Read the multi-line LOGIN_PRIVATE_KEY_PEM value (from after the '=' on its line through the END marker).
login_key() {
  awk '/^LOGIN_PRIVATE_KEY_PEM=/{sub(/^LOGIN_PRIVATE_KEY_PEM=/,"");p=1} p{print} /-----END PRIVATE KEY-----/{exit}' "$env_file"
}

db_username="$(val DB_USERNAME)"
cloud_sql_instance="$(val CLOUD_SQL_INSTANCE)"
base_url="$(val CAMPUS_COFFEE_APP_BASE_URL)"
admin_login="$(val BOOTSTRAP_ADMIN_LOGIN)"
admin_email="$(val BOOTSTRAP_ADMIN_EMAIL)"
admin_first="$(val BOOTSTRAP_ADMIN_FIRST_NAME)"
admin_last="$(val BOOTSTRAP_ADMIN_LAST_NAME)"

# --- sync the four secrets from deploy.prod.env into Secret Manager ----------------------------------------
# Create the secret if missing; add a new version only when the deploy.prod.env value differs from the current
# latest, so re-deploys do not churn versions. Values flow through a temp file (umask 077) and are never echoed.
sync_secret() { # secret-name; value on stdin
  local name="$1" tmp
  tmp="$(mktemp)"
  cat >"$tmp"
  if ! gcloud secrets describe "$name" >/dev/null 2>&1; then
    gcloud secrets create "$name" --data-file="$tmp" --replication-policy=automatic >/dev/null
    echo "  created $name"
  elif ! gcloud secrets versions access latest --secret="$name" 2>/dev/null | cmp -s - "$tmp"; then
    gcloud secrets versions add "$name" --data-file="$tmp" >/dev/null
    echo "  updated $name (new version)"
  else
    echo "  $name unchanged"
  fi
  rm -f "$tmp"
}

echo "Syncing secrets from ${env_file} to Secret Manager..."
printf '%s' "$(val JWT_SECRET)" | sync_secret jwt-secret
login_key | sync_secret login-key
printf '%s' "$(val DB_PASSWORD)" | sync_secret db-app-password
printf '%s' "$(val BOOTSTRAP_ADMIN_PASSWORD)" | sync_secret bootstrap-admin-password

# --- deploy: build from source, bind the secrets and non-secret config ------------------------------------
secrets="JWT_SECRET=jwt-secret:latest"
secrets+=",LOGIN_PRIVATE_KEY_PEM=login-key:latest"
secrets+=",DB_PASSWORD=db-app-password:latest"
secrets+=",BOOTSTRAP_ADMIN_PASSWORD=bootstrap-admin-password:latest"

env_vars="SPRING_PROFILES_ACTIVE=prod"
env_vars+=",DB_USERNAME=${db_username}"
env_vars+=",CLOUD_SQL_INSTANCE=${cloud_sql_instance}"
env_vars+=",CAMPUS_COFFEE_APP_BASE_URL=${base_url}"
env_vars+=",BOOTSTRAP_ADMIN_LOGIN=${admin_login}"
env_vars+=",BOOTSTRAP_ADMIN_EMAIL=${admin_email}"
env_vars+=",BOOTSTRAP_ADMIN_FIRST_NAME=${admin_first}"
env_vars+=",BOOTSTRAP_ADMIN_LAST_NAME=${admin_last}"

echo "Deploying ${service} to Cloud Run (building from source)..."
gcloud run deploy "$service" \
  --source . \
  --allow-unauthenticated \
  --set-secrets="$secrets" \
  --set-env-vars="$env_vars" \
  --memory=1Gi \
  --cpu=1 \
  --concurrency=80 \
  --max-instances="${MAX_INSTANCES:-4}" \
  --cpu-boost

# --- resolve the real service URL and write it back if it is still the placeholder -------------------------
url="$(gcloud run services describe "$service" --format='value(status.url)')"
if [ "$base_url" = "https://pending.invalid" ]; then
  echo "Resolved ${url}; writing it to ${env_file} and updating the service..."
  tmp="$(mktemp)"
  while IFS= read -r line; do
    case "$line" in
      CAMPUS_COFFEE_APP_BASE_URL=*) printf 'CAMPUS_COFFEE_APP_BASE_URL=%s\n' "$url" ;;
      *) printf '%s\n' "$line" ;;
    esac
  done <"$env_file" >"$tmp"
  mv "$tmp" "$env_file"
  chmod 600 "$env_file"
  gcloud run services update "$service" --update-env-vars="CAMPUS_COFFEE_APP_BASE_URL=${url}"
fi

echo "Service URL: ${url}"
