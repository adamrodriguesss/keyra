# One Keyra instance on GCP. State lives in a GCS bucket you create first:
#   terraform init -backend-config="bucket=<state-bucket>"
terraform {
  required_version = ">= 1.11"

  backend "gcs" {
    prefix = "keyra"
  }

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = ">= 7.0"
    }
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
}

module "keyra" {
  source = "../../modules/keyra/gcp"

  project_id               = var.project_id
  name                     = var.name
  region                   = var.region
  image                    = var.image
  replicas                 = var.replicas
  max_replicas             = var.max_replicas
  db_tier                  = var.db_tier
  db_pool_size             = var.db_pool_size
  cloud_sql_version        = var.cloud_sql_version
  deletion_protection      = var.deletion_protection
  bootstrap_admin_email    = var.bootstrap_admin_email
  bootstrap_admin_password = var.bootstrap_admin_password
  external_database        = var.external_database
  egress                   = var.egress
  app_databases            = var.app_databases
}

variable "project_id" {
  type = string
}

variable "name" {
  type    = string
  default = "keyra"
}

variable "region" {
  type = string
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

variable "cloud_sql_version" {
  type    = string
  default = null
}

variable "deletion_protection" {
  type    = bool
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
    static_ip  = optional(bool, false)
    network    = optional(string)
    subnetwork = optional(string)
  })
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

variable "app_databases" {
  type = map(object({
    password_version = optional(number, 1)
  }))
  default = {}
}

output "app_databases" {
  value = module.keyra.app_databases
}
