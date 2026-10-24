# Every mode decision lives here; resources only read these locals.
locals {
  create_db   = var.external_database == null
  use_own_vpc = local.create_db || var.egress.static_ip
  metrics     = var.metrics_enabled

  # Network Cloud Run sends egress into: the module VPC, the caller's VPC, or none (public internet).
  vpc_network    = local.use_own_vpc ? one(google_compute_network.vpc[*].id) : var.egress.network
  vpc_subnetwork = local.use_own_vpc ? one(google_compute_subnetwork.run[*].id) : var.egress.subnetwork
  vpc_egress     = var.egress.static_ip ? "ALL_TRAFFIC" : "PRIVATE_RANGES_ONLY"

  db_url = coalesce(
    try(var.external_database.jdbc_url, null),
    try("jdbc:postgresql://${google_sql_database_instance.db[0].private_ip_address}:5432/${google_sql_database.keyra[0].name}?sslmode=require", null),
  )
  db_user            = coalesce(try(var.external_database.username, null), one(google_sql_user.keyra[*].name))
  db_password_secret = coalesce(try(var.external_database.password_secret_id, null), one(google_secret_manager_secret.db_password[*].id))

  # One tag pins both this module and the image it runs; the release workflow checks they match.
  keyra_version = "0.1.0"
  use_ghcr      = var.image == null
  image         = coalesce(var.image, "${var.region}-docker.pkg.dev/${var.project_id}/${var.name}-ghcr/adamrodriguesss/keyra:${local.keyra_version}")

  # Cloud Run's deterministic URL, known before the service exists, so it can be the token issuer.
  url = "https://${var.name}-${data.google_project.this.number}.${var.region}.run.app"
}

data "google_project" "this" {
  project_id = var.project_id
}

resource "google_project_service" "apis" {
  for_each = toset(concat(
    ["run.googleapis.com", "secretmanager.googleapis.com"],
    # Compute hosts the VPC, private range and NAT, so it is only needed when the module creates networking.
    local.use_own_vpc ? ["compute.googleapis.com"] : [],
    local.use_ghcr ? ["artifactregistry.googleapis.com"] : [],
    local.create_db ? ["sqladmin.googleapis.com", "servicenetworking.googleapis.com"] : [],
    local.metrics ? ["monitoring.googleapis.com"] : [],
  ))

  project            = var.project_id
  service            = each.value
  disable_on_destroy = false
}
