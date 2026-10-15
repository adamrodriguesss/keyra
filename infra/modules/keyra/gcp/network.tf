# The module VPC exists only for Cloud SQL's private IP or for Cloud NAT (static egress IP).
resource "google_compute_network" "vpc" {
  count = local.use_own_vpc ? 1 : 0

  project                 = var.project_id
  name                    = var.name
  auto_create_subnetworks = false

  depends_on = [google_project_service.apis]

  # Create-before-destroy on the VPC, subnet and Cloud SQL instance: their dependents (Cloud Run)
  # must be updated before they are destroyed, otherwise Cloud Run still holds the subnet / database
  # during a mode switch.
  lifecycle {
    create_before_destroy = true
  }
}

resource "google_compute_subnetwork" "run" {
  count = local.use_own_vpc ? 1 : 0

  project                  = var.project_id
  name                     = "${var.name}-run"
  region                   = var.region
  network                  = google_compute_network.vpc[0].id
  ip_cidr_range            = var.subnet_cidr
  private_ip_google_access = true

  lifecycle {
    create_before_destroy = true
  }
}

resource "google_compute_address" "egress" {
  count = var.egress.static_ip ? 1 : 0

  project = var.project_id
  name    = "${var.name}-egress"
  region  = var.region

  depends_on = [google_project_service.apis]
}

resource "google_compute_router" "egress" {
  count = var.egress.static_ip ? 1 : 0

  project = var.project_id
  name    = "${var.name}-egress"
  region  = var.region
  network = google_compute_network.vpc[0].id
}

resource "google_compute_router_nat" "egress" {
  count = var.egress.static_ip ? 1 : 0

  project                            = var.project_id
  name                               = "${var.name}-egress"
  region                             = var.region
  router                             = google_compute_router.egress[0].name
  nat_ip_allocate_option             = "MANUAL_ONLY"
  nat_ips                            = [google_compute_address.egress[0].self_link]
  source_subnetwork_ip_ranges_to_nat = "LIST_OF_SUBNETWORKS"

  subnetwork {
    name                    = google_compute_subnetwork.run[0].id
    source_ip_ranges_to_nat = ["ALL_IP_RANGES"]
  }
}
