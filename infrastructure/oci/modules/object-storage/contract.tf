variable "compartment_ocid" { type = string }
variable "namespace" { type = string }
variable "bucket_name" { type = string }
variable "kms_key_id" { type = string }
variable "versioning_enabled" { type = bool }

output "contract" {
  value = {
    compartment_ocid   = var.compartment_ocid
    namespace          = var.namespace
    bucket_name        = var.bucket_name
    kms_key_id         = var.kms_key_id
    versioning_enabled = var.versioning_enabled
    public_access      = false
  }
}
