# The secret containers only. Their versions (the values) are written by scripts/deploy.sh from
# deploy.prod.env, so no secret value ever passes through OpenTofu or its state.
locals {
  secret_ids = toset([
    "jwt-secret",
    "login-key",
    "db-app-password",
    "bootstrap-admin-password",
    "totp-encryption-key",
  ])
}

resource "google_secret_manager_secret" "app" {
  for_each            = local.secret_ids
  secret_id           = each.key
  deletion_protection = true

  replication {
    auto {}
  }

  depends_on = [google_project_service.required]
}
