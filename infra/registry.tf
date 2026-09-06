# Where scripts/deploy.sh pushes the image. The cleanup policies keep the ten newest versions, drop untagged
# digests after 30 days and tagged ones after 90, so the repository does not grow with every deploy while a
# rollback target from the last quarter stays available. Cloud Run keeps its own copy of the image a serving
# revision uses, so a deletion here never affects the running service.
resource "google_artifact_registry_repository" "images" {
  location      = var.region
  repository_id = "campus-coffee"
  format        = "DOCKER"
  description   = "Images of the campus-coffee-consumption Cloud Run service"
  # The location is part of the resource's identity, so a wrong GCP_REGION would otherwise plan a
  # replacement, which deletes every image in the repository. `prevent_destroy` rejects that at plan time,
  # before any part of the apply runs, and `deletion_policy` is the provider-level second layer, the same
  # pairing the Cloud SQL instance and the Cloud Run service use.
  deletion_policy = "PREVENT"

  lifecycle {
    prevent_destroy = true
  }

  cleanup_policies {
    id     = "keep-recent"
    action = "KEEP"

    most_recent_versions {
      keep_count = 10
    }
  }

  cleanup_policies {
    id     = "delete-untagged"
    action = "DELETE"

    condition {
      tag_state  = "UNTAGGED"
      older_than = "2592000s"
    }
  }

  cleanup_policies {
    id     = "delete-old-tagged"
    action = "DELETE"

    condition {
      tag_state  = "TAGGED"
      older_than = "7776000s"
    }
  }

  depends_on = [google_project_service.required]
}
