# The Cloud Run service. Secrets are bound by name and version number to Secret Manager; the values
# themselves never appear here. A new revision is created whenever the template changes: scripts/deploy.sh
# passes the image by digest and the secrets' current version numbers, so a rebuild and a rotation both
# show up as a change, and a revision always names exactly what it runs.
locals {
  cloud_sql_connection_name = "${var.project}:${var.region}:${var.sql_instance}"
}

resource "google_cloud_run_v2_service" "app" {
  name                = var.service_name
  location            = var.region
  ingress             = "INGRESS_TRAFFIC_ALL"
  deletion_protection = true

  template {
    service_account                  = google_service_account.run.email
    max_instance_request_concurrency = 80

    scaling {
      min_instance_count = 0
      max_instance_count = var.max_instances
    }

    containers {
      image = var.image

      resources {
        limits = {
          cpu    = "1"
          memory = "1Gi"
        }
        cpu_idle          = true
        startup_cpu_boost = true
      }

      env {
        name  = "SPRING_PROFILES_ACTIVE"
        value = "prod"
      }
      env {
        name  = "DB_USERNAME"
        value = var.db_username
      }
      env {
        name  = "CLOUD_SQL_INSTANCE"
        value = local.cloud_sql_connection_name
      }
      env {
        name  = "CAMPUS_COFFEE_APP_BASE_URL"
        value = var.base_url
      }
      env {
        name  = "BOOTSTRAP_ADMIN_LOGIN"
        value = var.bootstrap_admin_login
      }
      env {
        name  = "BOOTSTRAP_ADMIN_EMAIL"
        value = var.bootstrap_admin_email
      }
      env {
        name  = "BOOTSTRAP_ADMIN_FIRST_NAME"
        value = var.bootstrap_admin_first_name
      }
      env {
        name  = "BOOTSTRAP_ADMIN_LAST_NAME"
        value = var.bootstrap_admin_last_name
      }
      env {
        name = "JWT_SECRET"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.app["jwt-secret"].secret_id
            version = var.secret_versions["jwt-secret"]
          }
        }
      }
      env {
        name = "LOGIN_PRIVATE_KEY_PEM"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.app["login-key"].secret_id
            version = var.secret_versions["login-key"]
          }
        }
      }
      env {
        name = "DB_PASSWORD"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.app["db-app-password"].secret_id
            version = var.secret_versions["db-app-password"]
          }
        }
      }
      env {
        name = "BOOTSTRAP_ADMIN_PASSWORD"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.app["bootstrap-admin-password"].secret_id
            version = var.secret_versions["bootstrap-admin-password"]
          }
        }
      }
      env {
        name = "TOTP_ENCRYPTION_KEY"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.app["totp-encryption-key"].secret_id
            version = var.secret_versions["totp-encryption-key"]
          }
        }
      }
    }
  }

  traffic {
    type    = "TRAFFIC_TARGET_ALLOCATION_TYPE_LATEST"
    percent = 100
  }

  # Cloud Run checks at deploy time that the runtime identity can read every bound secret.
  depends_on = [
    google_project_iam_member.run_cloudsql_client,
    google_secret_manager_secret_iam_member.run_secret_accessor,
  ]

  lifecycle {
    # The name and the location are part of the resource's identity: a typo in either would plan a
    # replacement, which deletes the service and with it the URL printed on the wall QR codes. This rejects
    # such a plan before any part of the apply runs; `deletion_protection` above is the provider-level layer.
    prevent_destroy = true
  }
}

# The app is public: users open capability URLs and admins log in; the app authenticates them itself.
resource "google_cloud_run_v2_service_iam_member" "public" {
  name     = google_cloud_run_v2_service.app.name
  location = google_cloud_run_v2_service.app.location
  role     = "roles/run.invoker"
  member   = "allUsers"
}
