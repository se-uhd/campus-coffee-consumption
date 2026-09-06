# Deploy identifiers and non-secret configuration. None has a default that names a real project, region,
# or instance: scripts/deploy.sh exports every value from the gitignored deploy.prod.env as TF_VAR_*.

variable "project" {
  type        = string
  description = "Google Cloud project id."
}

variable "region" {
  type        = string
  description = "Region of the Cloud Run service, the Cloud SQL instance, and the image repository."
}

variable "sql_instance" {
  type        = string
  description = "Name of the Cloud SQL instance (the last segment of its connection name)."
}

variable "sql_zone" {
  type        = string
  description = "Zone of the zonal Cloud SQL instance, inside the region."
}

variable "service_name" {
  type        = string
  description = "Name of the Cloud Run service."
  default     = "campus-coffee-consumption-prod"
}

variable "image" {
  type        = string
  description = "Container image the service runs, as pushed by scripts/deploy.sh (a tag or a digest reference)."
}

variable "base_url" {
  type        = string
  description = "Public https origin of the app, printed into the capability QR codes (CAMPUS_COFFEE_APP_BASE_URL)."
}

variable "db_username" {
  type        = string
  description = "Database role the app connects as."
  default     = "campus_coffee_app"
}

variable "bootstrap_admin_login" {
  type        = string
  description = "Login name of the admin created on a fresh database."
}

variable "bootstrap_admin_email" {
  type        = string
  description = "Email address of the bootstrap admin."
}

variable "bootstrap_admin_first_name" {
  type        = string
  description = "First name of the bootstrap admin."
  default     = "Bootstrap"
}

variable "bootstrap_admin_last_name" {
  type        = string
  description = "Last name of the bootstrap admin."
  default     = "Admin"
}

variable "secret_versions" {
  type        = map(string)
  description = "Secret Manager version number to bind, per secret id (e.g. { jwt-secret = \"3\" }). Pinned rather than `latest` so that a rotation is a visible template change that creates a revision, and every revision is reproducible. scripts/deploy.sh exports the current latest versions."

  validation {
    condition = alltrue([
      for id in ["jwt-secret", "login-key", "db-app-password", "bootstrap-admin-password", "totp-encryption-key"] :
      can(regex("^[0-9]+$", lookup(var.secret_versions, id, "")))
    ])
    error_message = "secret_versions must map each of jwt-secret, login-key, db-app-password, bootstrap-admin-password, and totp-encryption-key to a numeric version."
  }
}

variable "max_instances" {
  type        = number
  description = "Cloud Run instance cap. Keep max_instances x the app's pool size x 2 (two revisions overlap during a deployment) under the database's connection limit (25 on db-f1-micro)."
  default     = 2
}

variable "alert_email" {
  type        = string
  description = "Recipient of the uptime alert. Empty disables the uptime check and the alert."
  default     = ""
}

variable "state_passphrase" {
  type        = string
  description = "Passphrase that encrypts the committed state and plan files (STATE_PASSPHRASE in deploy.prod.env)."
  sensitive   = true

  validation {
    condition     = length(var.state_passphrase) >= 16
    error_message = "The state passphrase must be at least 16 characters long."
  }
}
