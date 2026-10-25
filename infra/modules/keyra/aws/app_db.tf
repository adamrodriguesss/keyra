# Extra databases for your apps (app_databases). RDS has no API for databases or roles, so a small function in
# the VPC connects as the master user and creates them. Only exists while app_databases is non-empty.

data "archive_file" "app_db" {
  count = length(local.app_dbs) > 0 ? 1 : 0

  type        = "zip"
  source_dir  = "${path.module}/app_db"
  excludes    = ["__pycache__"]
  output_path = "${path.module}/.build/app_db.zip"
}

resource "aws_security_group" "app_db" {
  count = length(local.app_dbs) > 0 ? 1 : 0

  name        = "${var.name}-app-db"
  description = "Keyra app database init function"
  vpc_id      = local.vpc_id
}

resource "aws_vpc_security_group_egress_rule" "app_db" {
  count = length(local.app_dbs) > 0 ? 1 : 0

  security_group_id = aws_security_group.app_db[0].id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

# Without a NAT the private subnets have no way out, so Secrets Manager is reached through an endpoint.
resource "aws_security_group" "secretsmanager" {
  count = local.sm_endpoint ? 1 : 0

  name        = "${var.name}-secretsmanager"
  description = "Secrets Manager endpoint for the app database init function"
  vpc_id      = local.vpc_id
}

# The endpoint's private DNS answers for Secrets Manager across the whole VPC, so Keyra's tasks (which read
# their secrets at startup) come through it too, not just the function.
resource "aws_vpc_security_group_ingress_rule" "secretsmanager" {
  for_each = local.sm_endpoint ? { app_db = aws_security_group.app_db[0].id, task = aws_security_group.task.id } : {}

  security_group_id            = aws_security_group.secretsmanager[0].id
  referenced_security_group_id = each.value
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
}

resource "aws_vpc_endpoint" "secretsmanager" {
  count = local.sm_endpoint ? 1 : 0

  vpc_id              = local.vpc_id
  service_name        = "com.amazonaws.${var.region}.secretsmanager"
  vpc_endpoint_type   = "Interface"
  subnet_ids          = aws_subnet.private[*].id
  security_group_ids  = [aws_security_group.secretsmanager[0].id]
  private_dns_enabled = true
}

resource "aws_iam_role" "app_db" {
  count = length(local.app_dbs) > 0 ? 1 : 0

  name = "${var.name}-app-db"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Action = "sts:AssumeRole", Principal = { Service = "lambda.amazonaws.com" } }]
  })
}

resource "aws_iam_role_policy_attachment" "app_db" {
  count = length(local.app_dbs) > 0 ? 1 : 0

  role       = aws_iam_role.app_db[0].name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaVPCAccessExecutionRole"
}

# The master password and the apps' passwords; nothing else.
resource "aws_iam_role_policy" "app_db" {
  count = length(local.app_dbs) > 0 ? 1 : 0

  name = "secrets"
  role = aws_iam_role.app_db[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = "secretsmanager:GetSecretValue"
      Resource = concat([aws_secretsmanager_secret.db_password[0].arn], [for s in aws_secretsmanager_secret.app_db_password : s.arn])
    }]
  })
}

resource "aws_lambda_function" "app_db" {
  count = length(local.app_dbs) > 0 ? 1 : 0

  function_name    = "${var.name}-app-db"
  role             = aws_iam_role.app_db[0].arn
  runtime          = "python3.13"
  handler          = "index.handler"
  filename         = data.archive_file.app_db[0].output_path
  source_code_hash = data.archive_file.app_db[0].output_base64sha256
  timeout          = 60

  vpc_config {
    subnet_ids         = aws_subnet.private[*].id
    security_group_ids = [aws_security_group.app_db[0].id]
  }

  depends_on = [aws_iam_role_policy_attachment.app_db, aws_iam_role_policy.app_db]
}

# Runs on create and again when the password version changes (new secret version -> new input).
# Removing an entry runs nothing, so it never drops an app's data.
resource "aws_lambda_invocation" "app_db" {
  for_each = local.app_dbs

  function_name = aws_lambda_function.app_db[0].function_name
  input = jsonencode({
    database           = each.key
    host               = aws_db_instance.db[0].address
    port               = aws_db_instance.db[0].port
    master_user        = aws_db_instance.db[0].username
    master_secret_arn  = aws_secretsmanager_secret.db_password[0].arn
    app_secret_arn     = aws_secretsmanager_secret.app_db_password[each.key].arn
    app_secret_version = aws_secretsmanager_secret_version.app_db_password[each.key].version_id
  })

  depends_on = [
    aws_vpc_endpoint.secretsmanager,
    aws_vpc_security_group_ingress_rule.db,
    aws_vpc_security_group_ingress_rule.secretsmanager,
    aws_route_table_association.private,
    aws_nat_gateway.egress,
  ]
}
