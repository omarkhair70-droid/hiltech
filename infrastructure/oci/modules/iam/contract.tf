variable "tenancy_ocid" { type = string }
variable "compartment_ocid" { type = string }
variable "name_prefix" { type = string }
variable "workload_principal_names" { type = set(string) }

output "contract" {
  value = {
    tenancy_ocid             = var.tenancy_ocid
    compartment_ocid         = var.compartment_ocid
    name_prefix              = var.name_prefix
    workload_principal_names = sort(tolist(var.workload_principal_names))
  }
}
