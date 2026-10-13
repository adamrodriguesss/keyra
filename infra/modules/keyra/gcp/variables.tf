# Inputs shared with the AWS module: name, region, image, replicas, db_tier, bootstrap_admin_*.

variable "project_id" {
  description = "GCP project to deploy into."
  type        = string
}

variable "name" {
  description = "Instance name. Prefixes every resource; the Cloud Run service gets exactly this name."
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,20}[a-z0-9]$", var.name))
    error_message = "name must be 3-22 characters of lowercase letters, digits and hyphens, starting with a letter."
  }
}

variable "region" {
  description = "Region for Cloud Run, Cloud SQL and the network, e.g. europe-west1."
  type        = string
}

variable "image" {
  description = "Keyra container image, e.g. europe-west1-docker.pkg.dev/<project>/keyra/keyra:1.0.0."
  type        = string
}

variable "replicas" {
  description = "Minimum number of running instances. Two or more for availability."
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
  description = "Cloud SQL machine tier. Ignored when external_database is set."
  type        = string
  default     = "db-custom-1-3840"
  nullable    = false
}

variable "db_pool_size" {
  description = "Connections each instance opens (KEYRA_DB_POOL_SIZE)."
  type        = number
  default     = 10
  nullable    = false
}

variable "cloud_sql_version" {
  description = "Cloud SQL Postgres version. Ignored when external_database is set."
  type        = string
  default     = "POSTGRES_18"
  nullable    = false
}

variable "external_database" {
  description = <<-EOT
    Bring your own Postgres instead of Cloud SQL. null (default) creates Cloud SQL.
    jdbc_url must require TLS (sslmode=require, verify-ca or verify-full). password_secret_id is an existing
    Secret Manager secret holding only the password ("projects/<p>/secrets/<s>" or a secret name in this project);
    the module only grants read access to it and never sees the value.
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
}

variable "egress" {
  description = <<-EOT
    How Cloud Run reaches the database. Default: Cloud SQL over the module's VPC, or a public external database
    over the internet. static_ip = true routes all egress through Cloud NAT with a reserved IP, for databases that
    allowlist clients (see the egress_ip output). network + subnetwork send egress into your own VPC, for a
    private external database; you own its routing and firewall rules.
  EOT
  type = object({
    static_ip  = optional(bool, false)
    network    = optional(string)
    subnetwork = optional(string)
  })
  default  = {}
  nullable = false

  validation {
    condition     = (var.egress.network == null) == (var.egress.subnetwork == null)
    error_message = "egress.network and egress.subnetwork must be set together."
  }

  validation {
    condition     = !(var.egress.static_ip && var.egress.network != null)
    error_message = "egress.static_ip and egress.network are mutually exclusive."
  }

  validation {
    condition     = var.egress.network == null || var.external_database != null
    error_message = "egress.network is only for an external database; Cloud SQL lives in the module's own VPC."
  }
}

variable "subnet_cidr" {
  description = "Range for the module VPC's Cloud Run subnet (only created for Cloud SQL or static_ip)."
  type        = string
  default     = "10.10.0.0/24"
  nullable    = false
}

variable "bootstrap_admin_email" {
  description = "First admin, created at startup when no managers exist."
  type        = string
}

variable "bootstrap_admin_password" {
  description = "First admin's password (12-128 characters). Ephemeral: written to Secret Manager, never to state. Supply via TF_VAR_bootstrap_admin_password."
  type        = string
  sensitive   = true
  ephemeral   = true

  validation {
    condition     = length(var.bootstrap_admin_password) >= 12 && length(var.bootstrap_admin_password) <= 128
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
  description = "Bump to generate a new Cloud SQL password. Ignored when external_database is set."
  type        = number
  default     = 1
  nullable    = false
}

variable "deletion_protection" {
  description = "Protects the Cloud SQL instance and the Cloud Run service from terraform destroy."
  type        = bool
  default     = true
  nullable    = false
}
