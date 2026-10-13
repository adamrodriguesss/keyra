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
