locals {
  public_subnet_contract_id       = "PENDING_OCI_APPLY:public"
  private_app_subnet_contract_id  = "PENDING_OCI_APPLY:private-app"
  private_data_subnet_contract_id = "PENDING_OCI_APPLY:private-data"
}

module "network" {
  source = "../../modules/network"

  compartment_ocid         = var.compartment_ocid
  region                   = var.region
  name_prefix              = var.name_prefix
  vcn_cidr                 = var.network.vcn_cidr
  public_subnet_cidr       = var.network.public_subnet_cidr
  private_app_subnet_cidr  = var.network.private_app_subnet_cidr
  private_data_subnet_cidr = var.network.private_data_subnet_cidr
}

module "kms_secrets" {
  source = "../../modules/kms-secrets"

  compartment_ocid = var.compartment_ocid
  name_prefix      = var.name_prefix
  vault_id         = var.kms_vault_id
  evidence_key_id  = var.evidence_kms_key_id
  secret_ids       = var.secret_ids
}

module "object_storage" {
  source = "../../modules/object-storage"

  compartment_ocid   = var.compartment_ocid
  namespace          = var.namespace
  bucket_name        = var.evidence_bucket_name
  kms_key_id         = var.evidence_kms_key_id
  versioning_enabled = true
}

module "registry" {
  source = "../../modules/registry"

  compartment_ocid = var.compartment_ocid
  namespace        = var.namespace
  repository_names = toset([
    "hiltech/api",
    "hiltech/otel-collector",
  ])
}

module "postgres" {
  source = "../../modules/postgres"

  compartment_ocid  = var.compartment_ocid
  region            = var.region
  private_subnet_id = local.private_data_subnet_contract_id
  name_prefix       = var.name_prefix
  shape             = var.postgres_shape
  pitr_days         = 10
}

module "container_runtime" {
  source = "../../modules/container-runtime"

  compartment_ocid        = var.compartment_ocid
  region                  = var.region
  private_subnet_id       = local.private_app_subnet_contract_id
  name_prefix             = var.name_prefix
  runtime_shape           = var.runtime_shape
  container_image_digests = var.container_image_digests
}

module "load_balancer" {
  source = "../../modules/load-balancer"

  compartment_ocid   = var.compartment_ocid
  name_prefix        = var.name_prefix
  public_subnet_id   = local.public_subnet_contract_id
  backend_subnet_id  = local.private_app_subnet_contract_id
  min_bandwidth_mbps = 10
  max_bandwidth_mbps = 100
}

module "observability" {
  source = "../../modules/observability"

  compartment_ocid       = var.compartment_ocid
  name_prefix            = var.name_prefix
  otel_collector_private = true
  trace_backend          = "OCI_APM"
  log_backend            = "OCI_LOGGING"
  metrics_backend        = "OCI_MONITORING"
}

module "iam" {
  source = "../../modules/iam"

  tenancy_ocid     = var.tenancy_ocid
  compartment_ocid = var.compartment_ocid
  name_prefix      = var.name_prefix
  workload_principal_names = toset([
    "hiltech-api",
    "hiltech-keycloak",
    "hiltech-openfga",
    "hiltech-otel-collector",
  ])
}
