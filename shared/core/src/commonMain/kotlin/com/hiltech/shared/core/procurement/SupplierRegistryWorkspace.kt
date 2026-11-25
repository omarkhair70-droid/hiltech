package com.hiltech.shared.core.procurement

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.design.HiltechPanel
import com.hiltech.shared.core.design.HiltechStateTag
import com.hiltech.shared.core.design.HiltechStateTone
import com.hiltech.shared.core.design.hiltechDateText
import com.hiltech.shared.core.design.hiltechText
import com.hiltech.shared.core.network.HiltechApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Same source contract on native Windows and Android; layout adapts to available space. */
@Composable
fun SupplierRegistryWorkspace(
    organizationId: String,
    sessionScope: String,
    client: SupplierMasterDataApiClient,
    installationId: String,
    newOperationId: () -> String,
    onReauthenticate: () -> Unit = {},
) = key(sessionScope, organizationId) {
    val jobs = rememberCoroutineScope()
    var capabilities by remember { mutableStateOf<SupplierRegistryCapabilitiesDto?>(null) }
    var rows by remember { mutableStateOf(emptyList<SupplierRegistryCandidateDto>()) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var detail by remember { mutableStateOf<SupplierRegistryDetailDto?>(null) }
    var history by remember { mutableStateOf(emptyList<SupplierRegistryHistoryEntryDto>()) }
    var historyFailed by remember { mutableStateOf(false) }
    var draftJson by rememberSaveable { mutableStateOf<String?>(null) }
    var changingLink by rememberSaveable { mutableStateOf(false) }
    var reason by rememberSaveable { mutableStateOf("") }
    var showValidation by rememberSaveable { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var detailLoading by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var detailError by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var detailRefresh by remember { mutableIntStateOf(0) }
    var pendingJson by rememberSaveable { mutableStateOf<String?>(null) }
    var dialogVisible by rememberSaveable { mutableStateOf(false) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var unknownOutcome by rememberSaveable { mutableStateOf(false) }
    var rejectionConfirmed by rememberSaveable { mutableStateOf(false) }
    var commandError by rememberSaveable { mutableStateOf<String?>(null) }
    var acknowledged by rememberSaveable { mutableStateOf<String?>(null) }
    val draft = draftJson?.let { Json.decodeFromString<SupplierProfileDto>(it) } ?: SupplierProfileDto()
    val pending = pendingJson?.let { Json.decodeFromString<SupplierRegistryIntent>(it) }
    val dirty = if (creating) draft != SupplierProfileDto() else detail?.let { draft != it.profile } == true

    fun edit(value: SupplierProfileDto) { draftJson = Json.encodeToString(SupplierProfileDto.serializer(), value) }
    fun prepare(intent: SupplierRegistryIntent) {
        pendingJson = Json.encodeToString(SupplierRegistryIntent.serializer(), intent)
        dialogVisible = true
        attempted = false
        unknownOutcome = false
        rejectionConfirmed = false
        commandError = null
        acknowledged = null
    }
    fun closeEditor() {
        selectedId = null
        creating = false
        detail = null
        draftJson = null
        changingLink = false
        showValidation = false
    }

    LaunchedEffect(query, refresh) {
        loading = true
        loadError = null
        try {
            delay(250)
            val access = client.capabilities(organizationId, installationId)
            capabilities = access
            rows = if (access.canMaintain) client.search(organizationId, query.trim(), installationId) else emptyList()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            rows = emptyList()
            loadError = (failure as? HiltechApiException)?.code ?: "LOAD_FAILED"
        } finally { loading = false }
    }

    LaunchedEffect(selectedId, detailRefresh) {
        val supplier = selectedId ?: return@LaunchedEffect
        detailLoading = true
        detailError = false
        historyFailed = false
        history = emptyList()
        try {
            val current = client.detail(organizationId, supplier, installationId)
            detail = current
            if (draftJson == null) edit(current.profile)
            try { history = client.history(organizationId, supplier, installationId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { historyFailed = true }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            detail = null
            detailError = true
        } finally { detailLoading = false }
    }

    val enabled = capabilities?.canMaintain == true && loadError == null && !loading && !detailLoading && !submitting && pending == null
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        HiltechPanel(Modifier.fillMaxWidth()) {
            Text(hiltechText("سجل الموردين", "Supplier register"), style = MaterialTheme.typography.headlineSmall)
            Text(hiltechText("بيانات موحّدة للموردين الذين تتعامل معهم الشركة، متصلة باختيار المورد في المشتريات.",
                "Maintain the company's supplier details and their availability for purchasing."))
            acknowledged?.let {
                HiltechStateTag(when (it) {
                    "CREATED" -> hiltechText("تم إنشاء المورد وتفعيل التعامل معه", "Supplier created and available for purchasing")
                    "UPDATED" -> hiltechText("تم حفظ بيانات المورد", "Supplier details saved")
                    "INACTIVE" -> hiltechText("تم إيقاف التعامل الجديد مع المورد", "Supplier suspended for new sourcing")
                    else -> hiltechText("تمت إعادة تفعيل المورد", "Supplier reactivated")
                }, HiltechStateTone.CURRENT)
                if (loadError != null || detailError) Text(hiltechText(
                    "الحفظ مؤكد. تعذر تحميل العرض المحدث؛ استخدم التحديث ولا تنشئ الطلب مرة أخرى.",
                    "The change is confirmed. The updated view could not load; refresh without creating the change again.",
                ))
            }
            if (pending != null) {
                Text(hiltechText("يوجد طلب محفوظ للمورد: ", "A retained request exists for: ") + pending.displayName)
                Button(onClick = { dialogVisible = true }, enabled = !submitting) {
                    Text(if (attempted) hiltechText("متابعة نفس الطلب", "Resume same request") else hiltechText("مراجعة الطلب", "Review request"))
                }
            }
            if (loading) CircularProgressIndicator()
            when {
                loadError != null -> {
                    Text(hiltechText("تعذر تحميل السجل. تحقق من الاتصال ثم أعد المحاولة.", "The register could not load. Check your connection and retry."))
                    TextButton(onClick = { refresh += 1 }) { Text(hiltechText("إعادة التحميل", "Reload")) }
                    if (loadError in setOf("UNAUTHENTICATED", "REAUTH_REQUIRED")) TextButton(onClick = onReauthenticate) {
                        Text(hiltechText("تأكيد تسجيل الدخول", "Confirm sign-in"))
                    }
                }
                capabilities?.available == false -> Text(hiltechText("سجل الموردين متوقف مؤقتًا. تواصل مع إدارة النظام لاستعادة الخدمة.",
                    "The supplier register is temporarily unavailable. Ask the system administrator to restore the service."))
                capabilities?.canMaintain == false -> Text(hiltechText("إدارة هذا السجل تحتاج مسؤولية «إدارة بيانات الموردين». يستطيع مسؤول الصلاحيات إسنادها من مركز مسؤوليات الموظفين.",
                    "This register requires the Supplier master data responsibility, assigned through employee responsibility administration."))
            }
        }

        if (capabilities?.canMaintain == true) BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth >= 920.dp
            val directory: @Composable () -> Unit = {
                HiltechPanel(Modifier.fillMaxWidth()) {
                    OutlinedTextField(value = query, onValueChange = { if (it.length <= 80 && it.none { c -> c.code < 32 }) query = it },
                        label = { Text(hiltechText("ابحث بالاسم أو الكود", "Search by name or code")) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), enabled = pending == null)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = enabled && !dirty && !changingLink, onClick = {
                            closeEditor(); creating = true; edit(SupplierProfileDto())
                        }) { Text(hiltechText("إضافة مورد", "Add supplier")) }
                        TextButton(enabled = !loading && !submitting && !dirty && pending == null, onClick = {
                            refresh += 1; draftJson = null; detailRefresh += 1
                        }) { Text(hiltechText("تحديث", "Refresh")) }
                    }
                    if (!loading && loadError == null && rows.isEmpty()) Text(if (query.isBlank()) hiltechText(
                        "ابدأ بإضافة أول مورد. سيصبح متاحًا للاختيار بالاسم في المشتريات.",
                        "Add the first supplier to make it available by name in purchasing.",
                    ) else hiltechText("لا توجد نتائج مطابقة. جرّب اسمًا آخر أو أضف موردًا جديدًا.", "No matching suppliers. Try another name or add a supplier."))
                    if (rows.size == 100) Text(hiltechText("يظهر أول ١٠٠ مورد مطابق؛ اكتب اسمًا أدق لتضييق البحث.",
                        "Showing the first 100 matches; refine the name to narrow your search."))
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = if (wide) 560.dp else 400.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(rows, key = { it.supplierOrganizationId }) { row ->
                            Surface(color = if (selectedId == row.supplierOrganizationId) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surface,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth().clickable(enabled = enabled && !dirty && !changingLink) {
                                    closeEditor(); selectedId = row.supplierOrganizationId
                                }) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(row.displayName, style = MaterialTheme.typography.titleSmall)
                                    if (row.organizationCode.isNotBlank()) Text(row.organizationCode, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    SupplierRelationshipTag(row.relationshipState, row.supplierStatus)
                                }
                            }
                        }
                    }
                    if (dirty) Text(hiltechText("احفظ التغييرات أو ألغِ التعديل قبل اختيار مورد آخر.",
                        "Save or cancel your edits before selecting another supplier."))
                }
            }
            val editor: @Composable () -> Unit = {
                HiltechPanel(Modifier.fillMaxWidth()) {
                    when {
                        detailLoading -> CircularProgressIndicator()
                        detailError -> {
                            Text(hiltechText("تعذر تحميل بيانات المورد الحالية.", "The current supplier details could not load."))
                            TextButton(onClick = { detailRefresh += 1 }) { Text(hiltechText("إعادة التحميل", "Reload")) }
                            TextButton(onClick = { closeEditor() }) { Text(hiltechText("العودة للسجل", "Back to register")) }
                        }
                        creating || detail != null -> {
                            val current = detail
                            Text(if (creating) hiltechText("مورد جديد", "New supplier") else current!!.profile.displayName,
                                style = MaterialTheme.typography.titleLarge)
                            if (creating) Text(hiltechText("الاسم هو المطلوب للبدء. يُنشأ الكود وتاريخ التسجيل تلقائيًا، ويمكن استكمال بيانات الاتصال لاحقًا.",
                                "Start with the supplier name. A code and registration date are assigned automatically; contact details can be completed later."))
                            else {
                                Text(current!!.organizationCode, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                SupplierRelationshipTag(current.relationshipState, current.supplierStatus)
                            }
                            SupplierProfileForm(draft, ::edit, enabled && !changingLink,
                                if (showValidation) supplierProfileProblems(draft) else emptySet())
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(enabled = enabled && !changingLink && (creating || dirty), onClick = {
                                    showValidation = true
                                    if (supplierProfileProblems(draft).isEmpty()) prepare(SupplierRegistryIntent(
                                        operationId = newOperationId(), organizationId = organizationId,
                                        action = if (creating) "CREATE" else "UPDATE", displayName = draft.displayName.trim(),
                                        supplierId = if (creating) null else current!!.supplierOrganizationId,
                                        profile = normalizedSupplierProfile(draft), baseVersion = if (creating) 0 else current!!.version,
                                    ))
                                }) { Text(hiltechText("مراجعة وحفظ", "Review and save")) }
                                TextButton(enabled = pending == null && !submitting, onClick = { closeEditor() }) {
                                    Text(if (dirty) hiltechText("إلغاء التعديل", "Cancel edits") else hiltechText("العودة للسجل", "Back to register"))
                                }
                            }
                            if (!creating && current != null) {
                                HorizontalDivider()
                                if (current.relationshipState == "ACTIVE" || current.supplierStatus == "ACTIVE") {
                                    if (!changingLink) OutlinedButton(enabled = enabled && !dirty, onClick = { changingLink = true; reason = "" }) {
                                        Text(if (current.relationshipState == "ACTIVE") hiltechText("إيقاف التعامل الجديد", "Suspend new sourcing")
                                            else hiltechText("إعادة تفعيل التعامل", "Reactivate supplier"))
                                    } else {
                                        Text(if (current.relationshipState == "ACTIVE") hiltechText(
                                            "سيُمنع اختيار المورد لطلبات شراء جديدة. تبقى عروضه وأوامره وفواتيره السابقة محفوظة.",
                                            "The supplier will be unavailable for new sourcing. Existing quotes, orders and invoices remain available.",
                                        ) else hiltechText("سيظهر المورد مجددًا في اختيارات المشتريات المعتمدة.", "The supplier will become available in authorized purchasing selections again."))
                                        OutlinedTextField(value = reason, onValueChange = { if (it.length <= 240 && it.none { c -> c.code < 32 }) reason = it },
                                            label = { Text(hiltechText("سبب التغيير", "Reason for change")) },
                                            supportingText = { Text(hiltechText("اكتب سببًا واضحًا من ٨ أحرف على الأقل.", "Enter a clear reason of at least 8 characters.")) },
                                            modifier = Modifier.fillMaxWidth(), enabled = enabled)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(enabled = enabled && reason.trim().length >= 8, onClick = {
                                                prepare(SupplierRegistryIntent(newOperationId(), organizationId, "LINK", current.profile.displayName,
                                                    current.supplierOrganizationId, relationshipRevision = current.relationshipRevision + 1,
                                                    relationshipState = if (current.relationshipState == "ACTIVE") "INACTIVE" else "ACTIVE", reason = reason.trim()))
                                            }) { Text(hiltechText("مراجعة التغيير", "Review change")) }
                                            TextButton(enabled = pending == null, onClick = { changingLink = false }) { Text(hiltechText("رجوع", "Back")) }
                                        }
                                    }
                                } else Text(hiltechText("هذا المورد غير متاح للتعامل حاليًا. يمكن تحديث بياناته المحلية.",
                                    "This supplier is currently unavailable for purchasing. Its company details can still be updated."))
                                Text(hiltechText("سجل التغييرات", "Change history"), style = MaterialTheme.typography.titleMedium)
                                if (historyFailed) Text(hiltechText("تعذر تحميل السجل التاريخي؛ بيانات المورد الحالية متاحة.",
                                    "History could not load; the current supplier details remain available."))
                                history.take(10).forEach { event ->
                                    Text(supplierHistoryLabel(event.action) + " · " + hiltechDateText(event.recordedAt.take(10)),
                                        style = MaterialTheme.typography.bodyMedium)
                                    event.reason?.takeUnless { it == "REGISTERED_IN_APPLICATION" }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                        else -> {
                            Text(hiltechText("ملف المورد", "Supplier profile"), style = MaterialTheme.typography.titleLarge)
                            Text(hiltechText("اختر موردًا لعرض بياناته وتحديثها ومراجعة حالة التعامل، أو أضف موردًا جديدًا.",
                                "Choose a supplier to review its details and purchasing status, or add a new supplier."))
                        }
                    }
                }
            }
            if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.width(320.dp)) { directory() }
                Column(Modifier.weight(1f)) { editor() }
            } else if (creating || selectedId != null) editor() else directory()
        }
    }

    pending?.takeIf { dialogVisible }?.let { intent ->
        AlertDialog(
            onDismissRequest = { if (!submitting) { dialogVisible = false; if (!attempted) pendingJson = null } },
            title = { Text(hiltechText("تأكيد بيانات المورد", "Confirm supplier change")) },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(intent.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(when (intent.action) {
                        "CREATE" -> hiltechText("إنشاء المورد وتفعيل اختياره في المشتريات.", "Create the supplier and make it available for purchasing.")
                        "UPDATE" -> hiltechText("حفظ التصحيح في سجل الشركة مع الاحتفاظ بتاريخ التعديلات.", "Save corrected company details while retaining their history.")
                        else -> if (intent.relationshipState == "ACTIVE") hiltechText("إعادة تفعيل التعامل مع المورد.", "Reactivate this supplier.")
                            else hiltechText("إيقاف التعامل الجديد مع حفظ المستندات السابقة.", "Suspend new sourcing while preserving previous documents.")
                    })
                    intent.profile?.let { value ->
                        if (value.legalName != value.displayName) Text(value.legalName)
                        listOfNotNull(value.contactName, value.phone, value.email, value.taxReference, value.address).forEach { Text(it) }
                    }
                    intent.reason?.let { Text(it) }
                    if (submitting) CircularProgressIndicator()
                    commandError?.let { Text(supplierRegistryError(it), color = MaterialTheme.colorScheme.error) }
                    if (unknownOutcome) Text(hiltechText("لم تتأكد النتيجة بعد. أعد نفس الطلب؛ لن يُنشأ مورد أو تعديل مكرر.",
                        "The outcome is unconfirmed. Retry this same request to avoid a duplicate supplier or change."))
                    if (rejectionConfirmed) Text(hiltechText("لم يُسجّل هذا الطلب. حمّل الحالة الحالية قبل مراجعة تغيير جديد.",
                        "This request was not recorded. Reload the current state before preparing a new change."))
                    if (commandError in setOf("UNAUTHENTICATED", "REAUTH_REQUIRED")) TextButton(onClick = onReauthenticate) {
                        Text(hiltechText("تأكيد تسجيل الدخول", "Confirm sign-in"))
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !submitting, onClick = {
                    if (rejectionConfirmed) {
                        pendingJson = null; dialogVisible = false; attempted = false; unknownOutcome = false
                        commandError = null; rejectionConfirmed = false; changingLink = false
                        if (!creating) draftJson = null
                        refresh += 1; detailRefresh += 1
                        return@TextButton
                    }
                    val previouslyUnknown = unknownOutcome
                    submitting = true; attempted = true; unknownOutcome = true; commandError = null
                    jobs.launch {
                        try {
                            val receipt = client.applyRegistryIntent(intent, installationId)
                            acknowledged = receipt.action
                            pendingJson = null; dialogVisible = false; attempted = false; unknownOutcome = false
                            rejectionConfirmed = false; changingLink = false; creating = false; draftJson = null
                            selectedId = receipt.supplierId; refresh += 1; detailRefresh += 1
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            commandError = (failure as? HiltechApiException)?.code ?: "OUTCOME_UNCONFIRMED"
                            val disposition = supplierRegistryFailureDisposition(previouslyUnknown, failure)
                            unknownOutcome = disposition.hasUnknownOutcome; rejectionConfirmed = disposition.rejectionConfirmed
                        } finally { submitting = false }
                    }
                }) { Text(when {
                    rejectionConfirmed -> hiltechText("تحميل الحالة الحالية", "Reload current state")
                    attempted -> hiltechText("إعادة نفس الطلب", "Retry same request")
                    else -> hiltechText("تأكيد الحفظ", "Confirm change")
                }) }
            },
            dismissButton = {
                TextButton(enabled = !submitting, onClick = { dialogVisible = false; if (!attempted) pendingJson = null }) {
                    Text(if (attempted) hiltechText("الاحتفاظ والرجوع", "Keep request and go back") else hiltechText("رجوع للتعديل", "Back to edit"))
                }
            },
        )
    }
}

@Composable
private fun SupplierProfileForm(value: SupplierProfileDto, onChange: (SupplierProfileDto) -> Unit, enabled: Boolean, problems: Set<String>) {
    SupplierField(hiltechText("اسم المورد *", "Supplier name *"), value.displayName, { onChange(value.copy(displayName = it)) }, enabled, "displayName" in problems)
    SupplierField(hiltechText("الاسم القانوني", "Legal name"), value.legalName, { onChange(value.copy(legalName = it)) }, enabled, "legalName" in problems,
        helper = hiltechText("يمكن تركه فارغًا إذا كان مطابقًا لاسم المورد.", "Leave blank if it matches the supplier name."))
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val contact: @Composable () -> Unit = {
            SupplierField(hiltechText("مسؤول التواصل", "Contact person"), value.contactName.orEmpty(), { onChange(value.copy(contactName = it)) }, enabled, "contactName" in problems)
            SupplierField(hiltechText("الهاتف", "Phone"), value.phone.orEmpty(), { onChange(value.copy(phone = it)) }, enabled, "phone" in problems, keyboardType = KeyboardType.Phone)
        }
        val details: @Composable () -> Unit = {
            SupplierField(hiltechText("البريد الإلكتروني", "Email"), value.email.orEmpty(), { onChange(value.copy(email = it)) }, enabled, "email" in problems, keyboardType = KeyboardType.Email)
            SupplierField(hiltechText("الرقم الضريبي أو التسجيل", "Tax or registration reference"), value.taxReference.orEmpty(), { onChange(value.copy(taxReference = it)) }, enabled, "taxReference" in problems)
        }
        if (maxWidth >= 580.dp) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { contact() }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { details() }
        } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { contact(); details() }
    }
    SupplierField(hiltechText("العنوان", "Address"), value.address.orEmpty(), { onChange(value.copy(address = it)) }, enabled, "address" in problems, multiline = true)
}

@Composable
private fun SupplierField(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean, error: Boolean,
    helper: String? = null, keyboardType: KeyboardType = KeyboardType.Text, multiline: Boolean = false) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, enabled = enabled,
        isError = error, singleLine = !multiline, minLines = if (multiline) 2 else 1, maxLines = if (multiline) 4 else 1,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType), modifier = Modifier.fillMaxWidth(),
        supportingText = if (error || helper != null) ({ Text(if (error) {
            if (keyboardType == KeyboardType.Email) hiltechText("أدخل بريدًا إلكترونيًا صحيحًا أو اترك الحقل فارغًا.", "Enter a valid email or leave this optional field blank.")
            else hiltechText("راجع هذا الحقل؛ القيمة فارغة أو أطول من المسموح أو تحتوي رموزًا غير صالحة.", "Review this field: it is missing, too long, or contains invalid characters.")
        } else helper.orEmpty()) }) else null)
}

@Composable
private fun SupplierRelationshipTag(state: String?, supplierStatus: String) {
    val active = state == "ACTIVE" && supplierStatus == "ACTIVE"
    HiltechStateTag(if (active) hiltechText("متاح للمشتريات", "Available for purchasing")
        else hiltechText("التعامل متوقف", "Purchasing suspended"), if (active) HiltechStateTone.CURRENT else HiltechStateTone.ATTENTION)
}

@Composable
private fun supplierHistoryLabel(action: String): String = when (action) {
    "PROFILE_SAVED" -> hiltechText("حفظ بيانات المورد", "Supplier details saved")
    "ACTIVE" -> hiltechText("تفعيل التعامل", "Supplier activated")
    else -> hiltechText("إيقاف التعامل الجديد", "New sourcing suspended")
}

@Composable
private fun supplierRegistryError(code: String): String = when (code) {
    "SUPPLIER_PROFILE_VERSION_CONFLICT", "SUPPLIER_LINK_REVISION_CONFLICT" -> hiltechText(
        "تغير سجل المورد منذ فتحه. راجع الحالة الحالية قبل الحفظ.", "The supplier changed since you opened it. Review its current state before saving.")
    "INVALID_SUPPLIER_PROFILE", "INVALID_SUPPLIER_LINK_EVENT" -> hiltechText(
        "راجع الاسم وبيانات الاتصال وسبب التغيير.", "Review the name, contact details and reason for change.")
    "SUPPLIER_MASTER_DATA_UNAVAILABLE" -> hiltechText(
        "هذه العملية لم تعد متاحة بصلاحيتك الحالية أو لهذا المورد. حدّث السجل وراجع مسؤول الصلاحيات.",
        "This operation is no longer available with your current authority or for this supplier. Refresh and contact the responsibility administrator.")
    "UNAUTHENTICATED", "REAUTH_REQUIRED" -> hiltechText("أكد تسجيل الدخول ثم تابع نفس الطلب.", "Confirm sign-in, then resume the same request.")
    else -> hiltechText("تعذر تأكيد النتيجة. تم الاحتفاظ بالطلب لإعادة المحاولة.", "The outcome could not be confirmed. Your request is retained for retry.")
}
