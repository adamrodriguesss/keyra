terraform {
  # 1.11+: ephemeral resources and write-only attributes keep every secret out of state.
  required_version = ">= 1.11"

  required_providers {
    archive = {
      source  = "hashicorp/archive"
      version = ">= 2.7"
    }
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.0"
    }
    http = {
      source  = "hashicorp/http"
      version = ">= 3.4"
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
