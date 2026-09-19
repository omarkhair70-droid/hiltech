variable "compartment_ocid" { type = string }
variable "name_prefix" { type = string }
variable "public_subnet_id" { type = string }
variable "backend_subnet_id" { type = string }
variable "min_bandwidth_mbps" { type = number }
variable "max_bandwidth_mbps" { type = number }

output "contract" {
  value = {
    compartment_ocid   = var.compartment_ocid
    name_prefix        = var.name_prefix
    public_subnet_id   = var.public_subnet_id
    backend_subnet_id  = var.backend_subnet_id
    min_bandwidth_mbps = var.min_bandwidth_mbps
    max_bandwidth_mbps = var.max_bandwidth_mbps
  }
}
