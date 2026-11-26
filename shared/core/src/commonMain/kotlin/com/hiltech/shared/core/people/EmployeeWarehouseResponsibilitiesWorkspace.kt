package com.hiltech.shared.core.people

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.design.HiltechPanel
import com.hiltech.shared.core.design.HiltechRecordChoice
import com.hiltech.shared.core.design.HiltechRecordPicker
import com.hiltech.shared.core.design.hiltechText
import com.hiltech.shared.core.network.HiltechApiException
import com.hiltech.shared.core.warehouse.EmployeeWarehouseAuthorityIntent
import com.hiltech.shared.core.warehouse.EmployeeWarehousePreparationException
import com.hiltech.shared.core.warehouse.EmployeeWarehouseResponsibilitiesDto
import com.hiltech.shared.core.warehouse.WarehouseApiClient
import com.hiltech.shared.core.warehouse.employeeWarehouseFailureDisposition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Employee-centric composition of the existing, strictly scoped delegation commands. */
@Composable
fun EmployeeWarehouseResponsibilitiesWorkspace(
    employee: EmployeeDetailDto,
    sessionScope: String,
    client: WarehouseApiClient,
    installationId: String,
    newOperationId: () -> String,
    nowUtc: () -> String,
) = key(sessionScope, employee.organizationId, employee.employeeId) {
    val jobs = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<EmployeeWarehouseResponsibilitiesDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var submitting by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var warehouseId by rememberSaveable { mutableStateOf("") }
    var authorityKey by rememberSaveable { mutableStateOf("") }
    var pendingJson by rememberSaveable { mutableStateOf<String?>(null) }
    var dialogVisible by rememberSaveable { mutableStateOf(false) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var hasUnknownOutcome by rememberSaveable { mutableStateOf(false) }
    var rejectionConfirmed by rememberSaveable { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var acknowledgement by remember { mutableStateOf(false) }
    var acknowledgedGrantAlreadyEnded by remember { mutableStateOf(false) }
    var commandError by remember { mutableStateOf<String?>(null) }
    val pending = pendingJson?.let { Json.decodeFromString(EmployeeWarehouseAuthorityIntent.serializer(), it) }

    LaunchedEffect(employee.version, refresh) {
        loading = true
        loadFailed = false
        try {
            val result = client.employeeWarehouseResponsibilities(employee.employeeId, installationId)
            require(result.employeeId == employee.employeeId && result.organizationId == employee.organizationId)
            require(result.scope == "MANAGED_WAREHOUSES_ONLY")
            snapshot = result
            if (result.warehouses.none { it.warehouseId == warehouseId }) {
                warehouseId = result.warehouses.firstOrNull()?.warehouseId.orEmpty()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            snapshot = null
            loadFailed = true
        } finally {
            loading = false
        }
    }

    val current = snapshot
    val warehouse = current?.warehouses?.firstOrNull { it.warehouseId == warehouseId }
    val availableKeys = warehouse?.grantableAuthorityKeys.orEmpty()
        .filter { it == "WAREHOUSE_VIEWER" || it == "WAREHOUSE_OPERATOR" }
        .filter { key -> warehouse?.bindings.orEmpty().none {
            it.authorityKey == key && it.assignmentSource == "DIRECT"
        } }
    LaunchedEffect(warehouseId, availableKeys) {
        if (authorityKey !in availableKeys) authorityKey = availableKeys.firstOrNull().orEmpty()
    }

    fun prepare(intent: EmployeeWarehouseAuthorityIntent) {
        pendingJson = Json.encodeToString(EmployeeWarehouseAuthorityIntent.serializer(), intent)
        attempted = false
        hasUnknownOutcome = false
        rejectionConfirmed = false
        dialogVisible = true
        commandError = null
        acknowledgement = false
        acknowledgedGrantAlreadyEnded = false
    }

    HiltechPanel(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(hiltechText("مسؤوليات المخازن", "Warehouse responsibilities"), Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { refresh += 1 }, enabled = !loading && !submitting) {
                Text(hiltechText("تحديث", "Refresh"))
            }
        }
        Text(hiltechText(
            "تظهر هنا المخازن التي تملك صلاحية إدارتها فقط. قد تكون للموظف مسؤوليات أخرى خارج هذا العرض.",
            "Only warehouses you manage appear here. This employee may hold other responsibilities outside this view.",
        ))
        if (loading) CircularProgressIndicator()
        if (loadFailed) Text(hiltechText(
            "تعذر تحميل المسؤوليات. تحقق من الاتصال وصلاحية إدارة الموظفين ثم حدّث العرض.",
            "Couldn’t load responsibilities. Check your connection and People administration access, then refresh.",
        ), color = MaterialTheme.colorScheme.error)
        if (acknowledgement) Text(hiltechText(
            "تم تسجيل التغيير. يُعاد التحقق من الصلاحيات عند استخدام الموظف للتطبيق.",
            "The change was recorded. Access is checked again when the employee uses the app.",
        ))
        if (acknowledgedGrantAlreadyEnded) Text(hiltechText(
            "تأكد تسجيل الإسناد السابق، لكن هذا التكليف انتهى بعد ذلك. يعرض التحديث الحالة الحالية.",
            "The earlier grant was confirmed, but this assignment has since ended. The refreshed view shows current access.",
        ))
        if (pending != null && !dialogVisible) {
            Text(hiltechText("يوجد تغيير محفوظ لم تُحسم نتيجته بعد.", "A saved change still needs its outcome confirmed."))
            TextButton(onClick = { dialogVisible = true }) { Text(hiltechText("متابعة التغيير", "Continue change")) }
        }
        when {
            loading || current == null -> Unit
            current.accountStatus != "CURRENT" -> Text(hiltechText(
                "لا يوجد حساب مرتبط نشط لهذا الموظف. راجع إعداد حسابه وعضويته قبل إسناد المسؤوليات.",
                "This employee has no current linked account. Review account setup and membership before assigning responsibilities.",
            ))
            current.warehouses.isEmpty() -> Text(hiltechText(
                "لا توجد مخازن ضمن سلطة إدارتك الحالية. إدارة الموظفين وحدها لا تمنح سلطة إسناد أعمال المخزن.",
                "No warehouses fall within your current management authority. People administration alone does not grant warehouse delegation authority.",
            ))
            else -> {
                HiltechRecordPicker(
                    label = hiltechText("المخزن", "Warehouse"),
                    value = warehouseId,
                    choices = current.warehouses.map { HiltechRecordChoice(it.warehouseId, it.name, it.code) },
                    onChange = { warehouseId = it },
                    allowKnownReference = false,
                    allowClear = false,
                    enabled = pending == null && !submitting,
                )
                warehouse?.let { selected ->
                    if (selected.bindings.isEmpty()) Text(hiltechText(
                        "لا توجد تكليفات مخزن حالية لهذا الموظف في هذا المخزن.",
                        "This employee has no current warehouse assignments in this warehouse.",
                    ))
                    selected.bindings.forEach { binding ->
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(warehouseResponsibilityLabel(binding.authorityKey), style = MaterialTheme.typography.titleSmall)
                            Text(if (binding.assignmentSource == "TEAM") hiltechText(
                                "من عضوية فريق — لا يُلغى من سجل الموظف.",
                                "Inherited from team membership — cannot be ended from this employee record.",
                            ) else hiltechText("تكليف مباشر", "Direct assignment"))
                            if (binding.canEnd && binding.assignmentSource == "DIRECT" &&
                                binding.authorityKey in setOf("WAREHOUSE_VIEWER", "WAREHOUSE_OPERATOR")) {
                                TextButton(
                                    enabled = pending == null && !submitting,
                                    onClick = {
                                        prepare(EmployeeWarehouseAuthorityIntent(
                                            operationId = newOperationId(), organizationId = current.organizationId, action = "END",
                                            employeeId = current.employeeId, employeeVersion = current.employeeVersion,
                                            employeeDisplayName = employee.displayName,
                                            warehouseId = selected.warehouseId, warehouseName = selected.name,
                                            principalId = requireNotNull(current.linkedIdentityId), authorityKey = binding.authorityKey,
                                            effectiveFrom = current.asOf, clientOccurredAt = nowUtc(),
                                            bindingId = binding.bindingId, baseVersion = binding.version,
                                        ))
                                    },
                                ) { Text(hiltechText("إنهاء التكليف المباشر", "End direct assignment")) }
                            } else if (binding.assignmentSource == "DIRECT") Text(hiltechText(
                                "هذا التكليف خارج الصلاحيات التي يمكن إدارتها من هنا.",
                                "This assignment is outside the authority that can be managed here.",
                            ))
                        }
                    }
                    if (availableKeys.isNotEmpty()) {
                        HiltechRecordPicker(
                            label = hiltechText("إضافة مسؤولية", "Add responsibility"),
                            value = authorityKey,
                            choices = availableKeys.map { key -> HiltechRecordChoice(key, warehouseResponsibilityLabel(key), warehouseResponsibilityDescription(key)) },
                            onChange = { authorityKey = it },
                            allowKnownReference = false, allowClear = false,
                            enabled = pending == null && !submitting,
                        )
                        Button(
                            enabled = authorityKey in availableKeys && pending == null && !submitting,
                            onClick = {
                                prepare(EmployeeWarehouseAuthorityIntent(
                                    operationId = newOperationId(), organizationId = current.organizationId, action = "GRANT",
                                    employeeId = current.employeeId, employeeVersion = current.employeeVersion,
                                    employeeDisplayName = employee.displayName,
                                    warehouseId = selected.warehouseId, warehouseName = selected.name,
                                    principalId = requireNotNull(current.linkedIdentityId), authorityKey = authorityKey,
                                    effectiveFrom = current.asOf, clientOccurredAt = nowUtc(),
                                ))
                            },
                        ) { Text(hiltechText("مراجعة الإسناد", "Review assignment")) }
                    }
                }
            }
        }
    }

    pending?.takeIf { dialogVisible }?.let { intent ->
        AlertDialog(
            onDismissRequest = {
                if (!submitting) {
                    dialogVisible = false
                    if (!attempted) pendingJson = null
                }
            },
            title = { Text(if (intent.action == "GRANT") hiltechText("تأكيد المسؤولية", "Confirm responsibility")
                else hiltechText("إنهاء التكليف المباشر", "End direct assignment")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(intent.employeeDisplayName)
                    if (attempted) Text(hiltechText(
                        "تُعاد المحاولة للحساب الذي اخترته عند إنشاء هذا الطلب. تحديث ملف الموظف لا يغيّر الحساب المستهدف.",
                        "Retries address the account captured when this request was prepared. Employee record updates do not change its target.",
                    ))
                    Text(intent.warehouseName + " · " + warehouseResponsibilityLabel(intent.authorityKey))
                    Text(if (intent.action == "GRANT") warehouseResponsibilityDescription(intent.authorityKey)
                        else hiltechText("أي صلاحية تأتي من فريق أو تكليف آخر تستمر بعد إنهاء هذا التكليف المباشر.",
                            "Access inherited from a team or another assignment remains after ending this direct assignment."))
                    if (submitting) CircularProgressIndicator()
                    commandError?.let { Text(warehouseResponsibilityError(it), color = MaterialTheme.colorScheme.error) }
                    if (!submitting && (hasUnknownOutcome || commandError != null)) Text(if (rejectionConfirmed) hiltechText(
                        "لم يُسجّل هذا الطلب. راجع الحالة الحالية قبل إنشاء تغيير جديد.",
                        "This request was not recorded. Review the current state before creating a new change.",
                    ) else hiltechText("لم يتم تأكيد النتيجة. أعد المحاولة بنفس الطلب قبل إنشاء تغيير آخر.",
                        "The outcome is not confirmed. Retry the same request before creating another change."))
                }
            },
            confirmButton = {
                TextButton(enabled = !submitting, onClick = {
                    if (rejectionConfirmed) {
                        // Only a first confirmed rejection or the same operation's
                        // stored terminal failure may release the retained intent.
                        pendingJson = null
                        dialogVisible = false
                        attempted = false
                        hasUnknownOutcome = false
                        rejectionConfirmed = false
                        commandError = null
                        refresh += 1
                        return@TextButton
                    }
                    submitting = true
                    commandError = null
                    val priorUnknownOutcome = hasUnknownOutcome
                    rejectionConfirmed = false
                    jobs.launch {
                        try {
                            val result = client.applyEmployeeWarehouseAuthority(intent, installationId,
                                priorOutcomeUnknown = priorUnknownOutcome,
                                onCommandSending = {
                                    // Cancellation/navigation during dispatch remains unknown.
                                    attempted = true
                                    hasUnknownOutcome = true
                                },
                            )
                            pendingJson = null
                            dialogVisible = false
                            attempted = false
                            hasUnknownOutcome = false
                            rejectionConfirmed = false
                            acknowledgement = true
                            acknowledgedGrantAlreadyEnded = intent.action == "GRANT" && !result.current
                            refresh += 1
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: EmployeeWarehousePreparationException) {
                            commandError = failure.safeCode
                            val disposition = employeeWarehouseFailureDisposition(priorUnknownOutcome, failure)
                            hasUnknownOutcome = disposition.hasUnknownOutcome
                            rejectionConfirmed = disposition.rejectionConfirmed
                        } catch (failure: HiltechApiException) {
                            commandError = failure.code
                            val disposition = employeeWarehouseFailureDisposition(priorUnknownOutcome, failure)
                            hasUnknownOutcome = disposition.hasUnknownOutcome
                            rejectionConfirmed = disposition.rejectionConfirmed
                        } catch (failure: Exception) {
                            commandError = "OUTCOME_UNCONFIRMED"
                            val disposition = employeeWarehouseFailureDisposition(priorUnknownOutcome, failure)
                            hasUnknownOutcome = disposition.hasUnknownOutcome
                            rejectionConfirmed = disposition.rejectionConfirmed
                        } finally {
                            submitting = false
                        }
                    }
                }) { Text(when {
                    rejectionConfirmed -> hiltechText("مراجعة الحالة الحالية", "Review current state")
                    attempted -> hiltechText("إعادة نفس الطلب", "Retry same request")
                    else -> hiltechText("تأكيد", "Confirm")
                }) }
            },
            dismissButton = {
                TextButton(enabled = !submitting, onClick = {
                    dialogVisible = false
                    if (!attempted) pendingJson = null
                }) {
                    Text(if (attempted) hiltechText("الاحتفاظ والرجوع", "Keep request and go back") else hiltechText("رجوع", "Back"))
                }
            },
        )
    }
}

@Composable
private fun warehouseResponsibilityError(code: String): String = when (code) {
    "EMPLOYEE_RESPONSIBILITY_TARGET_CHANGED" -> hiltechText(
        "تغير ملف الموظف أو حسابه المرتبط منذ إعداد الطلب. لم يُرسل أي تغيير. حدّث الحالة وراجع الحساب قبل المتابعة.",
        "The employee record or linked account changed since this request was prepared. No change was sent. Refresh and review the account before continuing.",
    )
    "EMPLOYEE_RESPONSIBILITY_PREFLIGHT_FAILED" -> hiltechText(
        "تعذر التحقق من حساب الموظف الحالي. لم يُرسل أي تغيير. حدّث الحالة قبل المتابعة.",
        "Couldn’t verify the employee’s current account. No change was sent. Refresh before continuing.",
    )
    "WAREHOUSE_AUTHORITY_VERSION_CONFLICT" -> hiltechText(
        "تغير التكليف منذ عرضه. راجع الحالة الحالية قبل طلب تغيير آخر.",
        "The assignment changed since it was displayed. Review its current state before requesting another change.",
    )
    "REAUTH_REQUIRED", "UNAUTHENTICATED" -> hiltechText(
        "أكد تسجيل الدخول قبل إعادة المحاولة.", "Confirm your sign-in before retrying.",
    )
    else -> hiltechText(
        "تعذر تأكيد تغيير المسؤولية. تحقق من الاتصال والصلاحية ثم أعد المحاولة. تم الاحتفاظ بالطلب.",
        "Couldn’t confirm the responsibility change. Check your connection and authority, then retry. The request is retained.",
    )
}

@Composable
private fun warehouseResponsibilityLabel(key: String): String = when (key) {
    "WAREHOUSE_VIEWER" -> hiltechText("عرض المخزن", "Warehouse viewer")
    "WAREHOUSE_OPERATOR" -> hiltechText("تشغيل المخزن", "Warehouse operator")
    "WAREHOUSE_MANAGER" -> hiltechText("إدارة المخزن", "Warehouse manager")
    "ASSET_MASTER_MANAGER" -> hiltechText("إدارة بيانات المعدات", "Asset master manager")
    "INVENTORY_ADJUSTMENT_APPROVER" -> hiltechText("اعتماد تسويات المخزون", "Inventory adjustment approver")
    "INVENTORY_VALUE_VIEWER" -> hiltechText("عرض قيمة المخزون", "Inventory value viewer")
    else -> hiltechText("مسؤولية مخزن أخرى", "Other warehouse responsibility")
}

@Composable
private fun warehouseResponsibilityDescription(key: String): String = when (key) {
    "WAREHOUSE_OPERATOR" -> hiltechText(
        "تنفيذ عمليات المخزن في نطاق هذا المخزن وفق قواعد كل عملية. لا يمنح إدارة الصلاحيات أو اعتماد التسويات أو عرض القيم المالية.",
        "Perform warehouse operations in this warehouse under each operation’s rules. Does not grant authority administration, adjustment approval, or financial value access.",
    )
    else -> hiltechText(
        "عرض بيانات المخزن المسموح بها دون تنفيذ عمليات المخزون أو عرض القيم المالية.",
        "Read permitted warehouse information without inventory operations or financial value access.",
    )
}
