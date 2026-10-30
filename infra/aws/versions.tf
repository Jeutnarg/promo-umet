terraform {
  required_version = ">= 1.6, < 2.0"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.97" }
  }
}
provider "aws" {
  region = var.region
  default_tags { tags = { Project = var.project } }
}
provider "aws" {
  alias  = "edge"
  region = "us-east-1"
  default_tags { tags = { Project = var.project } }
}
