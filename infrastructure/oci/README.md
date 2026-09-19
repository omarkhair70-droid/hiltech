# HILTECH OCI Infrastructure as Code

Status: **BOOTSTRAP CONTRACT SCAFFOLD / NO PRODUCTION APPLY AUTHORIZED**

Pinned Bootstrap validation line:
- Terraform 1.16.3
- Oracle/OCI provider 8.29.0

The first production candidate remains OCI with Jeddah (`me-jeddah-1`) primary candidate, Riyadh DR candidate, Container Instances preferred, Compute fallback, OCI Database with PostgreSQL, private Object Storage + KMS, Secret Management, Container Registry, Load Balancer and an OpenTelemetry Collector.

This tree materializes the frozen module interfaces without inventing tenancy OCIDs, production credentials, runtime sizing, quotas, DNS/TLS identities, or secret contents.

CI may format/init/validate this scaffold. **No plan/apply is authorized by Bootstrap validation.**

Before first real OCI apply:
1. confirm tenancy + region subscription + quotas,
2. resolve compartment/network/service values,
3. implement provider resources behind these frozen interfaces,
4. record provider/image lock provenance,
5. configure OCI Resource Manager state/locking,
6. pass staging plan/security/recovery/smoke gates,
7. explicitly approve production apply.

No plaintext production secret belongs in Terraform variables or Git.
