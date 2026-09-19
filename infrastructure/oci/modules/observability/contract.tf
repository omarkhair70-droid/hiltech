variable "compartment_ocid" { type = string }
variable "name_prefix" { type = string }
variable "otel_collector_private" { type = bool }
variable "trace_backend" { type = string }
variable "log_backend" { type = string }
variable "metrics_backend" { type = string }

output "contract" {
  value = {
    compartment_ocid       = var.compartment_ocid
    name_prefix            = var.name_prefix
    otel_collector_private = var.otel_collector_private
    trace_backend          = var.trace_backend
    log_backend            = var.log_backend
    metrics_backend        = var.metrics_backend
  }
}
