terraform {
  required_version = ">= 1.6"
  required_providers {
    ncloud = {
      source  = "NaverCloudPlatform/ncloud"
      version = ">= 3.0"
    }
  }
}

provider "ncloud" {
  support_vpc = true
  region      = var.region
  access_key  = var.access_key
  secret_key  = var.secret_key
}
