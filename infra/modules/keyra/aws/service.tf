locals {
  env = {
    KEYRA_ISSUER_URL                  = local.url
    KEYRA_DB_URL                      = local.db_url
    KEYRA_DB_USER                     = local.db_user
    KEYRA_DB_POOL_SIZE                = tostring(var.db_pool_size)
    KEYRA_JWT_KEY_ID                  = random_id.jwt_key_id.hex
    KEYRA_BOOTSTRAP_ADMIN_EMAIL       = var.bootstrap_admin_email
    LOGGING_STRUCTURED_FORMAT_CONSOLE = "ecs"
    # Keyra pushes OTLP to its default localhost:4318, where the collector sidecar listens.
    KEYRA_METRICS_ENABLED = tostring(local.metrics)
  }

  # Pinned versions (arn:::version-id), so a rotation rolls out a new task definition. An external secret follows its current version.
  secret_env = {
    KEYRA_JWT_PRIVATE_KEY          = "${aws_secretsmanager_secret.jwt_private_key.arn}:::${aws_secretsmanager_secret_version.jwt_private_key.version_id}"
    KEYRA_BOOTSTRAP_ADMIN_PASSWORD = "${aws_secretsmanager_secret.bootstrap_admin_password.arn}:::${aws_secretsmanager_secret_version.bootstrap_admin_password.version_id}"
    KEYRA_DB_PASSWORD              = local.create_db ? "${local.db_password_secret}:::${aws_secretsmanager_secret_version.db_password[0].version_id}" : local.db_password_secret
  }

  collector_image = "public.ecr.aws/aws-observability/aws-otel-collector:v0.50.0"
  # CloudWatch bills per metric, so only these series are kept. Histograms arrive as count/sum/min/max (no
  # buckets); latency percentiles come from the load balancer's TargetResponseTime.
  collector_config = {
    receivers  = { otlp = { protocols = { http = { endpoint = "localhost:4318" } } } }
    processors = { batch = {} }
    exporters = {
      awsemf = {
        namespace               = "Keyra"
        log_group_name          = "/keyra/${var.name}/metrics"
        dimension_rollup_option = "NoDimensionRollup"
        metric_declarations = [
          { dimensions = [["outcome"]], metric_name_selectors = ["^keyra\\.key\\.validations$"] },
          { dimensions = [["uri", "status"]], metric_name_selectors = ["^http\\.server\\.requests$"] },
          { dimensions = [["pool"]], metric_name_selectors = ["^hikaricp\\.connections(\\.active|\\.pending|\\.max)?$"] },
          { dimensions = [["area"]], metric_name_selectors = ["^jvm\\.memory\\.used$"] },
        ]
      }
    }
    # The ECS health check runs /healthcheck inside the container, which reaches this over loopback.
    extensions = { health_check = { endpoint = "localhost:13133" } }
    service = {
      extensions = ["health_check"]
      pipelines  = { metrics = { receivers = ["otlp"], processors = ["batch"], exporters = ["awsemf"] } }
    }
  }

  log_config = {
    logDriver = "awslogs"
    options = {
      awslogs-group         = aws_cloudwatch_log_group.keyra.name
      awslogs-region        = var.region
      awslogs-stream-prefix = "keyra"
    }
  }

  keyra_container = merge(
    {
      name         = "keyra"
      image        = local.image
      essential    = true
      portMappings = [{ containerPort = 8080, protocol = "tcp" }]
      environment  = [for k, v in local.env : { name = k, value = v }]
      secrets      = [for k, v in local.secret_env : { name = k, valueFrom = v }]
      # Keyra sizes its heap from this limit (MaxRAMPercentage); the rest of the task goes to the sidecar.
      memory           = local.metrics ? 1536 : 2048
      logConfiguration = local.log_config
    },
    # Start after the collector, so the first metrics push finds it.
    local.metrics ? { dependsOn = [{ containerName = "otel-collector", condition = "HEALTHY" }] } : {},
  )

  collector_container = {
    name             = "otel-collector"
    image            = local.collector_image
    essential        = true
    cpu              = 256
    memory           = 512
    environment      = [{ name = "AOT_CONFIG_CONTENT", value = yamlencode(local.collector_config) }]
    healthCheck      = { command = ["/healthcheck"], interval = 10, timeout = 5, retries = 3, startPeriod = 10 }
    logConfiguration = merge(local.log_config, { options = merge(local.log_config.options, { awslogs-stream-prefix = "otel" }) })
  }
}

resource "aws_cloudwatch_log_group" "keyra" {
  name              = "/keyra/${var.name}"
  retention_in_days = 30
}

resource "aws_cloudwatch_log_group" "metrics" {
  count = local.metrics ? 1 : 0

  name              = "/keyra/${var.name}/metrics"
  retention_in_days = 7
}

locals {
  ecs_tasks_assume = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Action = "sts:AssumeRole", Principal = { Service = "ecs-tasks.amazonaws.com" } }]
  })
}

# Execution role: what ECS itself needs to start a task (pull the image, write logs, read the secrets).
resource "aws_iam_role" "execution" {
  name               = "${var.name}-execution"
  assume_role_policy = local.ecs_tasks_assume
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# Read access to exactly these secrets, including an external database's existing one.
resource "aws_iam_role_policy" "execution_secrets" {
  name = "secrets"
  role = aws_iam_role.execution.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = "secretsmanager:GetSecretValue"
      Resource = [
        aws_secretsmanager_secret.jwt_private_key.arn,
        aws_secretsmanager_secret.bootstrap_admin_password.arn,
        local.db_password_secret,
      ]
    }]
  })
}

# Task role: what Keyra's containers call at runtime. Only the collector needs anything.
resource "aws_iam_role" "task" {
  name               = "${var.name}-task"
  assume_role_policy = local.ecs_tasks_assume
}

# The EMF exporter writes metrics as structured log events, which CloudWatch turns into metrics.
resource "aws_iam_role_policy" "task_metrics" {
  count = local.metrics ? 1 : 0

  name = "metrics"
  role = aws_iam_role.task.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["logs:CreateLogStream", "logs:PutLogEvents", "logs:DescribeLogStreams"]
      Resource = ["${aws_cloudwatch_log_group.metrics[0].arn}:*", aws_cloudwatch_log_group.metrics[0].arn]
    }]
  })
}

resource "aws_ecs_cluster" "keyra" {
  name = var.name
}

resource "aws_ecs_task_definition" "keyra" {
  family                   = var.name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  # The smallest Fargate size with a full vCPU; the metrics sidecar fits inside it.
  cpu                = 1024
  memory             = 2048
  execution_role_arn = aws_iam_role.execution.arn
  task_role_arn      = aws_iam_role.task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode(concat([local.keyra_container], local.metrics ? [local.collector_container] : []))
}

resource "aws_ecs_service" "keyra" {
  name            = var.name
  cluster         = aws_ecs_cluster.keyra.id
  task_definition = aws_ecs_task_definition.keyra.arn
  desired_count   = var.replicas
  launch_type     = "FARGATE"
  # Startup covers Flyway migrations before readiness passes.
  health_check_grace_period_seconds = 180
  # Like Cloud Run, apply waits for the new tasks to be healthy, and a failing rollout reverts.
  wait_for_steady_state = true

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets          = local.task_subnet_ids
    security_groups  = [aws_security_group.task.id]
    assign_public_ip = local.task_public_ip
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.keyra.arn
    container_name   = "keyra"
    container_port   = 8080
  }

  # Autoscaling owns the running count after the first deploy.
  lifecycle {
    ignore_changes = [desired_count]
  }

  depends_on = [
    aws_lb_listener.https,
    aws_lb_listener.http,
    aws_iam_role_policy.execution_secrets,
    aws_iam_role_policy_attachment.execution,
    aws_route_table_association.public,
    aws_route_table_association.private,
    aws_nat_gateway.egress,
  ]
}

resource "aws_appautoscaling_target" "keyra" {
  service_namespace  = "ecs"
  resource_id        = "service/${aws_ecs_cluster.keyra.name}/${aws_ecs_service.keyra.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  min_capacity       = var.replicas
  max_capacity       = var.max_replicas
}

# Scale on load per task (Cloud Run's concurrency equivalent), or on CPU, whichever asks for more.
resource "aws_appautoscaling_policy" "requests" {
  name               = "${var.name}-requests"
  service_namespace  = aws_appautoscaling_target.keyra.service_namespace
  resource_id        = aws_appautoscaling_target.keyra.resource_id
  scalable_dimension = aws_appautoscaling_target.keyra.scalable_dimension
  policy_type        = "TargetTrackingScaling"

  target_tracking_scaling_policy_configuration {
    # Requests per task per minute: half the 200 rps a small task is specified to handle.
    target_value = 6000
    predefined_metric_specification {
      predefined_metric_type = "ALBRequestCountPerTarget"
      resource_label         = "${aws_lb.keyra.arn_suffix}/${aws_lb_target_group.keyra.arn_suffix}"
    }
  }
}

resource "aws_appautoscaling_policy" "cpu" {
  name               = "${var.name}-cpu"
  service_namespace  = aws_appautoscaling_target.keyra.service_namespace
  resource_id        = aws_appautoscaling_target.keyra.resource_id
  scalable_dimension = aws_appautoscaling_target.keyra.scalable_dimension
  policy_type        = "TargetTrackingScaling"

  target_tracking_scaling_policy_configuration {
    target_value = 70
    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}

# A warning, not a failure: DNS may still be propagating after the retries.
check "health" {
  data "http" "health" {
    url = "${local.url}/actuator/health"

    retry {
      attempts     = 5
      min_delay_ms = 2000
    }
  }

  assert {
    condition     = data.http.health.status_code == 200 && jsondecode(data.http.health.response_body).status == "UP"
    error_message = "Keyra did not report healthy at ${local.url}/actuator/health. Check the ECS service events and the /keyra/${var.name} log group."
  }
}
