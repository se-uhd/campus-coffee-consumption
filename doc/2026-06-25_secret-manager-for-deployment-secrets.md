# Deployment secrets in Google Secret Manager

Date: 2026-06-25

## Decision

The production secrets are **authored locally in `deploy.prod.env`** (gitignored, the source of truth) and
**synced into Google Secret Manager** by the deploy. The running Cloud Run service reads them from Secret
Manager, bound as environment variables with `--set-secrets`. So the operator keeps a convenient editable copy
on their machine, while the runtime gets Secret Manager's encryption at rest, access audit, and versioning, and
no secret value rides in the git history or the build context.

The secrets, their Secret Manager names, and where their values come from:

| Environment variable        | Secret Manager secret      | Value source                         |
| --------------------------- | -------------------------- | ------------------------------------ |
| `JWT_SECRET`                | `jwt-secret`               | `deploy.prod.env` (synced on deploy) |
| `LOGIN_PRIVATE_KEY_PEM`     | `login-key`                | `deploy.prod.env` (synced on deploy) |
| `DB_PASSWORD`               | `db-app-password`          | `deploy.prod.env` (synced on deploy) |
| `BOOTSTRAP_ADMIN_PASSWORD`  | `bootstrap-admin-password` | `deploy.prod.env` (synced on deploy) |

The same `deploy.prod.env` also holds the **non-secret** config, passed to the service with `--set-env-vars`:
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

- The Cloud Run runtime service account needs `roles/secretmanager.secretAccessor` (to read the secrets) in
  addition to `roles/cloudsql.client` (to connect through the socket factory).
- `scripts/deploy-cloudrun.sh` syncs the secrets from `deploy.prod.env` on every deploy: it creates each
  Secret Manager secret if missing, and adds a new version only when the local value has changed, then binds
  `:latest` onto the service.
- The login key is stored as a normal multi-line PEM. `LoginEncryptionConfig` turns any literal `\n` into
  newlines, so a real multi-line PEM (the Secret Manager form) and a single line with `\n` separators both
  parse. It must be the same key on every instance (a client may fetch the public key from one instance and
  post the ciphertext to another), which one shared Secret Manager secret satisfies.
- Rotate a secret by adding a new version (`gcloud secrets versions add <name> --data-file=-`) and
  redeploying so the revision picks up `:latest`.
