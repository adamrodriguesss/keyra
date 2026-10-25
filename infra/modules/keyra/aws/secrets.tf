# Secret values go in through write-only attributes, so none of them reach Terraform state.

locals {
  # Without deletion protection, deleted secrets go at once, so a throwaway instance can be recreated under the same name.
  secret_recovery_days = var.deletion_protection ? 30 : 0
}

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

resource "aws_secretsmanager_secret" "jwt_private_key" {
  name                    = "${var.name}-jwt-private-key"
  recovery_window_in_days = local.secret_recovery_days
}

resource "aws_secretsmanager_secret_version" "jwt_private_key" {
  secret_id                = aws_secretsmanager_secret.jwt_private_key.id
  secret_string_wo         = ephemeral.tls_private_key.jwt.private_key_pem_pkcs8
  secret_string_wo_version = var.jwt_key_version
}

resource "aws_secretsmanager_secret" "bootstrap_admin_password" {
  name                    = "${var.name}-bootstrap-admin-password"
  recovery_window_in_days = local.secret_recovery_days
}

# Always generated (count can't depend on an ephemeral variable), used only when the password is null. Never stored; written only on the first apply (secret_string_wo_version stays 1).
ephemeral "random_password" "bootstrap_admin" {
  length  = 24
  special = false
}

# Only read on the first startup, while no managers exist.
resource "aws_secretsmanager_secret_version" "bootstrap_admin_password" {
  secret_id                = aws_secretsmanager_secret.bootstrap_admin_password.id
  secret_string_wo         = var.bootstrap_admin_password != null ? var.bootstrap_admin_password : ephemeral.random_password.bootstrap_admin.result
  secret_string_wo_version = 1
}

resource "aws_secretsmanager_secret" "db_password" {
  count = local.create_db ? 1 : 0

  name                    = "${var.name}-db-password"
  recovery_window_in_days = local.secret_recovery_days
}

resource "aws_secretsmanager_secret_version" "db_password" {
  count = local.create_db ? 1 : 0

  secret_id                = aws_secretsmanager_secret.db_password[0].id
  secret_string_wo         = ephemeral.random_password.db[0].result
  secret_string_wo_version = var.db_password_version

  # The password is generated anew each run, so the secret must be written in the run that sets it on RDS:
  # only after the instance exists (a failed create writes neither), and again whenever the instance is replaced.
  depends_on = [aws_db_instance.db]

  lifecycle {
    replace_triggered_by = [aws_db_instance.db[count.index].resource_id]
  }
}

# Your apps' passwords: Keyra never reads these, so they are not in the execution role's grant below.
resource "aws_secretsmanager_secret" "app_db_password" {
  for_each = local.app_dbs

  name                    = "${var.name}-${each.key}-db-password"
  recovery_window_in_days = local.secret_recovery_days
}

ephemeral "random_password" "app" {
  for_each = local.app_dbs

  length  = 32
  special = false
}

resource "aws_secretsmanager_secret_version" "app_db_password" {
  for_each = local.app_dbs

  secret_id                = aws_secretsmanager_secret.app_db_password[each.key].id
  secret_string_wo         = ephemeral.random_password.app[each.key].result
  secret_string_wo_version = each.value.password_version
}
