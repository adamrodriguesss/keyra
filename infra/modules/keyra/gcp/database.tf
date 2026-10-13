# Cloud SQL, only when no external_database is given. Private IP only, TLS required.
resource "google_compute_global_address" "sql" {
  count = local.create_db ? 1 : 0

  project       = var.project_id
  name          = "${var.name}-sql"
  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  prefix_length = 20
  network       = google_compute_network.vpc[0].id
}

resource "google_service_networking_connection" "sql" {
  count = local.create_db ? 1 : 0

  network                 = google_compute_network.vpc[0].id
  service                 = "servicenetworking.googleapis.com"
  reserved_peering_ranges = [google_compute_global_address.sql[0].name]
  # Removing the peering fails while Google still holds the range; leave it to the VPC's deletion.
  deletion_policy = "ABANDON"
}

resource "google_sql_database_instance" "db" {
  count = local.create_db ? 1 : 0

  project             = var.project_id
  name                = var.name
  region              = var.region
  database_version    = var.cloud_sql_version
  deletion_protection = var.deletion_protection

  settings {
    tier                        = var.db_tier
    edition                     = "ENTERPRISE"
    disk_autoresize             = true
    deletion_protection_enabled = var.deletion_protection

    ip_configuration {
      ipv4_enabled    = false
      private_network = google_compute_network.vpc[0].id
      ssl_mode        = "ENCRYPTED_ONLY"
    }

    backup_configuration {
      enabled                        = true
      point_in_time_recovery_enabled = true
    }
  }

  depends_on = [google_service_networking_connection.sql]
}

resource "google_sql_database" "keyra" {
  count = local.create_db ? 1 : 0

  project  = var.project_id
  name     = "keyra"
  instance = google_sql_database_instance.db[0].name
}

# Generated per run and never stored; written only when db_password_version changes.
ephemeral "random_password" "db" {
  count = local.create_db ? 1 : 0

  length  = 32
  special = false
}

resource "google_sql_user" "keyra" {
  count = local.create_db ? 1 : 0

  project             = var.project_id
  name                = "keyra"
  instance            = google_sql_database_instance.db[0].name
  password_wo         = ephemeral.random_password.db[0].result
  password_wo_version = var.db_password_version
}
