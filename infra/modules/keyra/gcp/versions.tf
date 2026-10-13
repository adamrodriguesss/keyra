terraform {
  # 1.11+: ephemeral resources and write-only attributes keep every secret out of state.
  required_version = ">= 1.11"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = ">= 7.0"
    }
    random = {
      source  = "hashicorp/random"
      version = ">= 3.7"
    }
    tls = {
      source  = "hashicorp/tls"
      version = ">= 4.1"
    }
  }
}
