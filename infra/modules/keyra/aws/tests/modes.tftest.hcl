# Offline checks that each database/egress mode creates only its own resources: terraform test.
# aws is mocked, so "apply" here creates nothing and needs no AWS account.

# Mocked values must still pass the provider's own format checks.
mock_provider "aws" {
  mock_data "aws_availability_zones" {
    defaults = {
      names = ["eu-west-1a", "eu-west-1b", "eu-west-1c"]
    }
  }
  mock_resource "aws_db_instance" {
    defaults = {
      address = "keyra.abc123.eu-west-1.rds.amazonaws.com"
      port    = 5432
    }
  }
  mock_resource "aws_secretsmanager_secret" {
    defaults = {
      arn = "arn:aws:secretsmanager:eu-west-1:123456789012:secret:keyra-secret-AbCdEf"
    }
  }
  mock_resource "aws_secretsmanager_secret_version" {
    defaults = {
      version_id = "11111111-2222-3333-4444-555555555555"
    }
  }
  mock_resource "aws_eip" {
    defaults = {
      public_ip = "203.0.113.10"
    }
  }
  mock_resource "aws_iam_role" {
    defaults = {
      arn = "arn:aws:iam::123456789012:role/keyra"
    }
  }
  mock_resource "aws_lb" {
    defaults = {
      arn        = "arn:aws:elasticloadbalancing:eu-west-1:123456789012:loadbalancer/app/keyra/50dc6c495c0c9188"
      arn_suffix = "app/keyra/50dc6c495c0c9188"
      dns_name   = "keyra-123.eu-west-1.elb.amazonaws.com"
      zone_id    = "Z32O12XQLNTSW2"
    }
  }
  mock_resource "aws_lb_target_group" {
    defaults = {
      arn        = "arn:aws:elasticloadbalancing:eu-west-1:123456789012:targetgroup/keyra/73e2d6bc24d8a067"
      arn_suffix = "targetgroup/keyra/73e2d6bc24d8a067"
    }
  }
  mock_resource "aws_acm_certificate" {
    defaults = {
      arn = "arn:aws:acm:eu-west-1:123456789012:certificate/12345678-1234-1234-1234-123456789012"
      domain_validation_options = [{
        domain_name           = "keyra.example.com"
        resource_record_name  = "_abc.keyra.example.com."
        resource_record_type  = "CNAME"
        resource_record_value = "_xyz.acm-validations.aws."
      }]
    }
  }
  mock_resource "aws_acm_certificate_validation" {
    defaults = {
      certificate_arn = "arn:aws:acm:eu-west-1:123456789012:certificate/12345678-1234-1234-1234-123456789012"
    }
  }
  mock_resource "aws_cloudwatch_log_group" {
    defaults = {
      arn = "arn:aws:logs:eu-west-1:123456789012:log-group:/keyra/keyra"
    }
  }
  mock_resource "aws_ecs_cluster" {
    defaults = {
      arn = "arn:aws:ecs:eu-west-1:123456789012:cluster/keyra"
    }
  }
  mock_resource "aws_ecs_task_definition" {
    defaults = {
      arn = "arn:aws:ecs:eu-west-1:123456789012:task-definition/keyra:1"
    }
  }
  mock_resource "aws_cloudfront_distribution" {
    defaults = {
      arn         = "arn:aws:cloudfront::123456789012:distribution/E2QWRUHAPOMQZL"
      domain_name = "d111111abcdef8.cloudfront.net"
    }
  }
  mock_resource "aws_cloudfront_vpc_origin" {
    defaults = {
      arn = "arn:aws:cloudfront::123456789012:vpcorigin/vo_abc123"
    }
  }
  mock_data "aws_security_group" {
    defaults = {
      id = "sg-0cf0cf0cf0cf0cf00"
    }
  }
  mock_resource "aws_lambda_function" {
    defaults = {
      arn = "arn:aws:lambda:eu-west-1:123456789012:function:keyra-app-db"
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

# random, tls and archive run locally for real (mocks do not support ephemeral resources).
variables {
  name                     = "keyra"
  region                   = "eu-west-1"
  domain_name              = "keyra.example.com"
  route53_zone_id          = "Z0123456789ABCDEFGHIJ"
  bootstrap_admin_email    = "admin@example.com"
  bootstrap_admin_password = "correct-horse-battery"
}

run "rds_by_default" {
  command = apply

  assert {
    condition     = length(aws_db_instance.db) == 1 && !aws_db_instance.db[0].publicly_accessible && aws_db_instance.db[0].storage_encrypted && aws_db_instance.db[0].backup_retention_period == 7
    error_message = "RDS should be created, private and encrypted."
  }
  assert {
    condition     = contains([for p in aws_db_parameter_group.db[0].parameter : "${p.name}=${p.value}"], "rds.force_ssl=1")
    error_message = "RDS should refuse connections without TLS."
  }
  assert {
    condition     = local.env.KEYRA_DB_URL == "jdbc:postgresql://keyra.abc123.eu-west-1.rds.amazonaws.com:5432/keyra?sslmode=require"
    error_message = "Keyra should reach RDS over TLS."
  }
  assert {
    condition     = output.url == "https://keyra.example.com" && local.env.KEYRA_ISSUER_URL == output.url
    error_message = "The URL is the domain, known up front, so it is also the token issuer."
  }
  assert {
    condition     = length(aws_nat_gateway.egress) == 0 && aws_ecs_service.keyra.network_configuration[0].assign_public_ip && output.egress_ip == null
    error_message = "Without static_ip, tasks get public IPs in public subnets instead of a NAT gateway."
  }
  assert {
    condition     = local.secret_env.KEYRA_JWT_PRIVATE_KEY == "arn:aws:secretsmanager:eu-west-1:123456789012:secret:keyra-secret-AbCdEf:::11111111-2222-3333-4444-555555555555"
    error_message = "Secrets should be pinned to a version, so a rotation rolls out a new task definition."
  }
  assert {
    condition     = [for c in jsondecode(aws_ecs_task_definition.keyra.container_definitions) : c.name] == ["keyra"]
    error_message = "Metrics are opt-in: no collector sidecar by default."
  }
  assert {
    condition     = length(aws_iam_role_policy.task_metrics) == 0 && length(aws_lambda_function.app_db) == 0
    error_message = "No metrics permissions or database-init function by default."
  }
}

run "https_ingress" {
  command = apply

  assert {
    condition     = aws_lb_listener.https[0].certificate_arn == aws_acm_certificate_validation.keyra[0].certificate_arn && aws_lb_listener.https[0].port == 443
    error_message = "HTTPS should use the validated ACM certificate."
  }
  assert {
    condition     = aws_lb_listener.http.default_action[0].type == "redirect" && aws_lb_listener.http.default_action[0].redirect[0].protocol == "HTTPS" && !aws_lb.keyra.internal
    error_message = "Plain HTTP should redirect to HTTPS."
  }
  assert {
    condition     = aws_route53_record.keyra[0].name == "keyra.example.com" && aws_route53_record.keyra[0].zone_id == "Z0123456789ABCDEFGHIJ"
    error_message = "The domain should alias the load balancer."
  }
  assert {
    condition     = length(aws_cloudfront_distribution.keyra) == 0
    error_message = "With a domain, there is no CloudFront in front."
  }
  assert {
    condition     = aws_lb_target_group.keyra.health_check[0].path == "/actuator/health/readiness" && aws_lb_target_group.keyra.target_type == "ip"
    error_message = "The load balancer should route to ready tasks only."
  }
}

run "external_database_public" {
  command = apply

  variables {
    external_database = {
      jdbc_url           = "jdbc:postgresql://db.example.com:5432/keyra?sslmode=require"
      username           = "keyra"
      password_secret_id = "arn:aws:secretsmanager:eu-west-1:123456789012:secret:ext-db-password-XyZ123"
    }
  }

  assert {
    condition     = length(aws_db_instance.db) == 0 && length(aws_secretsmanager_secret.db_password) == 0
    error_message = "An external database creates no RDS and no password secret."
  }
  assert {
    condition     = local.env.KEYRA_DB_URL == "jdbc:postgresql://db.example.com:5432/keyra?sslmode=require" && local.env.KEYRA_DB_USER == "keyra"
    error_message = "Keyra should use the external database."
  }
  assert {
    condition     = local.secret_env.KEYRA_DB_PASSWORD == "arn:aws:secretsmanager:eu-west-1:123456789012:secret:ext-db-password-XyZ123"
    error_message = "The external password should come from the caller's secret."
  }
  assert {
    condition     = contains(jsondecode(aws_iam_role_policy.execution_secrets.policy).Statement[0].Resource, "arn:aws:secretsmanager:eu-west-1:123456789012:secret:ext-db-password-XyZ123")
    error_message = "The task should be able to read the caller's secret."
  }
  assert {
    condition     = length(aws_nat_gateway.egress) == 0 && length(aws_vpc.this) == 1
    error_message = "A public external database needs the module VPC but no NAT."
  }
}

run "external_database_static_ip" {
  command = apply

  variables {
    external_database = {
      jdbc_url           = "jdbc:postgresql://db.example.com:5432/keyra?sslmode=require"
      username           = "keyra"
      password_secret_id = "arn:aws:secretsmanager:eu-west-1:123456789012:secret:ext-db-password-XyZ123"
    }
    egress = {
      static_ip = true
    }
  }

  assert {
    condition     = length(aws_nat_gateway.egress) == 1 && output.egress_ip == "203.0.113.10"
    error_message = "static_ip should route egress through a NAT gateway with an Elastic IP."
  }
  assert {
    condition     = !aws_ecs_service.keyra.network_configuration[0].assign_public_ip && toset(aws_ecs_service.keyra.network_configuration[0].subnets) == toset(aws_subnet.private[*].id)
    error_message = "With static_ip, tasks run in private subnets behind the NAT."
  }
}

run "external_database_own_vpc" {
  command = apply

  variables {
    external_database = {
      jdbc_url           = "jdbc:postgresql://10.0.5.4:5432/keyra?sslmode=verify-full"
      username           = "keyra"
      password_secret_id = "arn:aws:secretsmanager:eu-west-1:123456789012:secret:ext-db-password-XyZ123"
    }
    egress = {
      vpc_id          = "vpc-0123456789abcdef0"
      task_subnet_ids = ["subnet-0aaa", "subnet-0bbb"]
      alb_subnet_ids  = ["subnet-0ccc", "subnet-0ddd"]
    }
  }

  assert {
    condition     = length(aws_vpc.this) == 0 && length(aws_subnet.public) == 0 && length(aws_nat_gateway.egress) == 0
    error_message = "Your own VPC means the module creates no networking."
  }
  assert {
    condition     = toset(aws_ecs_service.keyra.network_configuration[0].subnets) == toset(["subnet-0aaa", "subnet-0bbb"]) && !aws_ecs_service.keyra.network_configuration[0].assign_public_ip
    error_message = "Tasks should run in your subnets, without public IPs."
  }
  assert {
    condition     = toset(aws_lb.keyra.subnets) == toset(["subnet-0ccc", "subnet-0ddd"]) && aws_lb_target_group.keyra.vpc_id == "vpc-0123456789abcdef0"
    error_message = "The load balancer should live in your public subnets."
  }
}

run "rejects_external_database_without_tls" {
  command = plan

  variables {
    external_database = {
      jdbc_url           = "jdbc:postgresql://db.example.com:5432/keyra"
      username           = "keyra"
      password_secret_id = "arn:aws:secretsmanager:eu-west-1:123456789012:secret:ext-db-password-XyZ123"
    }
  }

  expect_failures = [var.external_database]
}

run "rejects_secret_name_instead_of_arn" {
  command = plan

  variables {
    external_database = {
      jdbc_url           = "jdbc:postgresql://db.example.com:5432/keyra?sslmode=require"
      username           = "keyra"
      password_secret_id = "ext-db-password"
    }
  }

  expect_failures = [var.external_database]
}

run "rejects_own_vpc_with_rds" {
  command = plan

  variables {
    egress = {
      vpc_id          = "vpc-0123456789abcdef0"
      task_subnet_ids = ["subnet-0aaa"]
      alb_subnet_ids  = ["subnet-0ccc", "subnet-0ddd"]
    }
  }

  expect_failures = [var.egress]
}

run "rejects_partial_own_vpc" {
  command = plan

  variables {
    external_database = {
      jdbc_url           = "jdbc:postgresql://db.example.com:5432/keyra?sslmode=require"
      username           = "keyra"
      password_secret_id = "arn:aws:secretsmanager:eu-west-1:123456789012:secret:ext-db-password-XyZ123"
    }
    egress = {
      vpc_id = "vpc-0123456789abcdef0"
    }
  }

  expect_failures = [var.egress]
}

run "app_databases_on_rds" {
  command = apply

  variables {
    app_databases = {
      orders = {}
    }
  }

  assert {
    condition     = length(aws_lambda_function.app_db) == 1 && keys(aws_lambda_invocation.app_db) == ["orders"]
    error_message = "Each app database should be created by the in-VPC init function."
  }
  assert {
    condition     = !anytrue([for k, v in jsondecode(aws_lambda_invocation.app_db["orders"].input) : can(regex("(?i)password$", k))])
    error_message = "The invocation input is stored in state, so it must name secrets, never carry passwords."
  }
  assert {
    condition     = length(aws_vpc_endpoint.secretsmanager) == 1 && toset(keys(aws_vpc_security_group_ingress_rule.secretsmanager)) == toset(["app_db", "task"])
    error_message = "Without NAT, the function reaches Secrets Manager through a VPC endpoint, whose private DNS also catches the tasks, so it admits both."
  }
  assert {
    condition     = output.app_databases.orders.jdbc_url == "jdbc:postgresql://keyra.abc123.eu-west-1.rds.amazonaws.com:5432/orders?sslmode=require"
    error_message = "Each app database should expose its JDBC URL."
  }
  assert {
    condition     = length(jsondecode(aws_iam_role_policy.execution_secrets.policy).Statement[0].Resource) == 3
    error_message = "Keyra reads only its own three secrets, never your apps' passwords."
  }
}

run "app_databases_rejected_with_external_db" {
  command = plan

  variables {
    external_database = {
      jdbc_url           = "jdbc:postgresql://db.example.com:5432/keyra?sslmode=require"
      username           = "keyra"
      password_secret_id = "arn:aws:secretsmanager:eu-west-1:123456789012:secret:ext-db-password-XyZ123"
    }
    app_databases = {
      orders = {}
    }
  }

  expect_failures = [var.app_databases]
}

run "published_image" {
  command = apply

  assert {
    condition     = jsondecode(aws_ecs_task_definition.keyra.container_definitions)[0].image == "ghcr.io/adamrodriguesss/keyra:0.2.0"
    error_message = "With no image, the pinned published release should run."
  }
}

run "custom_image" {
  command = apply

  variables {
    image = "123456789012.dkr.ecr.eu-west-1.amazonaws.com/keyra:1.0.0"
  }

  assert {
    condition     = jsondecode(aws_ecs_task_definition.keyra.container_definitions)[0].image == "123456789012.dkr.ecr.eu-west-1.amazonaws.com/keyra:1.0.0"
    error_message = "A custom image should be used as given."
  }
}

run "generated_password" {
  command = apply

  variables {
    bootstrap_admin_password = null
  }

  assert {
    condition     = output.bootstrap_admin_password_secret == "keyra-bootstrap-admin-password"
    error_message = "The secret name should be exposed so the generated password can be read."
  }
}

run "metrics_enabled" {
  command = apply

  variables {
    metrics_enabled = true
  }

  assert {
    condition     = [for c in jsondecode(aws_ecs_task_definition.keyra.container_definitions) : c.name] == ["keyra", "otel-collector"]
    error_message = "Metrics should add the collector sidecar."
  }
  assert {
    condition     = jsondecode(aws_ecs_task_definition.keyra.container_definitions)[0].dependsOn == [{ containerName = "otel-collector", condition = "HEALTHY" }]
    error_message = "Keyra should start once the collector is healthy."
  }
  assert {
    condition     = local.env.KEYRA_METRICS_ENABLED == "true" && local.collector_config.exporters.awsemf.namespace == "Keyra"
    error_message = "Keyra should push metrics, and the collector write them to CloudWatch."
  }
  assert {
    condition     = length(aws_iam_role_policy.task_metrics) == 1 && length(aws_cloudwatch_log_group.metrics) == 1
    error_message = "The task needs permission to write metrics, and a log group for them."
  }
}

run "cloudfront_without_domain" {
  command = apply

  variables {
    domain_name     = null
    route53_zone_id = null
  }

  assert {
    condition     = output.url == "https://d111111abcdef8.cloudfront.net" && local.env.KEYRA_ISSUER_URL == output.url
    error_message = "Without a domain, the CloudFront URL is the public URL and the token issuer."
  }
  assert {
    condition     = length(aws_acm_certificate.keyra) == 0 && length(aws_route53_record.keyra) == 0 && length(aws_lb_listener.https) == 0
    error_message = "Without a domain, there is no certificate, DNS record or HTTPS listener."
  }
  assert {
    condition     = aws_lb.keyra.internal && toset(aws_lb.keyra.subnets) == toset(aws_subnet.private[*].id)
    error_message = "The load balancer should be internal, in private subnets, reachable only through the VPC origin."
  }
  assert {
    condition     = aws_lb_listener.http.default_action[0].type == "forward"
    error_message = "CloudFront reaches the load balancer over HTTP inside the VPC."
  }
  assert {
    condition     = keys(aws_vpc_security_group_ingress_rule.alb) == ["cloudfront"] && aws_vpc_security_group_ingress_rule.alb["cloudfront"].referenced_security_group_id == "sg-0cf0cf0cf0cf0cf00"
    error_message = "Only CloudFront's VPC origin may reach the load balancer."
  }
  assert {
    condition     = aws_cloudfront_distribution.keyra[0].default_cache_behavior[0].viewer_protocol_policy == "redirect-to-https"
    error_message = "Viewers must use HTTPS."
  }
  assert {
    condition     = contains(aws_cloudfront_cache_policy.keyra[0].parameters_in_cache_key_and_forwarded_to_origin[0].headers_config[0].headers[0].items, "Authorization") && aws_cloudfront_cache_policy.keyra[0].default_ttl == 0
    error_message = "Authorization must reach Keyra on GET requests, and nothing is cached by default."
  }
}

run "rejects_domain_without_zone" {
  command = plan

  variables {
    route53_zone_id = null
  }

  expect_failures = [var.route53_zone_id]
}
