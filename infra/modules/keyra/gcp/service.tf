resource "google_service_account" "run" {
  project      = var.project_id
  account_id   = "${var.name}-run"
  display_name = "Keyra (${var.name}) Cloud Run"
}

locals {
  env = {
    KEYRA_ISSUER_URL                  = local.url
    KEYRA_DB_URL                      = local.db_url
    KEYRA_DB_USER                     = local.db_user
    KEYRA_DB_POOL_SIZE                = tostring(var.db_pool_size)
    KEYRA_JWT_KEY_ID                  = random_id.jwt_key_id.hex
    KEYRA_BOOTSTRAP_ADMIN_EMAIL       = var.bootstrap_admin_email
    LOGGING_STRUCTURED_FORMAT_CONSOLE = "ecs"
    # Keyra pushes OTLP to its default localhost:4318, where the collector sidecar listens.
    KEYRA_METRICS_ENABLED = tostring(local.metrics)
  }

  collector_image = "us-docker.pkg.dev/cloud-ops-agents-artifacts/google-cloud-opentelemetry-collector/otelcol-google:0.163.1"
  collector_config = {
    receivers  = { otlp = { protocols = { http = { endpoint = "localhost:4318" } } } }
    processors = { batch = {}, resource_detection = { detectors = ["gcp"] } }
    exporters  = { googlemanagedprometheus = {} }
    # Probes reach the sidecar from outside its loopback, so health listens on all interfaces; OTLP stays local.
    extensions = { health_check = { endpoint = "0.0.0.0:13133" } }
    service = {
      extensions = ["health_check"]
      pipelines = {
        metrics = { receivers = ["otlp"], processors = ["resource_detection", "batch"], exporters = ["googlemanagedprometheus"] }
      }
    }
  }

  # Pinned versions (not "latest"), so a rotation rolls out a new revision.
  secret_env = {
    KEYRA_JWT_PRIVATE_KEY = {
      secret  = google_secret_manager_secret.jwt_private_key.id
      version = google_secret_manager_secret_version.jwt_private_key.version
    }
    KEYRA_BOOTSTRAP_ADMIN_PASSWORD = {
      secret  = google_secret_manager_secret.bootstrap_admin_password.id
      version = google_secret_manager_secret_version.bootstrap_admin_password.version
    }
    KEYRA_DB_PASSWORD = {
      secret  = local.db_password_secret
      version = coalesce(one(google_secret_manager_secret_version.db_password[*].version), "latest")
    }
  }
}

resource "google_cloud_run_v2_service" "keyra" {
  project             = var.project_id
  name                = var.name
  location            = var.region
  ingress             = "INGRESS_TRAFFIC_ALL"
  deletion_protection = var.deletion_protection
  # A public API: every caller authenticates to Keyra itself, not to Cloud Run IAM.
  invoker_iam_disabled = true

  template {
    service_account = google_service_account.run.email

    scaling {
      min_instance_count = var.replicas
      max_instance_count = var.max_replicas
    }

    dynamic "vpc_access" {
      for_each = local.vpc_network == null ? [] : [1]
      content {
        egress = local.vpc_egress
        network_interfaces {
          network    = local.vpc_network
          subnetwork = local.vpc_subnetwork
        }
      }
    }

    containers {
      name  = "keyra"
      image = local.image
      # Start after the collector, so the first metrics push finds it.
      depends_on = local.metrics ? ["otel-collector"] : []

      ports {
        container_port = 8080
      }

      resources {
        limits = {
          cpu    = "1"
          memory = "1Gi"
        }
        startup_cpu_boost = true
        # Metrics are pushed from a background thread, which needs CPU between requests.
        cpu_idle = !local.metrics
      }

      dynamic "env" {
        for_each = local.env
        content {
          name  = env.key
          value = env.value
        }
      }

      dynamic "env" {
        for_each = local.secret_env
        content {
          name = env.key
          value_source {
            secret_key_ref {
              secret  = env.value.secret
              version = env.value.version
            }
          }
        }
      }

      # Startup covers Flyway migrations; readiness includes the database connection.
      startup_probe {
        http_get {
          path = "/actuator/health/readiness"
        }
        period_seconds    = 5
        timeout_seconds   = 3
        failure_threshold = 24
      }

      liveness_probe {
        http_get {
          path = "/actuator/health/liveness"
        }
        period_seconds = 30
      }
    }

    dynamic "containers" {
      for_each = local.metrics ? [1] : []
      content {
        name  = "otel-collector"
        image = local.collector_image
        args  = ["--config=env:OTEL_CONFIG"]

        env {
          name  = "OTEL_CONFIG"
          value = yamlencode(local.collector_config)
        }

        resources {
          limits = {
            cpu    = "1"
            memory = "256Mi"
          }
          cpu_idle = false
        }

        startup_probe {
          http_get {
            path = "/"
            port = 13133
          }
        }
      }
    }
  }

  depends_on = [
    google_project_service.apis,
    google_artifact_registry_repository.ghcr,
    google_secret_manager_secret_iam_member.run,
    google_project_iam_member.metric_writer,
    google_sql_user.keyra,
    google_sql_database.keyra,
  ]
}

resource "google_project_iam_member" "metric_writer" {
  count   = local.metrics ? 1 : 0
  project = var.project_id
  role    = "roles/monitoring.metricWriter"
  member  = google_service_account.run.member
}

# A warning, not a failure: the instance may still be starting after the retries.
check "health" {
  data "http" "health" {
    url = "${local.url}/actuator/health"

    retry {
      attempts     = 5
      min_delay_ms = 2000
    }
  }

  assert {
    condition     = data.http.health.status_code == 200 && jsondecode(data.http.health.response_body).status == "UP"
    error_message = "Keyra did not report healthy at ${local.url}/actuator/health. Check the Cloud Run logs in Cloud Logging."
  }
}
