# RDS, only when no external_database is given. Private subnets only, TLS required.
resource "aws_db_subnet_group" "db" {
  count = local.create_db ? 1 : 0

  name       = var.name
  subnet_ids = aws_subnet.private[*].id
}

resource "aws_db_parameter_group" "db" {
  count = local.create_db ? 1 : 0

  name   = var.name
  family = "postgres${split(".", var.postgres_version)[0]}"

  # Static, so AWS records it as pending-reboot; a new instance starts with it in force.
  parameter {
    name         = "rds.force_ssl"
    value        = "1"
    apply_method = "pending-reboot"
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_security_group" "db" {
  count = local.create_db ? 1 : 0

  name        = "${var.name}-db"
  description = "Keyra RDS"
  vpc_id      = local.vpc_id
}

# Keyra's tasks, and the init function that creates app databases.
resource "aws_vpc_security_group_ingress_rule" "db" {
  for_each = local.create_db ? merge(
    { task = aws_security_group.task.id },
    length(local.app_dbs) > 0 ? { app_db = aws_security_group.app_db[0].id } : {},
  ) : {}

  security_group_id            = aws_security_group.db[0].id
  referenced_security_group_id = each.value
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}

# Generated per run and never stored. RDS and the secret must get it in the same run: see the secret version.
ephemeral "random_password" "db" {
  count = local.create_db ? 1 : 0

  length  = 32
  special = false
}

# RDS has no API for other roles, so Keyra connects as the master user, which owns the keyra database.
resource "aws_db_instance" "db" {
  count = local.create_db ? 1 : 0

  identifier                 = var.name
  engine                     = "postgres"
  engine_version             = var.postgres_version
  auto_minor_version_upgrade = true
  instance_class             = var.db_tier

  allocated_storage     = 20
  max_allocated_storage = 100
  storage_type          = "gp3"
  storage_encrypted     = true

  db_name             = "keyra"
  username            = "keyra"
  password_wo         = ephemeral.random_password.db[0].result
  password_wo_version = var.db_password_version

  db_subnet_group_name   = aws_db_subnet_group.db[0].name
  parameter_group_name   = aws_db_parameter_group.db[0].name
  vpc_security_group_ids = [aws_security_group.db[0].id]
  publicly_accessible    = false

  # Daily backups with point-in-time recovery over the retention window.
  backup_retention_period   = var.db_backup_retention_days
  copy_tags_to_snapshot     = true
  deletion_protection       = var.deletion_protection
  skip_final_snapshot       = !var.deletion_protection
  final_snapshot_identifier = "${var.name}-final"
}
