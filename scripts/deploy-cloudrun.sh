#!/usr/bin/env bash
#
# One-command deploy of CampusCoffeeConsumption (prod profile) to Google Cloud Run.
#
# Usage:
#   scripts/deploy-cloudrun.sh
#
# One-time prerequisites:
#   gcloud auth login
#   gcloud config set project <your-project-id>
#   gcloud config set run/region <region>       # e.g. europe-west3
#   gcloud components install beta
#
# How it works: `gcloud run compose up` has no flag to set environment variables, so the JWT secret is
# supplied through the Compose file's `env_file: deploy.env`. This script generates deploy.env with a random
# JWT secret on first run and reuses that secret afterwards (so JWTs issued earlier survive a redeploy).
# deploy.env is gitignored and kept out of the Docker build context. `--allow-unauthenticated` grants public
# invocation in the same command, so the deploy is a single step; app-level authentication still gates write
# requests.

set -euo pipefail

cd "$(dirname "$0")/.."

# Generate the random JWT secret once and reuse it on later runs.
if [[ -f deploy.env ]]; then
  secret="$(grep '^JWT_SECRET=' deploy.env | cut -d= -f2-)"
else
  secret="$(openssl rand -hex 32)"
  echo "Generated deploy.env (gitignored) with a random JWT secret."
fi
printf 'JWT_SECRET=%s\n' "$secret" > deploy.env

echo "Deploying campus-coffee-consumption-prod to Cloud Run..."
gcloud beta run compose up compose.prod.yaml --allow-unauthenticated

echo "Service URL:"
gcloud run services describe campus-coffee-consumption-prod --format='value(status.url)'
