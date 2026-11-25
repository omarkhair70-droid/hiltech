@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.hiltech.shared.core.service

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.design.*

@Composable
internal fun ServiceDeliveryForm(
    source: ServiceDeliveryWorkspaceDto,
    action: String,
    details: ServiceDeliveryDetails,
    enabled: Boolean,
    attempted: Boolean,
    onChange: (ServiceDeliveryDetails) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    onOpen: (ServiceDeliveryProofDto) -> Unit,
    onUpload: (String) -> Unit,
    onCreateIncident: () -> Unit,
) {
    val language = LocalHiltechLanguage.current
    val d = details
    @Composable fun field(ar: String, en: String, value: String, multiline: Boolean = false, update: (String) -> Unit) =
        OutlinedTextField(value, update, enabled = enabled, modifier = Modifier.fillMaxWidth(), singleLine = !multiline,
            minLines = if (multiline) 2 else 1, label = { Text(hiltechText(ar, en)) })
    @Composable fun choice(ar: String, en: String, value: String?, values: List<HiltechRecordChoice>, update: (String) -> Unit) =
        HiltechRecordPicker(hiltechText(ar, en), value.orEmpty(), values, update, allowKnownReference = false, enabled = enabled)
    @Composable fun proof(ar: String, en: String, value: String?, purposes: Set<String>, uploadPurpose: String, update: (String) -> Unit) {
        val records = source.proofDocuments.filter { it.purpose in purposes }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            choice(ar, en, value, records.map { HiltechRecordChoice(it.evidenceId, it.title, serviceDeliveryLabel(it.purpose, language)) }, update)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                records.firstOrNull { it.evidenceId == value }?.let { selected ->
                    TextButton(enabled = enabled, onClick = { onOpen(selected) }) { Text(hiltechText("فتح المستند", "Open document")) }
                }
                TextButton(enabled = enabled, onClick = { onUpload(uploadPurpose) }) { Text(hiltechText("إرفاق مستند جديد", "Attach a new document")) }
            }
        }
    }
    @Composable fun routing() {
        val policy = source.policies.firstOrNull { it.workflow == if (action.startsWith("HANDOVER_")) "HANDOVER" else "WARRANTY" }
        if (policy?.configured != true) Text(serviceDeliveryProblem("SERVICE_DELIVERY_REVIEW_SETUP_REQUIRED", language), color = MaterialTheme.colorScheme.error)
        else if (!policy.independent) Text(serviceDeliveryProblem("SERVICE_DELIVERY_INDEPENDENT_REVIEWER_REQUIRED", language), color = MaterialTheme.colorScheme.error)
        else HiltechKeyValue(hiltechText("المراجع المختص", "Assigned reviewer"), policy.reviewerName.orEmpty())
    }
    HiltechPanel(Modifier.fillMaxWidth()) {
        Text(serviceDeliveryLabel(action, language), style = MaterialTheme.typography.titleLarge)
        if (d.correctsEventId != null) HiltechStateTag(hiltechText("تصحيح مرتبط بالسجل السابق", "Correction linked to the previous record"), HiltechStateTone.ATTENTION)
        when (action) {
            "HANDOVER_RELEASE" -> Text(hiltechText("ستصبح النسخة المحددة قابلة للتصدير بعد مراجعتها. إرسالها للعميل واستجابته يُسجّلان منفصلين.",
                "After review, the selected version becomes available for export. Sending it and recording the client response are separate actions."), style = MaterialTheme.typography.bodyMedium)
            "HANDOVER_CLIENT_RESPONSE" -> Text(hiltechText("سجّل ما ورد بالفعل من العميل وأرفق الرد الأصلي. هذا السجل لا ينشئ توقيعًا إلكترونيًا للعميل.",
                "Record the client's actual response and attach the original evidence. This record does not create an electronic client signature."), style = MaterialTheme.typography.bodyMedium)
            "HANDOVER_WITHDRAW" -> Text(hiltechText("السحب يوقف تصدير الإصدار الحالي ويتيح تجهيز بديل بعد المراجعة. يبقى الإصدار واستجابة العميل السابقة في التاريخ.",
                "Withdrawal stops exporting the active release and allows a reviewed replacement. The release and prior client response remain in history."), style = MaterialTheme.typography.bodyMedium)
            "WARRANTY_TERMS" -> Text(hiltechText("انقل النطاق والتواريخ والالتزامات من الاتفاق الفعلي. لا تُفترض مدة ضمان أو بداية تلقائية من التسليم.",
                "Use the scope, dates and obligations from the actual agreement. Warranty duration and start date are never inferred from handover."), style = MaterialTheme.typography.bodyMedium)
        }
        ServiceDeliveryFormColumns(
            main = {
                when (action) {
                    "HANDOVER_RELEASE" -> {
                        choice("نسخة حزمة التسليم", "Handover version", d.packageVersionId, source.packages.map {
                            HiltechRecordChoice(it.packageVersionId, language.text("النسخة ", "Version ") + it.versionNumber,
                                language.text("مستندات: ", "Documents: ") + it.documents.size)
                        }) { onChange(d.copy(packageVersionId = it.ifBlank { null })) }
                        DeliveryCheck(hiltechText("راجعت تطابق نطاق الأعمال مع اتفاق التسليم", "I checked that the work scope matches the handover agreement"), d.scopeConfirmed, enabled) { onChange(d.copy(scopeConfirmed = it)) }
                        DeliveryCheck(hiltechText("المستندات المحددة مناسبة للتسليم لهذا العميل", "The selected documents are appropriate for disclosure to this client"), d.disclosureConfirmed, enabled) { onChange(d.copy(disclosureConfirmed = it)) }
                        if (source.readiness.missingReasons.any { it.startsWith("HANDOVER_DOCUMENT_WAIVER_NOT_APPROVED:") || it.startsWith("HANDOVER_DOCUMENT_EXCLUSION_NOT_APPROVED:") }) {
                            DeliveryCheck(hiltechText("راجعت أسباب الإعفاءات والاستبعادات وسأعرضها على المراجع مع الاتفاق", "I reviewed the waiver and exclusion reasons for the reviewer to assess against the agreement"), d.sourceExceptionsReviewed, enabled) {
                                onChange(d.copy(sourceExceptionsReviewed = it))
                            }
                        }
                    }
                    "HANDOVER_CLIENT_RESPONSE" -> {
                        ServiceDeliveryFieldPair(
                            first = { choice("نتيجة استجابة العميل", "Client response outcome", d.responseOutcome,
                                listOf("ACCEPTED", "ACCEPTED_WITH_RESERVATIONS", "REJECTED").map { HiltechRecordChoice(it, serviceDeliveryLabel(it, language)) }) {
                                onChange(d.copy(responseOutcome = it.ifBlank { null }))
                            } },
                            second = { HiltechDateField(hiltechText("تاريخ الاستجابة الفعلية", "Actual response date"), d.responseOn.orEmpty(), { onChange(d.copy(responseOn = it.ifBlank { null })) }, optional = false, enabled = enabled) },
                        )
                        ServiceDeliveryFieldPair(
                            first = { field("اسم ممثل العميل", "Client representative name", d.representativeName) { onChange(d.copy(representativeName = it)) } },
                            second = { field("صفة ممثل العميل", "Client representative capacity", d.representativeCapacity) { onChange(d.copy(representativeCapacity = it)) } },
                        )
                        field("مصدر التحقق من سلطته في الرد", "Source confirming their authority to respond", d.representativeAuthoritySource, true) { onChange(d.copy(representativeAuthoritySource = it)) }
                        DeliveryCheck(hiltechText("تحققت من هوية الممثل وصفته ومن ارتباط الرد بهذا الإصدار", "I verified the representative's identity, capacity and the response's link to this release"), d.representativeVerified, enabled) { onChange(d.copy(representativeVerified = it)) }
                    }
                    "WARRANTY_TERMS" -> {
                        choice("النطاق المشمول", "Covered scope", d.scopeId, source.scopeChoices.map {
                            HiltechRecordChoice(it.id, it.title, serviceDeliveryLabel(it.kind, language) + " · " + it.code)
                        }) { id -> val chosen = source.scopeChoices.firstOrNull { it.id == id }; onChange(d.copy(scopeId = chosen?.id, scopeType = chosen?.kind)) }
                        field("المعدة أو العمل أو الجزء المشمول بالتحديد", "Exact equipment, work or item covered", d.coveredItem) { onChange(d.copy(coveredItem = it)) }
                        ServiceDeliveryFieldPair(
                            first = { HiltechDateField(hiltechText("بداية الضمان وفق الاتفاق", "Warranty start in the agreement"), d.startsOn.orEmpty(), { onChange(d.copy(startsOn = it.ifBlank { null })) }, optional = false, enabled = enabled) },
                            second = { HiltechDateField(hiltechText("نهاية الضمان وفق الاتفاق", "Warranty end in the agreement"), d.endsOn.orEmpty(), { onChange(d.copy(endsOn = it.ifBlank { null })) }, optional = false, enabled = enabled) },
                        )
                        field("شروط الضمان المكتوبة", "Written warranty terms", d.writtenTerms, true) { onChange(d.copy(writtenTerms = it)) }
                        ServiceDeliveryFieldPair(
                            first = { field("الاستثناءات الواردة في الاتفاق، إن وجدت", "Contract exclusions, if any", d.exclusions, true) { onChange(d.copy(exclusions = it)) } },
                            second = { field("التزامات الاستجابة وفق الاتفاق، إن وجدت", "Contract response obligations, if any", d.responseObligations, true) { onChange(d.copy(responseObligations = it)) } },
                        )
                        DeliveryCheck(hiltechText("راجعت تطابق النطاق والتواريخ والشروط مع المستند الأصلي", "I checked the scope, dates and terms against the original agreement"), d.scopeConfirmed, enabled) { onChange(d.copy(scopeConfirmed = it)) }
                    }
                    "WARRANTY_ASSESSMENT" -> {
                        choice("بلاغ المشروع", "Project incident", d.ticketId, source.tickets.map { HiltechRecordChoice(it.id, it.title) }) { id ->
                            val prior = source.events.filter { it.action == "WARRANTY_ASSESSMENT" && it.details.ticketId == id }.maxByOrNull { it.eventNumber }
                            onChange(d.copy(ticketId = id.ifBlank { null }, correctsEventId = prior?.eventId))
                        }
                        if (source.tickets.isEmpty()) Text(hiltechText("افتح بلاغًا لهذا المشروع لتسجيل الواقعة، ثم استكمل تقييم تغطيته هنا.", "Open an incident for this project, then continue its coverage assessment here."))
                        TextButton(enabled = enabled, onClick = onCreateIncident) { Text(hiltechText("فتح بلاغ جديد", "Open new incident")) }
                        field("وصف الواقعة محل القرار", "Incident being assessed", d.incidentDescription, true) { onChange(d.copy(incidentDescription = it)) }
                        ServiceDeliveryFieldPair(
                            first = { choice("قرار التغطية المقترح", "Proposed coverage decision", d.coverageDecision,
                                listOf("COVERED", "NOT_COVERED", "INSUFFICIENT_INFORMATION").map { HiltechRecordChoice(it, serviceDeliveryLabel(it, language)) }) {
                                onChange(d.copy(coverageDecision = it.ifBlank { null }))
                            } },
                            second = { HiltechDateField(hiltechText("تاريخ الواقعة", "Incident date"), d.incidentOn.orEmpty(), { onChange(d.copy(incidentOn = it.ifBlank { null })) }, optional = d.coverageDecision == "INSUFFICIENT_INFORMATION", enabled = enabled) },
                        )
                        DeliveryCheck(hiltechText("الواقعة داخل النطاق المشمول في الشروط المحددة", "The incident falls within the selected terms' scope"), d.scopeConfirmed, enabled) { onChange(d.copy(scopeConfirmed = it)) }
                        DeliveryCheck(hiltechText("ينطبق استثناء من الضمان على هذه الواقعة", "A warranty exclusion applies to this incident"), d.exclusionApplies, enabled) { onChange(d.copy(exclusionApplies = it)) }
                        if (d.exclusionApplies) field("سبب انطباق الاستثناء ومرجعه", "Why the exclusion applies and its reference", d.exclusionReason, true) { onChange(d.copy(exclusionReason = it)) }
                    }
                }
                field("السبب والملاحظات المؤيدة للطلب", "Reason and supporting notes", d.reason, true) { onChange(d.copy(reason = it)) }
            },
            context = {
                if (action != "HANDOVER_WITHDRAW") Text(hiltechText("المصدر والمستندات المرتبطة", "Source and linked documents"), style = MaterialTheme.typography.titleMedium)
                when (action) {
                    "HANDOVER_RELEASE" -> {
                        proof("العقد أو اتفاق التسليم", "Handover contract or agreement", d.agreementEvidenceId, setOf("AGREEMENT"), "AGREEMENT") { onChange(d.copy(agreementEvidenceId = it.ifBlank { null })) }
                        field("مرجع الاتفاق كما يظهر في المستند", "Agreement reference shown in the document", d.contractReference) { onChange(d.copy(contractReference = it)) }
                        source.packages.firstOrNull { it.packageVersionId == d.packageVersionId }?.let { version ->
                            Text(hiltechText("المستندات التي ستتضمنها الحزمة", "Documents included in the package"), style = MaterialTheme.typography.labelLarge)
                            Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                version.documents.forEach { document -> Text(document.documentTitle.ifBlank { document.documentCode.ifBlank { language.text("مستند معتمد", "Approved document") } }, style = MaterialTheme.typography.bodyMedium) }
                            }
                        }
                    }
                    "HANDOVER_CLIENT_RESPONSE" -> {
                        source.events.firstOrNull { it.eventId == d.releaseEventId }?.details?.packageVersionId?.let { version ->
                            source.packages.firstOrNull { it.packageVersionId == version }?.let { packageVersion ->
                                HiltechKeyValue(hiltechText("نسخة التسليم المرتبط بها الرد", "Handover version receiving the response"), packageVersion.versionNumber.toString())
                            }
                        }
                        proof("الرد أو محضر الاستجابة الأصلي", "Original response or response record", d.proofEvidenceId, setOf("CLIENT_RESPONSE"), "CLIENT_RESPONSE") { onChange(d.copy(proofEvidenceId = it.ifBlank { null })) }
                    }
                    "WARRANTY_TERMS" -> {
                        proof("الاتفاق الذي يحدد الضمان", "Agreement establishing the warranty", d.agreementEvidenceId, setOf("AGREEMENT"), "AGREEMENT") { onChange(d.copy(agreementEvidenceId = it.ifBlank { null })) }
                        field("مرجع الاتفاق", "Agreement reference", d.contractReference) { onChange(d.copy(contractReference = it)) }
                        proof("مستند مساند (اختياري)", "Supporting document (optional)", d.proofEvidenceId, setOf("WARRANTY_SUPPORT", "CLIENT_RESPONSE", "WORK_DOCUMENT"), "WARRANTY_SUPPORT") { onChange(d.copy(proofEvidenceId = it.ifBlank { null })) }
                    }
                    "WARRANTY_ASSESSMENT" -> {
                        choice("شروط الضمان المعتمدة", "Approved warranty terms", d.termsEventId, source.warrantyTerms.map {
                            HiltechRecordChoice(it.eventId, it.details.coveredItem, listOfNotNull(it.scopeName, it.details.contractReference).joinToString(" · "))
                        }) { onChange(d.copy(termsEventId = it.ifBlank { null })) }
                        source.warrantyTerms.firstOrNull { it.eventId == d.termsEventId }?.let { terms ->
                            Text(terms.details.writtenTerms, style = MaterialTheme.typography.bodyMedium)
                            Text(hiltechDateText(terms.details.startsOn.orEmpty()) + " — " + hiltechDateText(terms.details.endsOn.orEmpty()), style = MaterialTheme.typography.labelLarge)
                            if (terms.details.exclusions.isNotBlank()) Text(hiltechText("الاستثناءات: ", "Exclusions: ") + terms.details.exclusions)
                        }
                        val insufficient = d.coverageDecision == "INSUFFICIENT_INFORMATION"
                        proof(if (insufficient) "دليل الواقعة (إن توفر)" else "دليل الواقعة", if (insufficient) "Incident evidence (if available)" else "Incident evidence",
                            d.proofEvidenceId, setOf("INCIDENT", "WORK_DOCUMENT"), "INCIDENT") { onChange(d.copy(proofEvidenceId = it.ifBlank { null })) }
                    }
                }
                routing()
            },
        )
        if (attempted && !serviceDeliveryFormComplete(action, d)) Text(serviceDeliveryProblem("FORM_INCOMPLETE", language), color = MaterialTheme.colorScheme.error)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = enabled, onClick = onSubmit) { Text(hiltechText("مراجعة الطلب قبل الإرسال", "Review before submitting")) }
            TextButton(enabled = enabled, onClick = onCancel) { Text(hiltechText("إلغاء", "Cancel")) }
        }
    }
}

/** Desktop keeps the decision and its original source side by side; phones keep one reading order. */
@Composable
private fun ServiceDeliveryFormColumns(main: @Composable () -> Unit, context: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 900.dp) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1.75f), verticalArrangement = Arrangement.spacedBy(14.dp)) { main() }
            HiltechPanel(Modifier.weight(1f)) { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { context() } }
        } else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            main()
            HiltechPanel(Modifier.fillMaxWidth()) { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { context() } }
        }
    }
}

@Composable
private fun ServiceDeliveryFieldPair(first: @Composable () -> Unit, second: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 600.dp) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) { first() }; Column(Modifier.weight(1f)) { second() }
        } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) { first(); second() }
    }
}

@Composable
internal fun DeliveryCheck(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange, enabled = enabled)
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun ServiceDeliveryDetailsView(action: String, d: ServiceDeliveryDetails, scopeName: String?) {
    val language = LocalHiltechLanguage.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        scopeName?.let { HiltechKeyValue(hiltechText("النطاق", "Scope"), it) }
        if (d.contractReference.isNotBlank()) HiltechKeyValue(hiltechText("مرجع الاتفاق", "Agreement reference"), d.contractReference)
        if (action == "HANDOVER_CLIENT_RESPONSE") {
            HiltechKeyValue(hiltechText("استجابة العميل", "Client response"), serviceDeliveryLabel(d.responseOutcome.orEmpty(), language))
            HiltechKeyValue(hiltechText("تاريخ الاستجابة", "Response date"), hiltechDateText(d.responseOn.orEmpty()))
            HiltechKeyValue(hiltechText("ممثل العميل وصفته", "Client representative and capacity"), d.representativeName + " · " + d.representativeCapacity)
            HiltechKeyValue(hiltechText("مصدر التحقق من السلطة", "Authority verification source"), d.representativeAuthoritySource)
        }
        if (action == "WARRANTY_TERMS") {
            HiltechKeyValue(hiltechText("الجزء المشمول", "Covered item"), d.coveredItem)
            HiltechKeyValue(hiltechText("الفترة المتفق عليها", "Agreed period"), hiltechDateText(d.startsOn.orEmpty()) + " — " + hiltechDateText(d.endsOn.orEmpty()))
            HiltechKeyValue(hiltechText("الشروط", "Terms"), d.writtenTerms)
            if (d.exclusions.isNotBlank()) HiltechKeyValue(hiltechText("الاستثناءات", "Exclusions"), d.exclusions)
            if (d.responseObligations.isNotBlank()) HiltechKeyValue(hiltechText("التزامات الاستجابة", "Response obligations"), d.responseObligations)
        }
        if (action == "WARRANTY_ASSESSMENT") {
            HiltechKeyValue(hiltechText("القرار", "Decision"), serviceDeliveryLabel(d.coverageDecision.orEmpty(), language))
            HiltechKeyValue(hiltechText("وصف الواقعة", "Incident description"), d.incidentDescription)
            d.incidentOn?.let { HiltechKeyValue(hiltechText("تاريخ الواقعة", "Incident date"), hiltechDateText(it)) }
            if (d.exclusionApplies) HiltechKeyValue(hiltechText("سبب الاستثناء", "Exclusion reason"), d.exclusionReason)
        }
        HiltechKeyValue(hiltechText("السبب والملاحظات", "Reason and notes"), d.reason)
        if (d.correctsEventId != null) Text(hiltechText("هذا السجل تصحيح مرتبط بنتيجة سابقة محفوظة.", "This record is a correction linked to a retained earlier result."), style = MaterialTheme.typography.bodySmall)
    }
}
