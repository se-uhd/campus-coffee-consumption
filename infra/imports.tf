# Adopts the resources that exist before a plan runs. Kept permanently: importing a resource that is already
# in the state is a no-op, so this file is also the recovery path when the state file or its passphrase is
# lost (delete the state, run `tofu plan`, and every resource is re-adopted). The one exception is the
# monitoring trio (notification channel, uptime check, alert policy): their ids are server-generated, so
# after a state loss the plan creates a new check, channel, and policy, and the previous three are deleted
# by hand. The ids are built from the
# variables so that no deploy identifier is committed. A plan fails when an imported object does not exist,
# so on a fresh project the blocks for the instance, the service, and the identities are removed for the
# first apply.
import {
  for_each = local.services
  to       = google_project_service.required[each.key]
  id       = "${var.project}/${each.key}"
}

import {
  for_each = local.secret_ids
  to       = google_secret_manager_secret.app[each.key]
  id       = "projects/${var.project}/secrets/${each.key}"
}

import {
  to = google_sql_database_instance.main
  id = "projects/${var.project}/instances/${var.sql_instance}"
}

import {
  to = google_cloud_run_v2_service.app
  id = "projects/${var.project}/locations/${var.region}/services/${var.service_name}"
}

import {
  to = google_cloud_run_v2_service_iam_member.public
  id = "projects/${var.project}/locations/${var.region}/services/${var.service_name} roles/run.invoker allUsers"
}

import {
  to = google_artifact_registry_repository.images
  id = "projects/${var.project}/locations/${var.region}/repositories/campus-coffee"
}

import {
  to = google_service_account.run
  id = "projects/${var.project}/serviceAccounts/campus-coffee-run@${var.project}.iam.gserviceaccount.com"
}

import {
  to = google_project_iam_member.run_cloudsql_client
  id = "${var.project} roles/cloudsql.client serviceAccount:campus-coffee-run@${var.project}.iam.gserviceaccount.com"
}

import {
  for_each = local.secret_ids
  to       = google_secret_manager_secret_iam_member.run_secret_accessor[each.key]
  id       = "projects/${var.project}/secrets/${each.key} roles/secretmanager.secretAccessor serviceAccount:campus-coffee-run@${var.project}.iam.gserviceaccount.com"
}

import {
  to = google_service_account.build
  id = "projects/${var.project}/serviceAccounts/campus-coffee-build@${var.project}.iam.gserviceaccount.com"
}

import {
  to = google_project_iam_member.build_log_writer
  id = "${var.project} roles/logging.logWriter serviceAccount:campus-coffee-build@${var.project}.iam.gserviceaccount.com"
}

import {
  to = google_artifact_registry_repository_iam_member.build_pusher
  id = "projects/${var.project}/locations/${var.region}/repositories/campus-coffee roles/artifactregistry.writer serviceAccount:campus-coffee-build@${var.project}.iam.gserviceaccount.com"
}

import {
  for_each = toset(["roles/storage.objectUser", "roles/storage.legacyBucketReader"])
  to       = google_storage_bucket_iam_member.build_bucket[each.key]
  id       = "b/${var.project}_cloudbuild ${each.key} serviceAccount:campus-coffee-build@${var.project}.iam.gserviceaccount.com"
}
