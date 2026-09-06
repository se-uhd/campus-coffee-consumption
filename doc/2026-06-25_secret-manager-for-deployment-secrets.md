# Deployment secrets in Google Secret Manager

Date: 2026-06-25

## Decision

The production secrets are **authored locally in `deploy.prod.env`** (gitignored, the source of truth) and
**synced into Google Secret Manager** by the deploy. The running Cloud Run service reads them from Secret
Manager, bound as environment variables by the deploy (since 2026-09-06 by the OpenTofu definition in
`infra/run.tf`, before that with `--set-secrets` on `gcloud run deploy`). So the operator keeps a convenient
editable copy on their machine, while the runtime gets Secret Manager's encryption at rest, access audit,
and versioning, and no secret value rides in the git history or the build context.

The secrets, their Secret Manager names, and where their values come from:

| Environment variable        | Secret Manager secret      | Value source                         |
| --------------------------- | -------------------------- | ------------------------------------ |
| `JWT_SECRET`                | `jwt-secret`               | `deploy.prod.env` (synced on deploy) |
| `LOGIN_PRIVATE_KEY_PEM`     | `login-key`                | `deploy.prod.env` (synced on deploy) |
| `DB_PASSWORD`               | `db-app-password`          | `deploy.prod.env` (synced on deploy) |
| `BOOTSTRAP_ADMIN_PASSWORD`  | `bootstrap-admin-password` | `deploy.prod.env` (synced on deploy) |
| `TOTP_ENCRYPTION_KEY`       | `totp-encryption-key`      | `deploy.prod.env` (synced on deploy) |

The same `deploy.prod.env` also holds the **non-secret** config, set on the service as plain environment
variables (since 2026-09-06 exported to OpenTofu as `TF_VAR_*`, before that passed with `--set-env-vars`):
`CAMPUS_COFFEE_APP_BASE_URL`, `DB_USERNAME`, and the bootstrap-admin identity (`BOOTSTRAP_ADMIN_LOGIN` /
`_EMAIL` / `_FIRST_NAME` / `_LAST_NAME`).

## Why Secret Manager (while keeping the local file)

The earlier deploy carried the secrets in a gitignored env file loaded onto the service through the Compose
`env_file`. Keeping a local file is convenient to author and edit, so `deploy.prod.env` stays as the source of
truth. But shipping the secrets to the cloud through the Compose env file left them only as plaintext in the
deployed environment, with no encryption at rest, access audit, or versioning. Routing them through Secret
Manager instead adds all three and keeps them out of the build context, while the application config
(`application.yaml`'s prod block) already reads them as environment variables, so this changed only the deploy
tooling, not the app. The deploy script reconciles the two: it reads `deploy.prod.env` and syncs each secret
into Secret Manager before binding it onto the service.

## Why `gcloud run deploy`, not `gcloud beta run compose up`

Binding a Secret Manager secret as an environment variable requires `--set-secrets` on `gcloud run deploy`
(or `gcloud run services update`). Cloud Run's Compose support cannot do this: its `secrets:` attribute only
**uploads a local file** into Secret Manager (and mounts it as a file, not as the env var the app reads). And
the prod profile fails fast when a secret is missing, so a `compose up` revision booted without the secrets
would crash before they could be bound in a follow-up update. So the cloud deploy moved from
`gcloud beta run compose up` to `gcloud run deploy --source . --set-secrets ...`, and `compose.prod.yaml` was
removed (the dev `compose.yaml` stays for local runs). Building still happens from the `Dockerfile` via Cloud
Build (`--source .`).

## Vendor lock-in

Secret Manager is a Google Cloud service, so it is not vendor-neutral. The incremental lock-in is small: the
deployment is already committed to Cloud Run and Cloud SQL (via the Cloud SQL socket factory baked into the
prod profile), and the **application stays portable** because it only ever reads environment variables and
never calls a Secret Manager API. Cloud Run performs the injection. Moving clouds later means rewriting the
Cloud Run / Cloud SQL deploy regardless; swapping the secret source (to a `.env` file, HashiCorp Vault, SOPS
+ age, or another manager that can populate env vars) is the easy part and touches only the deploy script.

## IAM and operations

- The service runs as the dedicated runtime service account `campus-coffee-run` (declared in
  `infra/iam.tf`), which holds `roles/secretmanager.secretAccessor` on each of the five secrets and
  `roles/cloudsql.client` (to connect through the socket factory), nothing else.
- `scripts/deploy.sh` syncs the secrets from `deploy.prod.env` on every deploy: it creates each Secret
  Manager secret if missing, and adds a new version only when the local value has changed. The OpenTofu
  definition in `infra/` owns the secret containers and binds each one onto the service by name and version number. It never
  sees a secret value (since 2026-09-06, `doc/adr/002-declarative-cloud-setup.md`). Before that, the previous
  script, `scripts/deploy-cloudrun.sh`, bound them with `gcloud run deploy --set-secrets`.
- The login key is stored as a normal multi-line PEM. `LoginEncryptionConfig` turns any literal `\n` into
  newlines, so a real multi-line PEM (the Secret Manager form) and a single line with `\n` separators both
  parse. It must be the same key on every instance (a client may fetch the public key from one instance and
  post the ciphertext to another), which one shared Secret Manager secret satisfies.
- Rotate a secret by changing its value in `deploy.prod.env` and running `scripts/deploy.sh`: the sync adds
  the Secret Manager version and the apply pins the service to it, so the rotation is a visible template
  change that creates a revision. A version added out of band with `gcloud secrets versions add` is not what
  the service reads, and the next deploy pins whatever `deploy.prod.env` holds. `DB_PASSWORD` additionally
  needs the database role changed in the same window. The README has the runbook.
- The service is pinned to one version per secret, but the runtime identity may read any enabled version of
  those secrets. `scripts/deploy.sh` therefore disables the superseded versions after a successful apply, so
  a compromised app process cannot read historical values. Disabling is reversible
  (`gcloud secrets versions enable <version> --secret=<name>`), which is what routing traffic back to an
  older revision needs.
