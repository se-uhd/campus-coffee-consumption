#!/usr/bin/env bash
#
# One-command deploy of CampusCoffeeConsumption to Google Cloud Run: sync the secrets from deploy.prod.env
# into Secret Manager, build and push the image, and apply the OpenTofu definition of the cloud setup in
# infra/ (see doc/adr/002-declarative-cloud-setup.md).
#
# Usage:
#   scripts/deploy.sh          # prints the plan and asks before applying
#   scripts/deploy.sh --yes    # applies without asking (the plan is still printed)
#   scripts/deploy.sh --plan   # only plans, against the image the service runs: the drift check, no build,
#                              # no secret sync (a changed secret value is not visible to a plan)
#
# One-time prerequisites:
#   gcloud auth login
#   gcloud on the PATH, and opentofu either on the PATH or installed through mise (both are pinned in
#     mise.toml; the script falls back to `mise exec` for opentofu)
#   deploy.prod.env filled in (copy deploy.env.example)
#
# The definition adopts the existing setup: infra/imports.tf names the Cloud SQL instance, the Cloud Run
# service, the identities, and their bindings, and a plan fails when one of them does not exist. A fresh
# project needs a documented bootstrap first; see "A fresh project" in the README.
#
# Secrets never pass through OpenTofu: this script writes the Secret Manager versions itself, and the
# definition binds them to the service by name and version number. OpenTofu authenticates with the gcloud
# login's access token, so no application-default credentials are needed. The state in
# infra/terraform.tfstate is encrypted with STATE_PASSPHRASE and committed to git: commit it together with
# the change after every apply (the script refuses to run while a previous apply is still uncommitted).

set -euo pipefail
umask 077
cd "$(dirname "$0")/.."
. scripts/lib/deploy-env.sh

# gcloud and opentofu are both pinned in mise.toml. gcloud is normally on the PATH; opentofu is not unless
# the shell has mise activated, so it is run through `mise exec` when the PATH has no `tofu`.
command -v gcloud >/dev/null 2>&1 || {
  echo "gcloud is not on the PATH. See the prerequisites above." >&2
  exit 1
}
if command -v tofu >/dev/null 2>&1; then
  run_tofu() { tofu "$@"; }
elif command -v mise >/dev/null 2>&1; then
  run_tofu() { mise exec -- tofu "$@"; }
else
  echo "opentofu is not on the PATH and mise is not installed. See the prerequisites above." >&2
  exit 1
fi

# The same default as `service_name` in infra/variables.tf. Used only to find the running image when the
# state is gone, which is the recovery path documented in the README.
service="campus-coffee-consumption-prod"
secret_names=(jwt-secret login-key db-app-password bootstrap-admin-password totp-encryption-key)
state_files=(infra/terraform.tfstate infra/terraform.tfstate.backup)

auto_approve=""
plan_only=""
usage() {
  echo "usage: scripts/deploy.sh [--yes | --plan]" >&2
  exit 2
}
[ "$#" -le 1 ] || usage
case "${1:-}" in
  "") ;;
  --yes) auto_approve="-auto-approve" ;;
  --plan) plan_only=1 ;;
  *) usage ;;
esac

for key in GCP_PROJECT GCP_REGION SQL_INSTANCE SQL_ZONE STATE_PASSPHRASE DB_USERNAME DB_PASSWORD \
  CAMPUS_COFFEE_APP_BASE_URL BOOTSTRAP_ADMIN_LOGIN BOOTSTRAP_ADMIN_EMAIL BOOTSTRAP_ADMIN_PASSWORD \
  JWT_SECRET LOGIN_PRIVATE_KEY_PEM TOTP_ENCRYPTION_KEY; do
  require "$key"
done
# May be empty (no uptime check), but the line has to be there: see `present` in scripts/lib/deploy-env.sh.
present ALERT_EMAIL

# An apply writes the state, so a previous apply that was never committed must not be built on. A DELETED
# state is allowed on purpose: that is the recovery path, where the import blocks re-adopt the resources.
# A plan writes nothing, so it does not need the check.
if [ -z "$plan_only" ]; then
  uncommitted_state="$(git status --porcelain --untracked-files=all -- "${state_files[@]}" | awk '$1 !~ /D/')"
  if [ -n "$uncommitted_state" ]; then
    echo "infra/terraform.tfstate has changes from a previous apply that are not committed. Commit them first." >&2
    exit 1
  fi
fi

project="$(val GCP_PROJECT)"
region="$(val GCP_REGION)"
export CLOUDSDK_CORE_PROJECT="$project"

# --- sync the secrets from deploy.prod.env into Secret Manager ---------------------------------------------
if [ -z "$plan_only" ]; then
  echo "Syncing secrets from ${env_file} to Secret Manager..."
  printf '%s' "$(val JWT_SECRET)" | sync_secret jwt-secret
  # Read the key first: a command substitution fails before the pipeline starts, so a malformed PEM never
  # reaches Secret Manager.
  login_key_pem="$(login_key)"
  printf '%s\n' "$login_key_pem" | sync_secret login-key
  unset login_key_pem
  printf '%s' "$(val DB_PASSWORD)" | sync_secret db-app-password
  printf '%s' "$(val BOOTSTRAP_ADMIN_PASSWORD)" | sync_secret bootstrap-admin-password
  printf '%s' "$(val TOTP_ENCRYPTION_KEY)" | sync_secret totp-encryption-key
fi

# The definition binds each secret by version number, so a rotation is a visible template change rather than
# a value the running instances pick up whenever they happen to restart. The version numbers are read once,
# here, and used both for the apply and for retiring the superseded versions afterwards.
pinned_versions=() # entries of the form "<secret name>=<version number>"
read_pinned_versions() {
  local name version
  for name in "${secret_names[@]}"; do
    # `|| true` so that a missing secret reaches the message below instead of aborting the script on the
    # command substitution, which would leave the operator with a bare gcloud NOT_FOUND and no next step.
    version="$(gcloud secrets versions describe latest --secret="$name" --format='value(name)' 2>/dev/null || true)"
    [ -n "$version" ] || {
      echo "the Secret Manager secret ${name} has no version to pin." >&2
      echo "Run scripts/deploy.sh without --plan, which creates the secrets from ${env_file} first." >&2
      exit 1
    }
    pinned_versions+=("${name}=${version##*/}")
  done
}
pinned_version_of() {
  local entry
  for entry in "${pinned_versions[@]}"; do
    case "$entry" in
      "$1="*)
        printf '%s' "${entry#*=}"
        return 0
        ;;
    esac
  done
  return 1
}
secret_versions_json() {
  local out="{" sep="" name
  for name in "${secret_names[@]}"; do
    out+="${sep}\"${name}\":\"$(pinned_version_of "$name")\""
    sep=","
  done
  printf '%s}' "$out"
}
read_pinned_versions

# --- the OpenTofu variables --------------------------------------------------------------------------------
# The provider authenticates with the gcloud login's access token. A token lives an hour and the provider
# cannot renew it, so one is minted right before each apply, after the (long) image build.
mint_token() {
  GOOGLE_OAUTH_ACCESS_TOKEN="$(gcloud auth print-access-token)"
  export GOOGLE_OAUTH_ACCESS_TOKEN
}
# Assigned before being exported throughout, so that a failing command substitution is not masked by
# `export`'s own exit status.
TF_VAR_project="$project"
TF_VAR_region="$region"
TF_VAR_sql_instance="$(val SQL_INSTANCE)"
TF_VAR_sql_zone="$(val SQL_ZONE)"
TF_VAR_base_url="$(val CAMPUS_COFFEE_APP_BASE_URL)"
TF_VAR_db_username="$(val DB_USERNAME)"
TF_VAR_bootstrap_admin_login="$(val BOOTSTRAP_ADMIN_LOGIN)"
TF_VAR_bootstrap_admin_email="$(val BOOTSTRAP_ADMIN_EMAIL)"
TF_VAR_alert_email="$(val ALERT_EMAIL)"
TF_VAR_state_passphrase="$(val STATE_PASSPHRASE)"
TF_VAR_secret_versions="$(secret_versions_json)"
export TF_VAR_project TF_VAR_region TF_VAR_sql_instance TF_VAR_sql_zone TF_VAR_base_url TF_VAR_db_username
export TF_VAR_bootstrap_admin_login TF_VAR_bootstrap_admin_email TF_VAR_alert_email TF_VAR_state_passphrase
export TF_VAR_secret_versions

# The two name variables have defaults in infra/variables.tf, so they are passed only when set.
first_name="$(val BOOTSTRAP_ADMIN_FIRST_NAME)"
if [ -n "$first_name" ]; then
  TF_VAR_bootstrap_admin_first_name="$first_name"
  export TF_VAR_bootstrap_admin_first_name
fi
last_name="$(val BOOTSTRAP_ADMIN_LAST_NAME)"
if [ -n "$last_name" ]; then
  TF_VAR_bootstrap_admin_last_name="$last_name"
  export TF_VAR_bootstrap_admin_last_name
fi

run_tofu -chdir=infra init -input=false >/dev/null

if [ -n "$plan_only" ]; then
  # The plan needs the image the service runs, or every plan would want to change it. It comes from the
  # state, and from the running service when there is no state (the recovery path).
  image="$(run_tofu -chdir=infra output -raw image 2>/dev/null || true)"
  if [ -z "$image" ]; then
    image="$(gcloud run services describe "$service" --region "$region" \
      --format='value(spec.template.spec.containers[0].image)' 2>/dev/null || true)"
  fi
  [ -n "$image" ] || {
    echo "could not determine the image the service runs: neither the state nor the service ${service} names one." >&2
    exit 1
  }
  export TF_VAR_image="$image"
  mint_token
  run_tofu -chdir=infra plan -input=false
  exit
fi

# --- build and push the image ------------------------------------------------------------------------------
repository="${region}-docker.pkg.dev/${project}/campus-coffee"
build_account="campus-coffee-build@${project}.iam.gserviceaccount.com"
tag="$(git rev-parse --short=12 HEAD)"
[ -z "$(git status --porcelain --untracked-files=no)" ] || tag="${tag}-dirty-$(date -u +%Y%m%dT%H%M%SZ)"
# `image` is a required variable, and its real value (the digest) is only known after the build, so the
# bootstrap apply below gets the tag as a placeholder. None of its targets reads it.
export TF_VAR_image="${repository}/app:${tag}"

# The repository and the build identity come from the definition, so when the repository does not exist yet
# they are applied on their own first.
if ! gcloud artifacts repositories describe campus-coffee --location "$region" >/dev/null 2>&1; then
  # The staging bucket is created by Cloud Build itself and is deliberately not part of the definition: it is
  # a US multi-region bucket shared by everything in the project, so declaring it with this deployment's
  # region would replace it and destroy the build logs. The IAM binding below still needs it to exist.
  gcloud storage buckets describe "gs://${project}_cloudbuild" >/dev/null 2>&1 || {
    echo "gs://${project}_cloudbuild does not exist yet. Create it with" >&2
    echo "  gcloud storage buckets create gs://${project}_cloudbuild --location=US" >&2
    echo "and run this script again." >&2
    exit 1
  }
  echo "Creating the image repository and the build identity..."
  mint_token
  run_tofu -chdir=infra apply -input=false $auto_approve \
    -target=google_artifact_registry_repository.images -target=google_service_account.build \
    -target=google_project_iam_member.build_log_writer \
    -target=google_artifact_registry_repository_iam_member.build_pusher \
    -target='google_storage_bucket_iam_member.build_bucket'
fi

echo "Building and pushing ${repository}/app:${tag} with Cloud Build (the context is what .gcloudignore admits)..."
gcloud builds submit --tag "${repository}/app:${tag}" --service-account="projects/${project}/serviceAccounts/${build_account}" \
  --gcs-log-dir="gs://${project}_cloudbuild/logs" --quiet .
# The service is pinned to the digest, so a rebuild of the same commit is still a visible change.
digest="$(gcloud artifacts docker images describe "${repository}/app:${tag}" --format='value(image_summary.digest)')"
case "$digest" in
  sha256:*) ;;
  *)
    echo "could not resolve the digest of ${repository}/app:${tag}" >&2
    exit 1
    ;;
esac
export TF_VAR_image="${repository}/app@${digest}"

# --- apply -------------------------------------------------------------------------------------------------
mint_token
run_tofu -chdir=infra apply -input=false $auto_approve

# --- retire the superseded secret versions -----------------------------------------------------------------
# The new revision reads one version per secret, but the runtime identity may read any ENABLED version of the
# secrets it can access, so a compromised app process could read every historical value. The superseded ones
# are disabled once the new revision is serving. Disabling is reversible
# (`gcloud secrets versions enable <version> --secret=<name>`), which is what routing traffic back to an
# older revision needs.
for name in "${secret_names[@]}"; do
  pinned="$(pinned_version_of "$name")"
  while read -r version state; do
    # Both fields are normalized rather than taken as rendered. `value()` prints what the list command's
    # display transform produces (today a bare number and a lowercase state), while the underlying API
    # fields are the full resource path and an uppercase enum. Normalizing accepts either, so a changed
    # transform cannot turn this into a silent no-op that leaves every old version readable.
    version="${version##*/}"
    state="$(printf '%s' "$state" | tr '[:upper:]' '[:lower:]')"
    case "$version" in
      '' | *[!0-9]*)
        echo "  WARNING: unexpected output from 'gcloud secrets versions list ${name}'; retired nothing for it" >&2
        break
        ;;
    esac
    if [ "$state" = "enabled" ] && [ "$version" != "$pinned" ]; then
      gcloud secrets versions disable "$version" --secret="$name" --quiet >/dev/null
      echo "  disabled ${name} version ${version} (superseded by ${pinned})"
    fi
  done < <(gcloud secrets versions list "$name" --format='value(name,state)')
done

echo "Service URL: $(run_tofu -chdir=infra output -raw service_url)"
echo "Commit infra/terraform.tfstate, infra/terraform.tfstate.backup, and infra/.terraform.lock.hcl with this change."
