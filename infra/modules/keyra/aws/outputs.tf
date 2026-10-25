output "url" {
  description = "Public HTTPS URL of the instance (also the token issuer)."
  value       = local.url
}

output "egress_ip" {
  description = "Static outbound IP to allowlist on an external database. null unless egress.static_ip."
  value       = one(aws_eip.egress[*].public_ip)
}

output "alb_dns_name" {
  description = "Load balancer hostname: what domain_name points at, or the internal origin behind CloudFront."
  value       = aws_lb.keyra.dns_name
}

output "rds_endpoint" {
  description = "RDS hostname. null with an external database."
  value       = one(aws_db_instance.db[*].address)
}

output "vpc_id" {
  description = "VPC the tasks run in; attach your own apps here to reach the database."
  value       = local.vpc_id
}

output "task_subnet_ids" {
  description = "Subnets the tasks run in."
  value       = local.task_subnet_ids
}

output "task_security_group_id" {
  description = "Security group of the tasks: allow it on an external database's security group or firewall."
  value       = aws_security_group.task.id
}

output "app_databases" {
  description = "Per app database: name, user, password secret and JDBC URL. Empty with an external database."
  value = {
    for k, _ in aws_lambda_invocation.app_db : k => {
      database           = k
      user               = k
      password_secret_id = aws_secretsmanager_secret.app_db_password[k].arn
      jdbc_url           = "jdbc:postgresql://${aws_db_instance.db[0].address}:${aws_db_instance.db[0].port}/${k}?sslmode=require"
    }
  }
}

output "bootstrap_admin_password_secret" {
  description = "Secrets Manager secret name holding the first admin's password."
  value       = aws_secretsmanager_secret.bootstrap_admin_password.name
}
