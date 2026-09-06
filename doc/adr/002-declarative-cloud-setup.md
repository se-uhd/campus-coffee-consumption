# Should the cloud setup be defined declaratively in the repository, and with which tool?

## Decision Metadata

### Status

Accepted

### Date

2026-09-06

### Involved

* Sebastian Baltes (decision)
* Claude Code (analysis)

## Context

The production setup on Google Cloud was an imperative shell script (`scripts/deploy-cloudrun.sh`, running
`gcloud run deploy --source .`) plus configuration that lived only in the console: the Cloud SQL instance's
tier, backups, flags, and maintenance window, the enabled APIs, and the Identity and Access Management (IAM)
bindings. There was no way to see drift and no reproducible way to recreate the setup. Reviewing it on
2026-09-06 also found two least-privilege gaps: the app connected to Cloud SQL as the `postgres` superuser
(the `campus_coffee_app` role in `scripts/sql/create-app-role.sql` had never been provisioned, although the
docs claimed it had), and the Cloud Run service ran as the default compute service account, which holds
`roles/editor` on the whole project.

The decision has three parts: the tool, where its state file lives, and where the secrets live.

## Considered Options

Tool:

* Keep the imperative script. No migration, but the gaps above stay invisible and the instance
  configuration stays in the console.
* OpenTofu (1.12, Mozilla Public License, Linux Foundation). Reads the same configuration files, written
  in HCL (the HashiCorp Configuration Language), and the same providers as Terraform. The state format is
  compatible. Has built-in state encryption and write-only attributes.
* HashiCorp Terraform (Business Source License, not open source). Functionally equivalent here, and the
  license is the difference for an open-source university project.
* Google Infrastructure Manager. Managed Terraform runs through Cloud Build with the state kept by
  Google. Terraform only, and every run depends on Cloud Build.
* Pulumi. Infrastructure as a program in a general language. A second runtime and ecosystem in a Kotlin
  and TypeScript repository for no gain at this size.
* Config Connector, Deployment Manager. The first needs a Kubernetes cluster, the second is deprecated.

State file location:

* A Google Cloud Storage bucket. Locking and versioning for several operators or for apply runs started from
  continuous integration (CI). One operator needs neither, and it is one more cloud resource to keep.
* A gitignored local file. Simplest, but lost with the machine.
* A hosted service (HashiCorp Cloud Platform (HCP) Terraform, Pulumi Cloud). An external dependency.
* Committed to git in plain text. The state holds no secret in this design, but it is a full inventory
  of the resources, including identifiers that must not appear in a public repository (project id and
  number, instance and connection name, IP addresses, service account and alert emails, the admin login).
* Committed to git, encrypted. OpenTofu encrypts the state with a passphrase before writing it. The
  identifiers stay out of the history, git provides the versioning, and any machine with the passphrase can
  plan and apply.

Secrets:

* Managed by the tool (write-only attributes keep the values out of the state). The values would still
  pass through the tool's variables.
* Unchanged: `deploy.prod.env` on the operator's machine, synced into Secret Manager by the deploy
  script. The tool sees only the secret containers, never a value.

## Decision(s)

OpenTofu, with the state committed to git encrypted (a key derived from the passphrase `STATE_PASSPHRASE`
in `deploy.prod.env` with PBKDF2, a password-based key derivation function, and AES-GCM authenticated
encryption, enforced for state and plan files), and the secrets left
where they are. OpenTofu owns the cloud resources: the enabled APIs, an Artifact Registry repository, a
dedicated runtime service account with exactly the two roles the app needs, the Secret Manager secret
containers, the Cloud SQL instance with deletion protection, the Cloud Run service, and an uptime check with
an email alert. The wrapper script `scripts/deploy.sh` owns the rest: syncing the secret versions, building
and pushing the image, and running `tofu apply`. The one-time database role setup stays a SQL script. The apply
runs from the operator's machine. CI runs `tofu fmt -check` and `tofu validate` and rejects a state file that
is not encrypted. No bucket.

## Consequences

* Positive: a setup that is reviewable in the repository, drift shown by `tofu plan`, a setup that can be
  recreated, a state that travels with the repository, and the two least-privilege gaps closed as part of
  the move (a dedicated service account, and the app role provisioned with the existing tables' ownership
  transferred to it so that future Flyway migrations keep working).
* Negative: the existing resources had to be imported with a zero-change plan before anything was applied.
  Building the image and applying are two steps. There is one more secret (the passphrase) to keep. The state file
  is committed after every apply. The `gcloud run deploy` path is gone, so the service must never be changed
  by hand again.
* A lost passphrase makes the committed state unreadable. The import blocks stay in `infra/imports.tf`, so
  the recovery is to delete the state and let the next plan re-adopt the resources (all but the monitoring
  trio, whose ids are server-generated, so the plan creates a new check, channel, and policy, and the previous
  three are deleted by hand).
* Applies from CI (via Workload Identity Federation, with the passphrase as a CI secret) are possible later
  without changing the layout.
