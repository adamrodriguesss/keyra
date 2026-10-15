# Proxies the published GHCR image so Cloud Run can pull it; only when no custom image is set.
resource "google_artifact_registry_repository" "ghcr" {
  count = local.use_ghcr ? 1 : 0

  project       = var.project_id
  location      = var.region
  repository_id = "${var.name}-ghcr"
  format        = "DOCKER"
  mode          = "REMOTE_REPOSITORY"

  remote_repository_config {
    docker_repository {
      custom_repository {
        uri = "https://ghcr.io"
      }
    }
  }

  depends_on = [google_project_service.apis]
}
