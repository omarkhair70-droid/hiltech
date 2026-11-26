package com.hiltech.server.procurement

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Direct, delegated supplier responsibility; never an implicit buyer/owner grant. */
@Component
class CurrentSupplierMasterDataAuthorization(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) : SupplierMasterDataAuthorizationPort {
    override fun canMaintainSupplierLinks(actor: UUID, purchasingOrganizationId: UUID): Boolean {
        val at = clock.instant().atOffset(ZoneOffset.UTC)
        return jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1 FROM supplier_master_data_authority_binding b
                JOIN organization o ON o.id=b.organization_id
                JOIN user_identity u ON u.id=b.identity_id
                WHERE b.organization_id=? AND b.identity_id=?
                  AND o.organization_type='HILTECH' AND o.status='ACTIVE'
                  AND u.status='ACTIVE' AND b.active
                  AND b.effective_from<=? AND (b.effective_to IS NULL OR b.effective_to>?)
                  AND EXISTS (
                      SELECT 1 FROM organization_membership m
                      WHERE m.organization_id=b.organization_id AND m.user_identity_id=b.identity_id
                        AND m.state='ACTIVE' AND m.valid_from<=?
                        AND (m.valid_until IS NULL OR m.valid_until>?)
                  )
            )
            """.trimIndent(), Boolean::class.java, purchasingOrganizationId, actor, at, at, at, at,
        ) == true
    }
}

data class SupplierProfileData(
    val legalName: String,
    val displayName: String,
    val contactName: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val taxReference: String? = null,
    val address: String? = null,
)

data class SupplierRegistryDetail(
    val organizationId: UUID,
    val supplierOrganizationId: UUID,
    val organizationCode: String,
    val supplierStatus: String,
    val relationshipRevision: Int,
    val relationshipState: String?,
    val version: Long,
    val profile: SupplierProfileData,
)

data class SupplierRegistryHistoryEntry(
    val eventId: UUID,
    val action: String,
    val revision: Long,
    val recordedAt: Instant,
    val reason: String?,
)

@Repository
class SupplierRegistryPersistence(private val jdbc: JdbcTemplate) {
    /** An existing company link is required before adopting a legacy local profile. */
    fun detail(org: UUID, supplier: UUID): SupplierRegistryDetail? = jdbc.query(
        """
        SELECT o.id, coalesce(p.supplier_code,o.organization_code) AS organization_code, o.status,
               coalesce(p.legal_name,o.legal_name) AS legal_name,
               coalesce(p.display_name,o.display_name) AS display_name,
               p.contact_name,p.email,p.phone,p.tax_reference,p.address,
               coalesce(p.version,0) AS profile_version, latest.revision_number, latest.state
        FROM organization o
        LEFT JOIN procurement_supplier_profile p ON p.supplier_organization_id=o.id AND p.organization_id=?
        LEFT JOIN LATERAL (
            SELECT l.revision_number,l.state FROM procurement_supplier_link_event l
            WHERE l.organization_id=? AND l.supplier_organization_id=o.id
            ORDER BY l.revision_number DESC LIMIT 1
        ) latest ON true
        WHERE o.id=? AND o.organization_type='SUPPLIER'
          AND (p.organization_id IS NOT NULL OR latest.revision_number IS NOT NULL)
        """.trimIndent(),
        { row, _ -> SupplierRegistryDetail(
            org, row.getObject("id", UUID::class.java), row.getString("organization_code"),
            row.getString("status"), row.getInt("revision_number"), row.getString("state"),
            row.getLong("profile_version"), SupplierProfileData(
                row.getString("legal_name"), row.getString("display_name"), row.getString("contact_name"),
                row.getString("email"), row.getString("phone"), row.getString("tax_reference"), row.getString("address"),
            ),
        ) }, org, org, supplier,
    ).singleOrNull()

    fun insertIdentity(id: UUID, profile: SupplierProfileData, at: Instant) {
        check(jdbc.update(
            """INSERT INTO organization(id,organization_code,legal_name,display_name,organization_type,status,created_at)
               VALUES (?,?,?,?,'SUPPLIER','ACTIVE',?)""",
            id, "SUP-" + id.toString().uppercase(), profile.legalName, profile.displayName, at.atOffset(ZoneOffset.UTC),
        ) == 1)
    }

    fun reserveSupplierCode(org: UUID): String = requireNotNull(jdbc.queryForObject(
        "SELECT procurement_next_supplier_code(?)", String::class.java, org,
    ))

    fun saveProfile(org: UUID, supplier: UUID, baseVersion: Long, value: SupplierProfileData, actor: UUID, at: Instant,
        newSupplierCode: String? = null): Boolean {
        val timestamp = at.atOffset(ZoneOffset.UTC)
        return if (baseVersion == 0L) jdbc.update(
            """
            INSERT INTO procurement_supplier_profile(
                organization_id,supplier_organization_id,supplier_code,legal_name,display_name,contact_name,email,phone,tax_reference,address,
                version,created_by_user_id,created_at,updated_by_user_id,updated_at
            ) VALUES (?,?,coalesce(?,(SELECT organization_code FROM organization WHERE id=?)),?,?,?,?,?,?,?,1,?,?,?,?) ON CONFLICT DO NOTHING
            """.trimIndent(), org, supplier, newSupplierCode, supplier, value.legalName, value.displayName, value.contactName, value.email,
            value.phone, value.taxReference, value.address, actor, timestamp, actor, timestamp,
        ) == 1 else jdbc.update(
            """
            UPDATE procurement_supplier_profile SET legal_name=?,display_name=?,contact_name=?,email=?,phone=?,
                tax_reference=?,address=?,version=version+1,updated_by_user_id=?,updated_at=?
            WHERE organization_id=? AND supplier_organization_id=? AND version=?
            """.trimIndent(), value.legalName, value.displayName, value.contactName, value.email,
            value.phone, value.taxReference, value.address, actor, timestamp, org, supplier, baseVersion,
        ) == 1
    }

    fun appendProfileEvent(id: UUID, ctx: ProcurementDraftContext, org: UUID, supplier: UUID, version: Long, at: Instant) {
        check(jdbc.update(
            """
            INSERT INTO procurement_supplier_profile_event(
                id,operation_id,organization_id,supplier_organization_id,version,profile_snapshot,
                recorded_by_user_id,recorded_at,correlation_id
            ) SELECT ?,?,p.organization_id,p.supplier_organization_id,p.version,
                jsonb_build_object('supplierCode',p.supplier_code,'legalName',p.legal_name,'displayName',p.display_name,'contactName',p.contact_name,
                    'email',p.email,'phone',p.phone,'taxReference',p.tax_reference,'address',p.address),?,?,?
              FROM procurement_supplier_profile p
              WHERE p.organization_id=? AND p.supplier_organization_id=? AND p.version=?
            """.trimIndent(), id, ctx.operationId, ctx.actor, at.atOffset(ZoneOffset.UTC), ctx.correlationId, org, supplier, version,
        ) == 1)
    }

    fun history(org: UUID, supplier: UUID): List<SupplierRegistryHistoryEntry> = jdbc.query(
        """
        SELECT * FROM (
            SELECT id,'PROFILE_SAVED' AS action,version AS revision,recorded_at,NULL::text AS reason
            FROM procurement_supplier_profile_event WHERE organization_id=? AND supplier_organization_id=?
            UNION ALL
            SELECT id,state AS action,revision_number AS revision,recorded_at,source_reference AS reason
            FROM procurement_supplier_link_event WHERE organization_id=? AND supplier_organization_id=?
        ) events ORDER BY recorded_at DESC,revision DESC,id LIMIT 50
        """.trimIndent(),
        { row, _ -> SupplierRegistryHistoryEntry(
            row.getObject("id", UUID::class.java), row.getString("action"), row.getLong("revision"),
            row.getObject("recorded_at", OffsetDateTime::class.java).toInstant(), row.getString("reason"),
        ) }, org, supplier, org, supplier,
    )
}
