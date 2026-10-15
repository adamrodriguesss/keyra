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

output "cloud_sql_private_ip" {
  description = "Private IP of the Cloud SQL instance. null with an external database."
  value       = one(google_sql_database_instance.db[*].private_ip_address)
}

output "vpc_network" {
  description = "Network Cloud Run egresses into; attach your own apps here to reach the database."
  value       = local.vpc_network
}

output "vpc_subnetwork" {
  description = "Subnetwork Cloud Run egresses into (Direct VPC egress)."
  value       = local.vpc_subnetwork
}

output "app_databases" {
  description = "Per app database: name, user, password secret and JDBC URL. Empty with an external database."
  value = {
    for k, _ in google_sql_database.app : k => {
      database           = k
      user               = google_sql_user.app[k].name
      password_secret_id = google_secret_manager_secret.app_db_password[k].id
      jdbc_url           = "jdbc:postgresql://${google_sql_database_instance.db[0].private_ip_address}:5432/${k}?sslmode=require"
    }
  }
}

output "bootstrap_admin_password_secret" {
  description = "Secret Manager secret ID holding the first admin's password."
  value       = google_secret_manager_secret.bootstrap_admin_password.secret_id
}
