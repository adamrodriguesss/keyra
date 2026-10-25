# Inputs shared with the GCP module: name, region, image, replicas, db_tier, bootstrap_admin_*.

variable "name" {
  description = "Instance name. Prefixes every resource; the ECS service, load balancer and RDS instance get exactly this name."
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,20}[a-z0-9]$", var.name))
    error_message = "name must be 3-22 characters of lowercase letters, digits and hyphens, starting with a letter."
  }
}

variable "region" {
  description = "AWS region for every resource, e.g. eu-west-1. Must match the aws provider's region."
  type        = string
}

variable "domain_name" {
  description = "Hostname Keyra serves on, e.g. keyra.example.com: the URL https://<domain_name> is also the token issuer. null puts CloudFront in front instead and serves on its https://<id>.cloudfront.net URL, with the load balancer internal."
  type        = string
  default     = null
}

variable "route53_zone_id" {
  description = "Route 53 hosted zone that contains domain_name. The module adds the ACM validation and alias records to it. Set together with domain_name."
  type        = string
  default     = null

  validation {
    condition     = (var.route53_zone_id == null) == (var.domain_name == null)
    error_message = "domain_name and route53_zone_id must be set together."
  }
}

variable "image" {
  description = "Custom Keyra container image, e.g. <account>.dkr.ecr.<region>.amazonaws.com/keyra:1.0.0. null runs the published release from GHCR."
  type        = string
  default     = null
}

variable "replicas" {
  description = "Minimum number of running tasks. Two or more for availability."
  type        = number
  default     = 2
  nullable    = false

  validation {
    condition     = var.replicas >= 1
    error_message = "replicas must be at least 1."
  }
}

variable "max_replicas" {
  description = "Autoscaling ceiling. max_replicas x db_pool_size must fit within the database's max_connections."
  type        = number
  default     = 4
  nullable    = false

  validation {
    condition     = var.max_replicas >= var.replicas
    error_message = "max_replicas must be at least replicas."
  }
}

variable "db_tier" {
  description = "RDS instance class. Ignored when external_database is set."
  type        = string
  default     = "db.t4g.medium"
  nullable    = false
}

variable "db_pool_size" {
  description = "Connections each task opens (KEYRA_DB_POOL_SIZE)."
  type        = number
  default     = 10
  nullable    = false
}

variable "db_backup_retention_days" {
  description = "Days of RDS backups, and the point-in-time recovery window. AWS free-plan accounts only allow 1. Ignored when external_database is set."
  type        = number
  default     = 7
  nullable    = false

  validation {
    condition     = var.db_backup_retention_days >= 1 && var.db_backup_retention_days <= 35
    error_message = "db_backup_retention_days must be 1 to 35."
  }
}

variable "postgres_version" {
  description = "RDS for PostgreSQL engine version, e.g. 18 or 18.1. Ignored when external_database is set."
  type        = string
  default     = "18"
  nullable    = false
}

variable "external_database" {
  description = <<-EOT
    Bring your own Postgres instead of RDS. null (default) creates RDS.
    jdbc_url must require TLS (sslmode=require, verify-ca or verify-full). password_secret_id is the ARN of an
    existing Secrets Manager secret holding only the password; the module only grants read access to it and never
    sees the value. A secret encrypted with your own KMS key also needs kms:Decrypt for the execution role.
  EOT
  type = object({
    jdbc_url           = string
    username           = string
    password_secret_id = string
  })
  default = null

  validation {
    condition = var.external_database == null ? true : (
      startswith(var.external_database.jdbc_url, "jdbc:postgresql://")
      && can(regex("[?&]sslmode=(require|verify-ca|verify-full)(&|$)", var.external_database.jdbc_url))
    )
    error_message = "external_database.jdbc_url must be a jdbc:postgresql:// URL with sslmode=require, verify-ca or verify-full."
  }

  validation {
    condition     = var.external_database == null ? true : can(regex("^arn:aws[a-z-]*:secretsmanager:", var.external_database.password_secret_id))
    error_message = "external_database.password_secret_id must be a Secrets Manager secret ARN."
  }
}

variable "app_databases" {
  description = "Extra databases for your own apps on the RDS instance: one database and one user per key (same name), password in Secrets Manager. Bump password_version to rotate. RDS only."
  type = map(object({
    password_version = optional(number, 1)
  }))
  default  = {}
  nullable = false

  validation {
    condition = alltrue([
      for k in keys(var.app_databases) :
      can(regex("^[a-z][a-z0-9_]{0,62}$", k)) && !contains(["keyra", "postgres"], k) && !startswith(k, "rds")
    ])
    error_message = "app_databases keys must match ^[a-z][a-z0-9_]{0,62}$ and must not be keyra, postgres or start with rds."
  }

  validation {
    condition     = var.external_database == null || length(var.app_databases) == 0
    error_message = "app_databases is only for RDS; with external_database, create the databases yourself."
  }
}

variable "egress" {
  description = <<-EOT
    How tasks reach the database and the internet. Default: the module's VPC, with tasks in public subnets
    (public IPs, reachable only from the load balancer) and RDS in private subnets. static_ip = true moves tasks
    to private subnets behind a NAT gateway with an Elastic IP, for databases that allowlist clients (see the
    egress_ip output). vpc_id + task_subnet_ids + alb_subnet_ids deploy into your own VPC, for a private external
    database: tasks get no public IP, so the task subnets need their own route out (NAT) to pull the image and
    reach AWS APIs. The load balancer subnets span two or more AZs: public with domain_name, private (no internet
    gateway route, in a VPC that has one) without it, for CloudFront's VPC origin.
  EOT
  type = object({
    static_ip       = optional(bool, false)
    vpc_id          = optional(string)
    task_subnet_ids = optional(list(string))
    alb_subnet_ids  = optional(list(string))
  })
  default  = {}
  nullable = false

  validation {
    condition = (
      (var.egress.vpc_id == null) == (var.egress.task_subnet_ids == null)
      && (var.egress.vpc_id == null) == (var.egress.alb_subnet_ids == null)
    )
    error_message = "egress.vpc_id, egress.task_subnet_ids and egress.alb_subnet_ids must be set together."
  }

  validation {
    condition     = !(var.egress.static_ip && var.egress.vpc_id != null)
    error_message = "egress.static_ip and egress.vpc_id are mutually exclusive."
  }

  validation {
    condition     = var.egress.vpc_id == null || var.external_database != null
    error_message = "egress.vpc_id is only for an external database; RDS lives in the module's own VPC."
  }
}

variable "vpc_cidr" {
  description = "Range for the module VPC (not used with egress.vpc_id). Split into two public and two private subnets."
  type        = string
  default     = "10.10.0.0/16"
  nullable    = false
}

variable "bootstrap_admin_email" {
  description = "First admin, created at startup when no managers exist."
  type        = string
}

variable "bootstrap_admin_password" {
  description = "First admin's password (12-128 characters). Ephemeral: written to Secrets Manager, never to state. null generates one (read it from the bootstrap_admin_password_secret output)."
  type        = string
  default     = null
  sensitive   = true
  ephemeral   = true

  validation {
    condition     = var.bootstrap_admin_password == null || (length(var.bootstrap_admin_password) >= 12 && length(var.bootstrap_admin_password) <= 128)
    error_message = "bootstrap_admin_password must be 12 to 128 characters."
  }
}

variable "jwt_key_version" {
  description = "Bump to generate a new JWT signing key. Existing access tokens stop verifying (they live 15 minutes)."
  type        = number
  default     = 1
  nullable    = false
}

variable "db_password_version" {
  description = "Bump to generate a new RDS password. Ignored when external_database is set."
  type        = number
  default     = 1
  nullable    = false
}

variable "metrics_enabled" {
  description = "Exports metrics to CloudWatch (namespace Keyra) through an AWS Distro for OpenTelemetry collector sidecar. The sidecar shares the task's existing CPU and memory; CloudWatch bills per custom metric."
  type        = bool
  default     = false
  nullable    = false
}

variable "deletion_protection" {
  description = "Protects the RDS instance and the load balancer from terraform destroy, keeps a final RDS snapshot, and gives deleted secrets a 30-day recovery window."
  type        = bool
  default     = true
  nullable    = false
}
