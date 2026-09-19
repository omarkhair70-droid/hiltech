variable "compartment_ocid" { type = string }
variable "namespace" { type = string }
variable "repository_names" { type = set(string) }

output "contract" {
  value = {
    compartment_ocid = var.compartment_ocid
    namespace        = var.namespace
    repository_names = sort(tolist(var.repository_names))
    mutable_latest   = false
  }
}
