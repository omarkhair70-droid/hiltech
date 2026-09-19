variable "compartment_ocid" { type = string }
variable "region" { type = string }
variable "private_subnet_id" { type = string }
variable "name_prefix" { type = string }
variable "runtime_shape" { type = string }
variable "container_image_digests" { type = map(string) }

output "contract" {
  value = {
    compartment_ocid      = var.compartment_ocid
    region                = var.region
    private_subnet_id     = var.private_subnet_id
    name_prefix           = var.name_prefix
    runtime_shape         = var.runtime_shape
    container_image_names = sort(keys(var.container_image_digests))
  }
}
