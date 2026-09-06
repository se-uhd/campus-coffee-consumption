output "service_url" {
  description = "The Cloud Run service URL."
  value       = google_cloud_run_v2_service.app.uri
}

output "build_service_account" {
  description = "The identity scripts/deploy.sh runs the Cloud Build image build as."
  value       = google_service_account.build.email
}

output "runtime_service_account" {
  description = "The identity the service runs as."
  value       = google_service_account.run.email
}

output "image" {
  description = "The image the service runs (by digest), read by scripts/deploy.sh --plan."
  value       = google_cloud_run_v2_service.app.template[0].containers[0].image
}

output "image_repository" {
  description = "The Artifact Registry repository scripts/deploy.sh pushes to."
  value       = "${var.region}-docker.pkg.dev/${var.project}/${google_artifact_registry_repository.images.repository_id}"
}
