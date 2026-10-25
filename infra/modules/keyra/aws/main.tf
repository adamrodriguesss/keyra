# Every mode decision lives here; resources only read these locals.
locals {
  create_db   = var.external_database == null
  use_own_vpc = var.egress.vpc_id == null
  use_nat     = var.egress.static_ip
  metrics     = var.metrics_enabled
  # With a domain: a public load balancer with an ACM certificate. Without: CloudFront in front of an internal one.
  use_domain = var.domain_name != null
  app_dbs    = local.create_db ? var.app_databases : {}
  # The init function runs in private subnets: through the NAT when there is one, else a Secrets Manager endpoint.
  sm_endpoint = length(local.app_dbs) > 0 && !local.use_nat

  # Where tasks and the load balancer run: the module VPC (tasks public, or private behind the NAT), or the caller's VPC.
  vpc_id          = local.use_own_vpc ? one(aws_vpc.this[*].id) : var.egress.vpc_id
  alb_subnet_ids  = local.use_own_vpc ? (local.use_domain ? aws_subnet.public[*].id : aws_subnet.private[*].id) : var.egress.alb_subnet_ids
  task_subnet_ids = local.use_own_vpc ? (local.use_nat ? aws_subnet.private[*].id : aws_subnet.public[*].id) : var.egress.task_subnet_ids
  task_public_ip  = local.use_own_vpc && !local.use_nat

  db_url = coalesce(
    try(var.external_database.jdbc_url, null),
    try("jdbc:postgresql://${aws_db_instance.db[0].address}:${aws_db_instance.db[0].port}/keyra?sslmode=require", null),
  )
  db_user            = coalesce(try(var.external_database.username, null), one(aws_db_instance.db[*].username))
  db_password_secret = coalesce(try(var.external_database.password_secret_id, null), one(aws_secretsmanager_secret.db_password[*].arn))

  # One tag pins both this module and the image it runs; the release workflow checks they match.
  keyra_version = "0.3.1"
  image         = coalesce(var.image, "ghcr.io/adamrodriguesss/keyra:${local.keyra_version}")

  # Known before the tasks exist (the domain, or the distribution, which is created first), so it can be the token issuer.
  url = local.use_domain ? "https://${var.domain_name}" : "https://${aws_cloudfront_distribution.keyra[0].domain_name}"
}
