# The managed PostgreSQL 18 instance. Tier and instance cap are coupled: db-f1-micro allows 25 connections,
# and the app holds 5 per Cloud Run instance across up to two overlapping revisions (see variables.tf,
# max_instances). Deletion protection is set twice on purpose: `deletion_protection` makes OpenTofu refuse
# to destroy the resource, `deletion_protection_enabled` makes the Cloud SQL API refuse it too.
resource "google_sql_database_instance" "main" {
  name                = var.sql_instance
  database_version    = "POSTGRES_18"
  region              = var.region
  deletion_protection = true

  settings {
    tier                        = "db-f1-micro"
    edition                     = "ENTERPRISE"
    availability_type           = "ZONAL"
    disk_type                   = "PD_SSD"
    disk_size                   = 10
    disk_autoresize             = true
    deletion_protection_enabled = true
    retain_backups_on_delete    = true
    enable_dataplex_integration = true

    location_preference {
      zone = var.sql_zone
    }

    backup_configuration {
      enabled                        = true
      location                       = "eu"
      start_time                     = "02:00"
      point_in_time_recovery_enabled = true
      transaction_log_retention_days = 7

      backup_retention_settings {
        retained_backups = 7
        retention_unit   = "COUNT"
      }
    }

    final_backup_config {
      enabled        = true
      retention_days = 30
    }

    ip_configuration {
      ipv4_enabled = true
      ssl_mode     = "ENCRYPTED_ONLY"
    }

    # Mirrors the instance as created: no day, so no fixed window (Google picks the time) and `hour` is inert;
    # only the update track is set. Add `day = 1..7` here to pin a weekly window.
    maintenance_window {
      hour         = 0
      update_track = "canary"
    }

    database_flags {
      name  = "cloudsql.iam_authentication"
      value = "on"
    }

    password_validation_policy {
      enable_password_policy      = true
      complexity                  = "COMPLEXITY_DEFAULT"
      min_length                  = 8
      disallow_username_substring = true
      reuse_interval              = 0
    }
  }

  lifecycle {
    # The disk grows on its own (disk_autoresize); the declared size is the floor, not a target.
    ignore_changes = [settings[0].disk_size]
    # The name and the region are part of the resource's identity: a typo in either would plan a replacement,
    # which destroys the database. This rejects such a plan before any part of the apply runs; the two
    # deletion_protection settings above are the provider-level and API-level layers behind it.
    prevent_destroy = true
  }

  depends_on = [google_project_service.required]
}
