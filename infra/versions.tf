# The cloud setup of the production deployment, defined declaratively (see doc/adr/002-declarative-cloud-setup.md).
# Applied with OpenTofu from the operator's machine through scripts/deploy.sh; CI only formats and validates.
terraform {
  required_version = ">= 1.12"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 8.1"
    }
  }
}

provider "google" {
  project = var.project
  region  = var.region
}
