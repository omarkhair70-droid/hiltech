variable "tenancy_ocid" {
  description = "OCI tenancy OCID. Identifier only; never a secret value."
  type        = string
}

variable "compartment_ocid" {
  description = "Environment compartment OCID."
  type        = string
}

variable "region" {
  description = "Deployment region after tenancy/quota validation."
  type        = string
  default     = "me-jeddah-1"
}

variable "namespace" {
  description = "OCI Object Storage / Registry namespace."
  type        = string
}

variable "name_prefix" {
  type = string
}

variable "network" {
  type = object({
    vcn_cidr                 = string
    public_subnet_cidr       = string
    private_app_subnet_cidr  = string
    private_data_subnet_cidr = string
  })
}

variable "runtime_shape" {
  description = "Resolved Container Instances or approved Compute fallback shape."
  type        = string
}

variable "postgres_shape" {
  description = "Resolved OCI Database with PostgreSQL shape."
  type        = string
}

variable "evidence_bucket_name" {
  type = string
}

variable "kms_vault_id" {
  description = "Bootstrap-resolved vault OCID until the KMS resource module is activated."
  type        = string
}

variable "evidence_kms_key_id" {
  description = "Bootstrap-resolved evidence KMS key OCID."
  type        = string
}

variable "secret_ids" {
  description = "Secret references by logical name. Values are OCIDs/references, never secret contents."
  type        = map(string)
  sensitive   = true
}

variable "container_image_digests" {
  description = "Immutable image digests by service."
  type        = map(string)
}
