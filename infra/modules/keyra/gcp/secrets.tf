# Secret values go in through write-only attributes, so none of them reach Terraform state.

ephemeral "tls_private_key" "jwt" {
  algorithm = "RSA"
  rsa_bits  = 2048
}

# Rotates together with the key, so tokens name the key that signed them.
resource "random_id" "jwt_key_id" {
  byte_length = 8
  keepers = {
    version = var.jwt_key_version
  }
}

resource "google_secret_manager_secret" "jwt_private_key" {
  project   = var.project_id
  secret_id = "${var.name}-jwt-private-key"
  replication {
    auto {}
  }
  depends_on = [google_project_service.apis]
}

resource "google_secret_manager_secret_version" "jwt_private_key" {
  secret                 = google_secret_manager_secret.jwt_private_key.id
  secret_data_wo         = ephemeral.tls_private_key.jwt.private_key_pem_pkcs8
  secret_data_wo_version = var.jwt_key_version
}

resource "google_secret_manager_secret" "bootstrap_admin_password" {
  project   = var.project_id
  secret_id = "${var.name}-bootstrap-admin-password"
  replication {
    auto {}
  }
  depends_on = [google_project_service.apis]
}

# Always generated (count can't depend on an ephemeral variable), used only when the password is null. Never stored; written only on the first apply (secret_data_wo_version stays 1).
ephemeral "random_password" "bootstrap_admin" {
  length  = 24
  special = false
}

# Only read on the first startup, while no managers exist.
resource "google_secret_manager_secret_version" "bootstrap_admin_password" {
  secret                 = google_secret_manager_secret.bootstrap_admin_password.id
  secret_data_wo         = var.bootstrap_admin_password != null ? var.bootstrap_admin_password : ephemeral.random_password.bootstrap_admin.result
  secret_data_wo_version = 1
}

resource "google_secret_manager_secret" "db_password" {
  count = local.create_db ? 1 : 0

  project   = var.project_id
  secret_id = "${var.name}-db-password"
  replication {
    auto {}
  }
  depends_on = [google_project_service.apis]
}

resource "google_secret_manager_secret_version" "db_password" {
  count = local.create_db ? 1 : 0

  secret                 = google_secret_manager_secret.db_password[0].id
  secret_data_wo         = ephemeral.random_password.db[0].result
  secret_data_wo_version = var.db_password_version
}

# Your apps' passwords: Keyra never reads these, so they are not in the Cloud Run grants below.
resource "google_secret_manager_secret" "app_db_password" {
  for_each = local.create_db ? var.app_databases : {}

  project   = var.project_id
  secret_id = "${var.name}-${each.key}-db-password"
  replication {
    auto {}
  }
  depends_on = [google_project_service.apis]
}

resource "google_secret_manager_secret_version" "app_db_password" {
  for_each = local.create_db ? var.app_databases : {}

  secret                 = google_secret_manager_secret.app_db_password[each.key].id
  secret_data_wo         = ephemeral.random_password.app[each.key].result
  secret_data_wo_version = each.value.password_version
}

# Read access to exactly these secrets, including an external database's existing one.
resource "google_secret_manager_secret_iam_member" "run" {
  for_each = {
    jwt_private_key          = google_secret_manager_secret.jwt_private_key.id
    bootstrap_admin_password = google_secret_manager_secret.bootstrap_admin_password.id
    db_password              = local.db_password_secret
  }

  project   = var.project_id
  secret_id = each.value
  role      = "roles/secretmanager.secretAccessor"
  member    = google_service_account.run.member
}
