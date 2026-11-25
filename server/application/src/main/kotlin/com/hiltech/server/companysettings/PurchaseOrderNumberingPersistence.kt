package com.hiltech.server.companysettings

import com.hiltech.server.platform.ProductApiException
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JdbcPurchaseOrderNumberingPersistence @org.springframework.beans.factory.annotation.Autowired constructor(
    private val jdbc: JdbcTemplate,
    @Value("\${hiltech.procurement.po.number-prefix:}") private val deploymentPrefix: String = "",
) : PurchaseOrderNumberingPersistencePort {
    override fun current(organizationId: UUID): PurchaseOrderNumberingSnapshot {
        val row = rows("n.organization_id=? ORDER BY n.revision DESC LIMIT 1", organizationId).singleOrNull()
        if (row != null) return PurchaseOrderNumberingSnapshot(organizationId, PurchaseOrderNumberingSource.COMPANY,
            row.prefix, row.version, row.id, row.changedAt)
        val inherited = deploymentPrefix.trim().takeIf(::validExistingPurchaseOrderPrefix)
        return PurchaseOrderNumberingSnapshot(organizationId,
            if (inherited == null) PurchaseOrderNumberingSource.NOT_CONFIGURED else PurchaseOrderNumberingSource.DEPLOYMENT,
            inherited, 0)
    }
    override fun lockOrganization(organizationId: UUID) {
        if (jdbc.query("SELECT id FROM organization WHERE id=? AND organization_type='HILTECH' AND status='ACTIVE' FOR UPDATE",
                { rs, _ -> rs.getObject(1, UUID::class.java) }, organizationId).isEmpty())
            throw ProductApiException("COMPANY_SETTINGS_UNAVAILABLE", "The company is unavailable.", HttpStatus.NOT_FOUND)
    }
    override fun revision(id: UUID): PurchaseOrderNumberingRevision? = rows("n.id=?", id).singleOrNull()
    override fun history(organizationId: UUID, limit: Int): List<PurchaseOrderNumberingRevision> {
        require(limit in 1..50)
        return rows("n.organization_id=? ORDER BY n.revision DESC LIMIT ?", organizationId, limit)
    }
    override fun append(revision: PurchaseOrderNumberingRevision) {
        check(jdbc.update("""INSERT INTO purchase_order_numbering_revision
            (id,operation_id,organization_id,revision,prefix,previous_source,previous_prefix,reason,changed_by_user_id,changed_at)
            VALUES (?,?,?,?,?,?,?,?,?,?)""".trimIndent(), revision.id, revision.operationId, revision.organizationId,
            revision.version, revision.prefix, revision.previousSource.name, revision.previousPrefix, revision.reason,
            revision.changedBy, revision.changedAt.atOffset(ZoneOffset.UTC)) == 1)
    }
    private fun rows(predicate: String, vararg args: Any): List<PurchaseOrderNumberingRevision> = jdbc.query(
        """SELECT n.*,COALESCE(p.display_name,'') AS changed_by_name FROM purchase_order_numbering_revision n
           JOIN user_identity u ON u.id=n.changed_by_user_id LEFT JOIN person p ON p.id=u.person_id
           WHERE $predicate""".trimIndent(), { rs, _ -> row(rs) }, *args)
    private fun row(rs: ResultSet) = PurchaseOrderNumberingRevision(rs.getObject("id", UUID::class.java),
        rs.getObject("operation_id", UUID::class.java), rs.getObject("organization_id", UUID::class.java),
        rs.getLong("revision"), rs.getString("prefix"), PurchaseOrderNumberingSource.valueOf(rs.getString("previous_source")),
        rs.getString("previous_prefix"), rs.getString("reason"), rs.getObject("changed_by_user_id", UUID::class.java),
        rs.getObject("changed_at", OffsetDateTime::class.java).toInstant(), rs.getString("changed_by_name"))
}
