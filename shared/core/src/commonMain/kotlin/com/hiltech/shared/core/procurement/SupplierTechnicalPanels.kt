@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.hiltech.shared.core.procurement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.design.*

@Composable
internal fun SupplierTechnicalContext(quote: SupplierTechnicalQuoteDto) {
    val language = LocalHiltechLanguage.current
    HiltechPanel {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(quote.itemName, style = MaterialTheme.typography.titleLarge)
            Text(quote.itemCode, style = MaterialTheme.typography.labelMedium)
            HiltechKeyValue(hiltechText("المورد", "Supplier"), quote.supplierName)
            Text(quote.supplierCode, style = MaterialTheme.typography.labelMedium)
            HiltechKeyValue(hiltechText("مرجع العرض", "Quote reference"), quote.quoteReference)
            HiltechKeyValue(hiltechText("إصدار العرض", "Quote version"), quote.quoteRevision.toString())
            HiltechKeyValue(hiltechText("الكمية المطلوبة", "Required quantity"), "${quote.requestedQuantity} ${quote.requestedUnit}")
            HiltechKeyValue(hiltechText("الكمية المعروضة", "Offered quantity"), "${quote.offeredQuantity} ${quote.offeredUnit}")
            HiltechKeyValue(hiltechText("مصدر الاحتياج", "Requirement source"), quote.sourceLabel.ifBlank { supplierTechnicalLabel(quote.sourceType, language) })
            Text(quote.needDescription, style = MaterialTheme.typography.bodyMedium)
            if (!quote.sourceCurrent || !quote.latestQuote) HiltechStateTag(hiltechText("المصدر يحتاج تحديثًا", "Source needs refreshing"), HiltechStateTone.ATTENTION)
        }
    }
}

@Composable
internal fun SupplierTechnicalPacketRead(packet: SupplierTechnicalPacketDto) {
    val d = packet.details
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HiltechStateTag(hiltechText("بيان فني", "Technical packet") + " · ${packet.packetRevision}", if (packet.sourceCurrent) HiltechStateTone.CURRENT else HiltechStateTone.ATTENTION)
            Text(packet.preparedByName, style = MaterialTheme.typography.labelLarge)
            Text(hiltechDateText(packet.preparedAt), style = MaterialTheme.typography.labelMedium)
        }
        if (d.manufacturer.isNotBlank()) HiltechKeyValue(hiltechText("المصنّع", "Manufacturer"), d.manufacturer)
        if (d.model.isNotBlank()) HiltechKeyValue(hiltechText("الطراز", "Model"), d.model)
        HiltechKeyValue(hiltechText("المواصفات الفنية المعروضة", "Offered technical specification"), d.offeredSpecification)
        HiltechKeyValue(hiltechText("الفروق عن المطلوب", "Deviations from the requirement"), d.deviations)
        HiltechKeyValue(hiltechText("المصدر الفني للمورد", "Supplier technical source"), d.supplierSource)
        HiltechKeyValue(hiltechText("مرجع المصدر", "Source reference"), d.sourceReference)
        d.sourceDate?.let { HiltechKeyValue(hiltechText("تاريخ المصدر", "Source date"), hiltechDateText(it)) }
        HiltechKeyValue(hiltechText("سبب التسجيل أو التصحيح", "Reason for recording or correcting"), d.correctionReason)
    }
}

@Composable
internal fun SupplierTechnicalForm(d: SupplierTechnicalPacketFields, documents: List<SupplierTechnicalEvidenceDto>, enabled: Boolean, attempted: Boolean,
    onChange: (SupplierTechnicalPacketFields) -> Unit, onUpload: () -> Unit, onOpen: (SupplierTechnicalEvidenceDto) -> Unit) {
    fun invalid(value: String, minimum: Int) = attempted && value.trim().length < minimum
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        BoxWithConstraints {
            if (maxWidth >= 600.dp) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(d.manufacturer, { onChange(d.copy(manufacturer = it)) }, label = { Text(hiltechText("المصنّع، إن وُجد", "Manufacturer, if applicable")) }, modifier = Modifier.weight(1f), enabled = enabled)
                OutlinedTextField(d.model, { onChange(d.copy(model = it)) }, label = { Text(hiltechText("الطراز، إن وُجد", "Model, if applicable")) }, modifier = Modifier.weight(1f), enabled = enabled)
            } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(d.manufacturer, { onChange(d.copy(manufacturer = it)) }, label = { Text(hiltechText("المصنّع، إن وُجد", "Manufacturer, if applicable")) }, modifier = Modifier.fillMaxWidth(), enabled = enabled)
                OutlinedTextField(d.model, { onChange(d.copy(model = it)) }, label = { Text(hiltechText("الطراز، إن وُجد", "Model, if applicable")) }, modifier = Modifier.fillMaxWidth(), enabled = enabled)
            }
        }
        OutlinedTextField(d.offeredSpecification, { onChange(d.copy(offeredSpecification = it)) }, label = { Text(hiltechText("المواصفات الفنية المعروضة", "Offered technical specification")) }, minLines = 3,
            isError = invalid(d.offeredSpecification, 8), modifier = Modifier.fillMaxWidth(), enabled = enabled)
        OutlinedTextField(d.deviations, { onChange(d.copy(deviations = it)) }, label = { Text(hiltechText("الفروق عن المطلوب، أو اكتب لا توجد فروق", "Deviations, or state that there are none")) }, minLines = 2,
            isError = invalid(d.deviations, 1), modifier = Modifier.fillMaxWidth(), enabled = enabled)
        OutlinedTextField(d.supplierSource, { onChange(d.copy(supplierSource = it)) }, label = { Text(hiltechText("المصدر الفني، مثل كتيب المصنّع أو إفادة المورد", "Technical source, such as manufacturer data or supplier statement")) },
            isError = invalid(d.supplierSource, 1), modifier = Modifier.fillMaxWidth(), enabled = enabled)
        OutlinedTextField(d.sourceReference, { onChange(d.copy(sourceReference = it)) }, label = { Text(hiltechText("مرجع يمكن الرجوع إليه للمصدر الفني", "Traceable technical source reference")) },
            isError = invalid(d.sourceReference, 1), modifier = Modifier.fillMaxWidth(), enabled = enabled)
        HiltechDateField(hiltechText("تاريخ المصدر الفني", "Technical source date"), d.sourceDate.orEmpty(), { onChange(d.copy(sourceDate = it.ifBlank { null })) }, enabled = enabled)
        Text(hiltechText("يمكن المراجعة من البيان المكتوب ومصدره. أرفق أصلًا فنيًا منفصلًا عندما يلزم الرجوع إليه.", "The written specification and its source can be reviewed directly. Attach a separate technical original when it is needed."), style = MaterialTheme.typography.bodySmall)
        documents.forEach { doc ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(doc.evidenceId in d.evidenceIds, { checked -> onChange(d.copy(evidenceIds = if (checked) d.evidenceIds + doc.evidenceId else d.evidenceIds - doc.evidenceId)) }, enabled = enabled && (doc.evidenceId in d.evidenceIds || d.evidenceIds.size < 10))
                Text(doc.title, Modifier.weight(1f))
                TextButton(onClick = { onOpen(doc) }, enabled = enabled) { Text(hiltechText("عرض", "View")) }
            }
        }
        OutlinedButton(onClick = onUpload, enabled = enabled) { Text(hiltechText("إضافة مستند فني", "Add technical document")) }
        OutlinedTextField(d.correctionReason, { onChange(d.copy(correctionReason = it)) }, label = { Text(hiltechText("سبب التسجيل أو التصحيح", "Reason for recording or correcting")) },
            isError = invalid(d.correctionReason, 8), modifier = Modifier.fillMaxWidth(), enabled = enabled)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(d.nonFinancialConfirmed, { onChange(d.copy(nonFinancialConfirmed = it)) }, enabled = enabled)
            Text(hiltechText("راجعت أن البيان والمرفقات خالية من الأسعار وشروط الدفع.", "I checked that this packet and its attachments contain no prices or payment terms."), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun SupplierTechnicalReviewControls(packet: SupplierTechnicalPacketDto?, choice: String, reason: String, acknowledged: Boolean, enabled: Boolean,
    onChoice: (String) -> Unit, onReason: (String) -> Unit, onAcknowledged: (Boolean) -> Unit) {
    val language = LocalHiltechLanguage.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(hiltechText("نتيجة المراجعة الفنية", "Technical review result"), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("COMPLIANT", "NON_COMPLIANT", "NEEDS_CLARIFICATION").forEach { decision ->
                FilterChip(choice == decision, { onChoice(decision) }, label = { Text(supplierTechnicalLabel(decision, language)) },
                    enabled = enabled && (decision == "NEEDS_CLARIFICATION" || packet?.sourceCurrent == true))
            }
        }
        if (packet == null) Text(hiltechText("لم يُنشر بيان فني بعد. يمكنك طلب الإيضاح من المشتريات.", "No technical packet has been published yet. You can request clarification from purchasing."), style = MaterialTheme.typography.bodyMedium)
        if (packet != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(acknowledged, onAcknowledged, enabled = enabled)
            Text(hiltechText("راجعت المواصفات والفروق ومرجع المصدر في هذه النسخة.", "I reviewed this version’s specification, deviations and source reference."), style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedTextField(reason, onReason, label = { Text(hiltechText("نتيجة المقارنة وأسباب القرار", "Comparison findings and reasons")) }, minLines = 3, modifier = Modifier.fillMaxWidth(), enabled = enabled)
        if (choice == "COMPLIANT" && packet != null && !packet.displayedEvidenceIds.containsAll(packet.details.evidenceIds))
            Text(hiltechText("افتح المرفقات الفنية لهذه النسخة قبل تأكيد المطابقة.", "Open this version’s technical attachments before confirming compliance."), style = MaterialTheme.typography.bodySmall)
    }
}
