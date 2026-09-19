variable "compartment_ocid" { type = string }
variable "region" { type = string }
variable "private_subnet_id" { type = string }
variable "name_prefix" { type = string }
variable "shape" { type = string }
variable "pitr_days" { type = number }

output "contract" {
  value = {
    compartment_ocid  = var.compartment_ocid
    region            = var.region
    private_subnet_id = var.private_subnet_id
    name_prefix       = var.name_prefix
    shape             = var.shape
    pitr_days         = var.pitr_days
  }
}
