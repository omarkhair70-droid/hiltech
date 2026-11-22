# HILTECH — Oracle Always Free capacity audit and deployment decision gate

Date: 2026-11-22  
Status: **READ-ONLY HOST FACTS VERIFIED / TENANCY LIMITS NOT ACCESSED / NO DEPLOYMENT**

## Purpose

The owner uses an Oracle Free Tier tenancy, verified with a Visa card on signup (this alone is not a Pay As You Go upgrade). Compare the originally accepted OCI infrastructure architecture with an existing Coolify installation and other deployment arrangements. Do not overwrite or remove the tenant's existing applications.

## Evidence obtained directly from the authorized running Oracle VM

Method: read-only SSH through an already authorized owner computer and instance metadata endpoint, plus Docker/host read-only introspection. No OCI credential contents, environment secrets or Docker environment variables collected.

| Check | Verified snapshot |
|---|---|
| Instance | `nova-backend`, OCI `VM.Standard.A1.Flex` (Arm) |
| Region | `me-jeddah-1`, availability domain `ME-JEDDAH-1-AD-1` |
| Shape allocation | **1 OCPU, 6 GB RAM** |
| Networking shape | 1 Gbps nominal instance networking shape limit, not an observed throughput guarantee |
| OS | Ubuntu 24.04 |
| Root FS | 45 GB, 25 GB used, ~20 GB free (56% utilized) |
| Running Docker containers | 30 of 31 listed; **2 unhealthy** |
| Host memory | 5.8 GiB total, ~3.0 GiB available during sample |
| Swap | 2.0 GiB total; **~1.3 GiB used** during sample |
| Load averages | 1.58 / 1.16 / 0.98 on **one OCPU** at sample |
| CPU pressure | Linux PSI CPU `some avg10=17.55` — momentary signal, not long-term benchmark |
| OCI CLI on VM / OCI API config | Not installed / not present |
| OCI CLI on owner's Windows PC / OCI API config | Not installed / not present in checked standard path |

**Do not** infer spare headroom from `free`'s available memory alone. The host already runs many unrelated tenants and has measurable CPU/swap pressure and two unhealthy containers. Do not add HILTECH runtime, run `docker prune`, restart containers, configure paid OCI services, or disturb current projects.

## Official Oracle Always Free position (checked 2026-11-22)

- The current Oracle Always Free documentation lists **1,500 OCPU-hours and 9,000 GB-hours/month**, equivalent on an Always Free tenancy to **2 OCPU and 12 GB RAM** for all Ampere A1 instances combined.
- Existing audited VM uses **1 OCPU + 6 GB**. **IF and only if no other A1 instance shares the tenancy quota**, at most **1 OCPU + 6 GB** remains within the A1 always-free budget, subject to available home-region host capacity and account service limits. This is a *theoretical remainder*, not a confirmed available provision.
- Combined boot/block volume budget is **200 GB** free across eligible volumes; a new VM consumes a separate boot volume (minimum/default near 50 GB). The current 45-GB filesystem size is not a complete accounting of OCI volume resources.
- Always Free Object Storage is **20 GB combined** for an account after the trial; limited free API calls, a qualifying 10 Mbps flexible load balancer, eligible Vault secrets and Resource Manager exist subject to quotas.
- **OCI Database with PostgreSQL is not available in Always Free**. The original managed PostgreSQL infrastructure contract is therefore not executable without a paid account/service or a deliberate self-managed PostgreSQL substitution.
- Oracle's sign-up card is used for identity verification. Entering card details **does not** itself activate Pay As You Go. Free Trial credit is time-limited (up to 30 days), not renewed because a card was entered months ago.

Official source URLs:
- https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm
- https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier.htm
- https://www.oracle.com/cloud/free/faq/
- https://docs.oracle.com/en/learn/migrate-postgres-with-rclone/index.html (explicit managed PostgreSQL Always Free exclusion)

## Original design versus real present state

Accepted architectural contract: `docs/11-architecture/ADR/ADR-014-OCI-INFRASTRUCTURE.md`, `docs/13-delivery/first-slice-contract-pack/18_PRODUCTION_INFRASTRUCTURE_CONTRACT.md`.

Original desired production:
- Jeddah candidate, public HTTPS load balancer, private OCI Container Instances or Compute fallback.
- HILTECH Spring Boot, production-mode Keycloak, PostgreSQL-persistent OpenFGA, OTel Collector, conditional evidence scanner.
- **OCI Database with PostgreSQL** with backup/PITR; separate HILTECH/Keycloak/OpenFGA databases and authorization.
- OCI private S3-compatible Object Storage, KMS/Secret Management, OCI Registry immutable image digests.
- Terraform/OCI Resource Manager; separated staging and production compartments/VCNs/DB/buckets/credentials.
- Riyadh candidate DR, rehearsed restore/failover objectives, real Android and Windows release gates.

Current implementation: `infrastructure/oci/environments/staging/` and `production/` exist, and nine `infrastructure/oci/modules/*/contract.tf` modules contain variables/outputs **but zero OCI `resource` blocks** as of this audit. They are validated interfaces/scaffolds, **not deployable infrastructure**. No proof of funded OCI managed service subscriptions, region-specific quotas, provisioned DR or production apply.

## Decision matrix

| Option | Capability and cost boundary | Evaluation |
| --- | --- | --- |
| **A. Keep adding HILTECH to today's 1-OCPU Coolify host** | No new OCI service but shares 30 running containers, unhealthy services and CPU/swap pressure | **NO-GO** for persistent production-shaped whole-system environment |
| **B. Separate eligible Always Free A1 instance if tenancy permits** | Theoretical remaining 1 OCPU/6 GB; second boot volume required, finite 200 GB block and 20 GB object storage; self-managed PostgreSQL/Keycloak/OpenFGA | **CONDITIONAL** for deliberately constrained synthetic preproduction, not a production durability/DR guarantee; benchmark and independent backup first |
| **C. Original OCI managed stack** | Most faithful to accepted architecture; managed PostgreSQL, Container Instances, stronger DR and potentially additional resources are **outside Always Free** | **REQUIRES approved PAYG/cost budget and actual provisioning**; not available free as designed |
| **D. Isolated sufficiently sized non-OCI VPS/other provider with Coolify** | Same portable containers and services on a dedicated host, possible managed database/storage; paid budget/provider SLA required | Valid alternative if OCI managed total cost or available quotas fail review; avoid lock-in |
| **E. OCI Free VM for Coolify control plane, dedicated separate execution host** | Existing 30 containers stay untouched; Coolify can manage an isolated external/OCI host | Useful hybrid only after capacity and private networking/credential boundaries; does not turn Coolify into managed PostgreSQL or DR |

**Provisional recommendation:** retain original OCI architecture as the target contracts. Do **not** deploy HILTECH on today's shared VM. First validate account-level quota and remaining Always Free usage and real ability to provision a second VM. If a second 1 OCPU/6 GB VM is feasible, benchmark a *bounded isolated integration/preproduction* stack under explicit user limits and separate data. Do **not** describe it as full high-availability production. If no capacity or the full stack cannot sustain load, choose an isolated funded execution host; use current Coolify only as control plane if justified. Managed OCI production from the original contract requires an explicit paid plan.

## Still unknown — cannot establish with SSH instance metadata

1. Tenancy **billing/upgrade status** (Free Tier vs Pay As You Go), though owner reports free.
2. Home region and actual **tenancy-wide A1 resource occupancy**, other VMs, free-tier labels, block volumes and attached boot volumes.
3. OCI **Limits, Quotas and Usage** and current free A1 *host capacity* in Jeddah.
4. Bucket volume usage, LB and Vault allocation, cost/budget notifications, compartment IAM.
5. Monthly maximum cost approval, company RPO/RTO and true workload sizes.

OCI API credentials are not configured in the checked environments. Reading the VM's instance metadata does not grant OCI Console tenancy-wide query access. Any browser audit of authenticated tenancy must be done with explicit owner sign-in; do not request passwords or API private keys in chat.

## Next explicit read-only OCI Console audit

After owner signs into OCI Console, verify: Billing/Account Management plan; tenancy Home Region; Compute > Instances across compartments; Compute limits A1 OCPU/memory; Block Volume sizes and backups; Object Storage total; Load Balancer allocation; Vault/KMS service quota; Cost Analysis/Budgets. Record **counts and sizes only**, never login/session secrets, billing card information, OCI OCIDs or sensitive tenancy tags. Compare against these facts before any provisioning, PAYG upgrade, installation or deployment.

Until that evidence is obtained: `PERSISTENT_STAGING_GO=NO`, `PRODUCTION_GO=NO`, `TENANCY_WIDE_CAPACITY_VERIFIED=NO`. 
