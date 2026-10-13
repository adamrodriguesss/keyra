output "url" {
  description = "Public HTTPS URL of the instance (also the token issuer)."
  value       = local.url
}

output "egress_ip" {
  description = "Static outbound IP to allowlist on an external database. null unless egress.static_ip."
  value       = one(google_compute_address.egress[*].address)
}

output "service_account_email" {
  description = "Identity Cloud Run runs as."
  value       = google_service_account.run.email
}

output "cloud_sql_instance" {
  description = "Cloud SQL connection name. null with an external database."
  value       = one(google_sql_database_instance.db[*].connection_name)
}
