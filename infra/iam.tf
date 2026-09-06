# The identity the Cloud Run service runs as, with exactly what the app needs: connecting to Cloud SQL
# through the connector, and reading its five secrets. No project-level role (the default compute service
# account it replaces held roles/editor on the whole project).
resource "google_service_account" "run" {
  account_id   = "campus-coffee-run"
  display_name = "Cloud Run runtime of campus-coffee-consumption"
  depends_on   = [google_project_service.required]
}

resource "google_project_iam_member" "run_cloudsql_client" {
  project = var.project
  role    = "roles/cloudsql.client"
  member  = google_service_account.run.member
}

resource "google_secret_manager_secret_iam_member" "run_secret_accessor" {
  for_each  = google_secret_manager_secret.app
  secret_id = each.value.secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = google_service_account.run.member
}

# The identity Cloud Build runs the image build as (scripts/deploy.sh passes it to `gcloud builds submit`).
# A build needs exactly three things: write its logs, read its source upload from the project's Cloud Build
# bucket, and push to the image repository. Before this definition the build ran as the default compute
# service account, which held roles/editor on the whole project; that account now needs no role at all (its
# bindings are removed by hand, project IAM being additive).
resource "google_service_account" "build" {
  account_id   = "campus-coffee-build"
  display_name = "Cloud Build of the campus-coffee-consumption image"
  depends_on   = [google_project_service.required]
}

resource "google_project_iam_member" "build_log_writer" {
  project = var.project
  role    = "roles/logging.logWriter"
  member  = google_service_account.build.member
}

resource "google_artifact_registry_repository_iam_member" "build_pusher" {
  location   = google_artifact_registry_repository.images.location
  repository = google_artifact_registry_repository.images.repository_id
  role       = "roles/artifactregistry.writer"
  member     = google_service_account.build.member
}

# The source upload and the build logs live in the bucket `gcloud builds submit` uses by default. Objects are
# read, written, and replaced with the object user role (no object ACL rights, unlike object admin), and the
# legacy bucket reader adds the bucket-level `get` that gcloud checks before it starts a build with a chosen
# identity.
resource "google_storage_bucket_iam_member" "build_bucket" {
  for_each = toset(["roles/storage.objectUser", "roles/storage.legacyBucketReader"])
  bucket   = "${var.project}_cloudbuild"
  role     = each.key
  member   = google_service_account.build.member
}
