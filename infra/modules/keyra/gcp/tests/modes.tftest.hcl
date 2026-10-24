# Offline checks that each database/egress mode creates only its own resources: terraform test.
# google is mocked, so "apply" here creates nothing and needs no GCP account.

# Mocked values must still pass the provider's own format checks.
mock_provider "google" {
  mock_data "google_project" {
    defaults = {
      number = "123456789"
    }
  }
  mock_resource "google_compute_network" {
    defaults = {
      id = "projects/acme/global/networks/keyra"
    }
  }
  mock_resource "google_compute_subnetwork" {
    defaults = {
      id = "projects/acme/regions/europe-west1/subnetworks/keyra-run"
    }
  }
  mock_resource "google_compute_address" {
    defaults = {
      self_link = "https://www.googleapis.com/compute/v1/projects/acme/regions/europe-west1/addresses/keyra-egress"
      address   = "203.0.113.10"
    }
  }
  mock_resource "google_service_account" {
    defaults = {
      email  = "keyra-run@acme.iam.gserviceaccount.com"
      member = "serviceAccount:keyra-run@acme.iam.gserviceaccount.com"
    }
  }
  mock_resource "google_sql_database_instance" {
    defaults = {
      private_ip_address = "10.20.0.3"
    }
  }
  mock_resource "google_secret_manager_secret" {
    defaults = {
      id = "projects/acme/secrets/keyra-secret"
    }
  }
  mock_resource "google_secret_manager_secret_version" {
    defaults = {
      version = "1"
    }
  }
}

mock_provider "http" {
  mock_data "http" {
    defaults = {
      status_code   = 200
      response_body = "{\"status\":\"UP\"}"
    }
  }
}

# random and tls run locally for real; mocks do not support ephemeral resources.

variables {
  project_id               = "acme"
  name                     = "keyra"
  region                   = "europe-west1"
  image                    = "europe-west1-docker.pkg.dev/acme/keyra/keyra:1"
  bootstrap_admin_email    = "admin@example.com"
  bootstrap_admin_password = "correct-horse-battery"
}

run "cloud_sql_by_default" {
  command = apply

  assert {
    condition     = length(google_sql_database_instance.db) == 1 && length(google_compute_network.vpc) == 1
    error_message = "Cloud SQL and its VPC should be created when no external database is given."
  }
  assert {
    condition     = length(google_compute_router_nat.egress) == 0
    error_message = "No NAT without static_ip."
  }
  assert {
    condition     = output.url == "https://keyra-123456789.europe-west1.run.app"
    error_message = "URL should be Cloud Run's deterministic run.app URL."
  }
  assert {
    condition     = length(google_cloud_run_v2_service.keyra.template[0].containers) == 1 && length(google_project_iam_member.metric_writer) == 0
    error_message = "Metrics are opt-in: no collector sidecar or metric writer role by default."
  }
  assert {
    condition     = google_cloud_run_v2_service.keyra.template[0].containers[0].resources[0].cpu_idle
    error_message = "Without metrics, CPU should only be allocated during requests."
  }
}

run "external_database_public" {
  command = apply

  variables {
    external_database = {
      jdbc_url           = "jdbc:postgresql://db.example.com:5432/keyra?sslmode=verify-full"
      username           = "keyra"
      password_secret_id = "keyra-db-password"
    }
  }

  assert {
    condition     = length(google_sql_database_instance.db) == 0 && length(google_compute_network.vpc) == 0
    error_message = "No Cloud SQL and no VPC for a public external database."
  }
  assert {
    condition     = length(google_cloud_run_v2_service.keyra.template[0].vpc_access) == 0
    error_message = "Cloud Run should use plain internet egress."
  }
  assert {
    condition     = !contains(keys(google_project_service.apis), "compute.googleapis.com")
    error_message = "No networking is created, so the Compute API should not be enabled."
  }
  assert {
    condition     = google_secret_manager_secret_iam_member.run["db_password"].secret_id == "keyra-db-password"
    error_message = "The caller's existing password secret should be granted to Cloud Run."
  }
  assert {
    condition     = local.db_url == "jdbc:postgresql://db.example.com:5432/keyra?sslmode=verify-full"
    error_message = "The external JDBC URL should be passed through."
  }
}

run "external_database_static_ip" {
  command = apply

  variables {
    external_database = {
      jdbc_url           = "jdbc:postgresql://db.example.com:5432/keyra?sslmode=require"
      username           = "keyra"
      password_secret_id = "keyra-db-password"
    }
    egress = { static_ip = true }
  }

  assert {
    condition     = length(google_compute_router_nat.egress) == 1 && length(google_sql_database_instance.db) == 0
    error_message = "Static IP should create NAT but no Cloud SQL."
  }
  assert {
    condition     = contains(keys(google_project_service.apis), "compute.googleapis.com")
    error_message = "The NAT and static IP need the Compute API."
  }
  assert {
    condition     = google_cloud_run_v2_service.keyra.template[0].vpc_access[0].egress == "ALL_TRAFFIC"
    error_message = "All traffic must go through NAT to get the static IP."
  }
}

run "external_database_private_network" {
  command = apply

  variables {
    external_database = {
      jdbc_url           = "jdbc:postgresql://10.0.0.5:5432/keyra?sslmode=require"
      username           = "keyra"
      password_secret_id = "projects/acme/secrets/keyra-db-password"
    }
    egress = {
      network    = "projects/acme/global/networks/corp"
      subnetwork = "projects/acme/regions/europe-west1/subnetworks/keyra-run"
    }
  }

  assert {
    condition     = length(google_compute_network.vpc) == 0 && length(google_compute_router_nat.egress) == 0
    error_message = "The caller's network replaces the module VPC."
  }
  assert {
    condition = (
      google_cloud_run_v2_service.keyra.template[0].vpc_access[0].network_interfaces[0].network == "projects/acme/global/networks/corp"
      && google_cloud_run_v2_service.keyra.template[0].vpc_access[0].egress == "PRIVATE_RANGES_ONLY"
    )
    error_message = "Egress should go into the caller's network for private ranges only."
  }
}

run "rejects_external_database_without_tls" {
  command = plan

  variables {
    external_database = {
      jdbc_url           = "jdbc:postgresql://db.example.com:5432/keyra"
      username           = "keyra"
      password_secret_id = "keyra-db-password"
    }
  }

  expect_failures = [var.external_database]
}

run "rejects_private_network_with_cloud_sql" {
  command = plan

  variables {
    egress = {
      network    = "projects/acme/global/networks/corp"
      subnetwork = "projects/acme/regions/europe-west1/subnetworks/keyra-run"
    }
  }

  expect_failures = [var.egress]
}

run "app_databases_on_cloud_sql" {
  command = apply

  variables {
    app_databases = {
      billing = {}
      crm     = { password_version = 2 }
    }
  }

  assert {
    condition     = length(google_sql_database.app) == 2 && length(google_sql_user.app) == 2 && length(google_secret_manager_secret.app_db_password) == 2
    error_message = "Each app should get a database, a user and a password secret."
  }
  assert {
    condition     = length(google_secret_manager_secret_iam_member.run) == 3
    error_message = "Keyra must not be granted the apps' secrets."
  }
  assert {
    condition     = output.app_databases["crm"].jdbc_url == "jdbc:postgresql://10.20.0.3:5432/crm?sslmode=require"
    error_message = "The output should carry the private-IP JDBC URL."
  }
}

run "app_databases_rejected_with_external_db" {
  command = plan

  variables {
    external_database = {
      jdbc_url           = "jdbc:postgresql://db.example.com:5432/keyra?sslmode=require"
      username           = "keyra"
      password_secret_id = "keyra-db-password"
    }
    app_databases = { billing = {} }
  }

  expect_failures = [var.app_databases]
}

run "app_database_initial" {
  command = apply

  variables {
    app_databases = { billing = {} }
  }
}

run "app_database_added_later" {
  command = apply

  variables {
    app_databases = {
      billing = {}
      crm     = {}
    }
  }

  assert {
    condition     = contains(keys(google_sql_database.app), "crm") && contains(keys(google_sql_database.app), "billing")
    error_message = "The new database should be added next to the existing one."
  }
  assert {
    condition     = length(google_sql_database_instance.db) == 1 && length(google_sql_database.keyra) == 1
    error_message = "The instance and Keyra's database should be untouched."
  }
}

run "published_image" {
  command = apply

  variables {
    image = null
  }

  assert {
    condition     = length(google_artifact_registry_repository.ghcr) == 1
    error_message = "The GHCR proxy repository should exist when no image is set."
  }
  assert {
    condition     = google_cloud_run_v2_service.keyra.template[0].containers[0].image == "europe-west1-docker.pkg.dev/acme/keyra-ghcr/adamrodriguesss/keyra:0.1.0"
    error_message = "The service should run the published release through the proxy."
  }
  assert {
    condition     = contains(keys(google_project_service.apis), "artifactregistry.googleapis.com")
    error_message = "Artifact Registry API should be enabled."
  }
}

run "custom_image" {
  command = apply

  assert {
    condition     = length(google_artifact_registry_repository.ghcr) == 0 && !contains(keys(google_project_service.apis), "artifactregistry.googleapis.com")
    error_message = "A custom image needs no proxy repository or Artifact Registry API."
  }
}

run "generated_password" {
  command = apply

  variables {
    bootstrap_admin_password = null
  }

  assert {
    condition     = output.bootstrap_admin_password_secret == "keyra-bootstrap-admin-password"
    error_message = "The secret ID should be exposed so the generated password can be read."
  }
}

run "metrics_enabled" {
  command = apply

  variables {
    metrics_enabled = true
  }

  assert {
    condition     = join(",", [for c in google_cloud_run_v2_service.keyra.template[0].containers : c.name]) == "keyra,otel-collector"
    error_message = "Metrics should add the collector sidecar."
  }
  assert {
    condition     = google_cloud_run_v2_service.keyra.template[0].containers[0].depends_on == tolist(["otel-collector"])
    error_message = "Keyra should start after the collector."
  }
  assert {
    condition     = alltrue([for c in google_cloud_run_v2_service.keyra.template[0].containers : !c.resources[0].cpu_idle])
    error_message = "Pushing metrics needs CPU always allocated."
  }
  assert {
    condition     = local.env.KEYRA_METRICS_ENABLED == "true"
    error_message = "Keyra should be told to push metrics."
  }
  assert {
    condition     = local.collector_config.extensions.health_check.endpoint == "0.0.0.0:13133"
    error_message = "Cloud Run's startup probe can't reach a health check bound to localhost."
  }
  assert {
    condition     = length(google_project_iam_member.metric_writer) == 1 && contains(keys(google_project_service.apis), "monitoring.googleapis.com")
    error_message = "The service account needs the metric writer role and the Monitoring API."
  }
}
