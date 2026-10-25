# One Keyra instance on AWS. State lives in an S3 bucket you create first:
#   terraform init -backend-config="bucket=<state-bucket>" -backend-config="region=<region>"
terraform {
  required_version = ">= 1.11"

  backend "s3" {
    key          = "keyra/terraform.tfstate"
    use_lockfile = true
  }

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.0"
    }
  }
}

provider "aws" {
  region = var.region
}

module "keyra" {
  source = "../../modules/keyra/aws"

  name                     = var.name
  region                   = var.region
  domain_name              = var.domain_name
  route53_zone_id          = var.route53_zone_id
  image                    = var.image
  replicas                 = var.replicas
  max_replicas             = var.max_replicas
  db_tier                  = var.db_tier
  db_pool_size             = var.db_pool_size
  db_backup_retention_days = var.db_backup_retention_days
  postgres_version         = var.postgres_version
  deletion_protection      = var.deletion_protection
  metrics_enabled          = var.metrics_enabled
  jwt_key_version          = var.jwt_key_version
  db_password_version      = var.db_password_version
  bootstrap_admin_email    = var.bootstrap_admin_email
  bootstrap_admin_password = var.bootstrap_admin_password
  external_database        = var.external_database
  egress                   = var.egress
  app_databases            = var.app_databases
}

variable "name" {
  type    = string
  default = "keyra"
}

variable "region" {
  type = string
}

# Both unset: CloudFront in front, on its https://<id>.cloudfront.net URL.
variable "domain_name" {
  type    = string
  default = null
}

variable "route53_zone_id" {
  type    = string
  default = null
}

variable "image" {
  type    = string
  default = null
}

# Optional inputs default to null, which means "use the module's default" (see the module README).
variable "replicas" {
  type    = number
  default = null
}

variable "max_replicas" {
  type    = number
  default = null
}

variable "db_tier" {
  type    = string
  default = null
}

variable "db_pool_size" {
  type    = number
  default = null
}

variable "db_backup_retention_days" {
  type    = number
  default = null
}

variable "postgres_version" {
  type    = string
  default = null
}

variable "deletion_protection" {
  type    = bool
  default = null
}

variable "metrics_enabled" {
  type    = bool
  default = null
}

variable "jwt_key_version" {
  type    = number
  default = null
}

variable "db_password_version" {
  type    = number
  default = null
}

variable "bootstrap_admin_email" {
  type = string
}

variable "bootstrap_admin_password" {
  type      = string
  default   = null
  sensitive = true
  ephemeral = true
}

variable "external_database" {
  type = object({
    jdbc_url           = string
    username           = string
    password_secret_id = string
  })
  default = null
}

variable "egress" {
  type = object({
    static_ip       = optional(bool, false)
    vpc_id          = optional(string)
    task_subnet_ids = optional(list(string))
    alb_subnet_ids  = optional(list(string))
  })
  default = {}
}

variable "app_databases" {
  type = map(object({
    password_version = optional(number, 1)
  }))
  default = {}
}

output "url" {
  value = module.keyra.url
}

output "bootstrap_admin_password_secret" {
  value = module.keyra.bootstrap_admin_password_secret
}

output "egress_ip" {
  value = module.keyra.egress_ip
}

output "app_databases" {
  value = module.keyra.app_databases
}
