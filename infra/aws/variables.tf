variable "region" {
  type    = string
  default = "us-east-1"
}
variable "project" {
  type    = string
  default = "promotion-sandbox"
}
variable "root_domain" {
  type        = string
  description = "Customer hostname, e.g. sandbox.example.com, in the supplied hosted zone."
}
variable "hosted_zone_id" {
  type        = string
  description = "Existing Route 53 public hosted zone ID."
}
variable "backend_image_tag" {
  type        = string
  default     = "latest"
  description = "Use an immutable release tag after publishing the first backend image."
}
variable "desired_count" {
  type        = number
  default     = 0
  description = "Set to 1 or more after pushing the first image; 0 permits infrastructure bootstrap."
}
variable "protect_data" {
  type    = bool
  default = true
}
