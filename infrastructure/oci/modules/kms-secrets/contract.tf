variable "compartment_ocid" { type = string }
variable "name_prefix" { type = string }
variable "vault_id" { type = string }
variable "evidence_key_id" { type = string }
variable "secret_ids" {
  type      = map(string)
  sensitive = true
}

output "contract" {
  value = {
    compartment_ocid = var.compartment_ocid
    name_prefix      = var.name_prefix
    vault_id         = var.vault_id
    evidence_key_id  = var.evidence_key_id
    secret_names     = sort(keys(var.secret_ids))
  }
}
