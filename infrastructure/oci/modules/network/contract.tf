variable "compartment_ocid" { type = string }
variable "region" { type = string }
variable "name_prefix" { type = string }
variable "vcn_cidr" { type = string }
variable "public_subnet_cidr" { type = string }
variable "private_app_subnet_cidr" { type = string }
variable "private_data_subnet_cidr" { type = string }

output "contract" {
  value = {
    compartment_ocid         = var.compartment_ocid
    region                   = var.region
    name_prefix              = var.name_prefix
    vcn_cidr                 = var.vcn_cidr
    public_subnet_cidr       = var.public_subnet_cidr
    private_app_subnet_cidr  = var.private_app_subnet_cidr
    private_data_subnet_cidr = var.private_data_subnet_cidr
  }
}
