package com.hiltech.shared.core.procurement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Source-connected, no placeholder service or invented approval. This native
 * reusable surface is not mounted until the UX/integration owners approve it. */
@Composable
fun ServicePurchaseNeedSection(
    api: ServicePurchaseNeedApiClient, organizationId: String, installationId: String,
    newOperationId: () -> String,
) {
    val scope = rememberCoroutineScope()
    var loading by remember(organizationId, installationId) { mutableStateOf(false) }
    var error by remember(organizationId, installationId) { mutableStateOf<String?>(null) }
    var teams by remember(organizationId, installationId) { mutableStateOf<List<ServicePurchaseTeamDto>>(emptyList()) }
    var selected by remember(organizationId, installationId) { mutableStateOf<ServicePurchaseTeamDto?>(null) }
    var showTeams by remember(organizationId, installationId) { mutableStateOf(false) }
    var needs by remember(organizationId, installationId) { mutableStateOf<List<ServicePurchaseNeedDto>>(emptyList()) }
    var teamsLoaded by remember(organizationId, installationId) { mutableStateOf(false) }
    var needsLoaded by remember(organizationId, installationId) { mutableStateOf(false) }
    var confirmation by remember(organizationId, installationId) { mutableStateOf<String?>(null) }
    var spec by remember(organizationId, installationId) { mutableStateOf("") }
    var acceptance by remember(organizationId, installationId) { mutableStateOf("") }
    var why by remember(organizationId, installationId) { mutableStateOf("") }
    var pendingCreate by remember(organizationId, installationId) { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("طلبات الخدمات الخارجية / Service requests",style = MaterialTheme.typography.titleLarge)
        Text("الخدمات خارج المخزون تمر بمراجعة فنية مستقلة؛ جاهزية الطلب لا تعني إصدار أمر للمورد أو إثبات تنفيذ أو اعتماد مالي.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = !loading,onClick = {
            loading=true;error=null;confirmation=null
            // Revocation/failed refresh must not retain old team actions.
            teams=emptyList();selected=null;showTeams=false;needs=emptyList()
            teamsLoaded=false;needsLoaded=false
            spec="";acceptance="";why="";pendingCreate=null
            scope.launch {
                try {
                    teams=api.teams(organizationId,installationId)
                    teamsLoaded=true
                } catch(e:Exception) { error=e.message ?: "Team permission unavailable." }
                finally { loading=false }
            }
        }) { Text("تحميل الفرق المتاحة لي") }
        if(teamsLoaded && teams.isEmpty()) Text("لا توجد فرق مخوّل لك العمل عليها حاليًا.")
        OutlinedButton(enabled = !loading && teams.isNotEmpty(),onClick = {showTeams=true}) {
            Text(selected?.name ?: "اختار الفريق الحالي")
        }
        DropdownMenu(expanded=showTeams,onDismissRequest={showTeams=false}) {
            teams.forEach { team ->
                DropdownMenuItem(text={ Text(team.name) },onClick={
                    showTeams=false;error=null;confirmation=null
                    if(selected?.id!=team.id) {
                        spec="";acceptance="";why="";pendingCreate=null
                    }
                    selected=team;needs=emptyList();needsLoaded=false
                    loading=true
                    scope.launch {
                        try {
                            needs=api.list(team.id,installationId)
                            needsLoaded=true
                        } catch(e:Exception) { error=e.message ?: "Unable to read service requests." }
                        finally { loading=false }
                    }
                })
            }
        }
        val team=selected
        if(team!=null) {
            Text(if(team.canRequest) "مسموح لي بإنشاء طلب خدمة" else
                "مراجعة فنية مستقلة وفق صلاحيات الفريق",
                style=MaterialTheme.typography.bodySmall)
            if(team.canRequest) {
                OutlinedTextField(value=spec,onValueChange={
                    spec=it;pendingCreate=null
                },label={Text("Service scope (what must be done)")},
                    modifier=Modifier.fillMaxWidth())
                OutlinedTextField(value=acceptance,onValueChange={
                    acceptance=it;pendingCreate=null
                },label={Text("Evidence and acceptance criteria")},
                    modifier=Modifier.fillMaxWidth())
                OutlinedTextField(value=why,onValueChange={
                    why=it;pendingCreate=null
                },label={Text("Business justification")},
                    modifier=Modifier.fillMaxWidth())
                OutlinedButton(
                    enabled=!loading && spec.trim().length in 20..2000 &&
                        acceptance.trim().length in 20..2000 && why.trim().length in 12..500,
                    onClick={
                        loading=true;error=null
                        scope.launch {
                            try {
                                val op=pendingCreate?:newOperationId().also {pendingCreate=it}
                                api.create(team.id,CreateServicePurchaseNeedDto(
                                    op,team.version,spec.trim(),acceptance.trim(),why.trim(),
                                ),installationId)
                                pendingCreate=null
                                spec="";acceptance="";why=""
                                confirmation="تم حفظ مسودة طلب الخدمة؛ لم تُرسل للمورد."
                                needs=emptyList();needsLoaded=false
                                try {
                                    needs=api.list(team.id,installationId)
                                    needsLoaded=true
                                } catch(e:Exception) {
                                    error="تم حفظ المسودة، لكن تعذر تحديث القائمة؛ أعد تحميل الطلبات."
                                }
                            } catch(e:Exception) {
                                error=e.message ?: "Could not save service need; verify current team."
                            } finally { loading=false }
                        }
                    }
                ) { Text(if(loading) "جارٍ الحفظ…" else "حفظ مسودة طلب خدمة") }
            }
            OutlinedButton(enabled=!loading,onClick={
                loading=true;error=null
                needs=emptyList();needsLoaded=false
                scope.launch {
                    try {
                        needs=api.list(team.id,installationId)
                        needsLoaded=true
                    } catch(e:Exception) { error=e.message ?: "Unable to refresh service requests." }
                    finally { loading=false }
                }
            }) { Text("تحديث طلبات الفريق") }
            if(needsLoaded && needs.isEmpty()) Text("لا توجد طلبات خدمة حالية لهذا الفريق.")
            needs.forEach { need ->
                ServicePurchaseNeedCard(need,api,installationId,newOperationId) { changed ->
                    needs=needs.map { if(it.id==changed.id) changed else it }
                }
            }
        }
        confirmation?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun ServicePurchaseNeedCard(
    need: ServicePurchaseNeedDto, api: ServicePurchaseNeedApiClient,
    installationId: String, newOperationId: () -> String,
    onChanged: (ServicePurchaseNeedDto) -> Unit,
) {
    val scope=rememberCoroutineScope()
    var busy by remember(need.id) { mutableStateOf(false) }
    var error by remember(need.id) { mutableStateOf<String?>(null) }
    var operation by remember(need.id) { mutableStateOf<String?>(null) }
    var operationType by remember(need.id) { mutableStateOf<String?>(null) }
    var scopeText by remember(need.id) { mutableStateOf(need.specification) }
    var criteria by remember(need.id) { mutableStateOf(need.acceptanceCriteria) }
    var reason by remember(need.id) { mutableStateOf(need.justification) }
    var reviewReason by remember(need.id) { mutableStateOf("") }

    Card(modifier=Modifier.fillMaxWidth()) {
        Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text("Service · "+need.id.take(8)+" · "+need.state,
                style=MaterialTheme.typography.titleMedium)
            Text(need.teamName+" · source version "+need.teamVersion,
                style=MaterialTheme.typography.bodySmall)
            Text(need.specification)
            Text("Acceptance: "+need.acceptanceCriteria,
                style=MaterialTheme.typography.bodySmall)
            Text(need.justification)
            if(!need.sourceCurrent) Text(
                "Team or authority source changed; new decisions are blocked.",
                color=MaterialTheme.colorScheme.error,
            )
            need.lastReviewReason?.let { Text("Reviewer: "+it) }
            Text("Supplier not contacted · No service order or invoice",
                style=MaterialTheme.typography.bodySmall)

            fun act(type: String, call: suspend (String) -> ServicePurchaseNeedDto) {
                val op = if(operationType==type)
                    operation?:newOperationId().also { operation=it }
                else newOperationId().also { operation=it;operationType=type }
                busy=true;error=null
                scope.launch {
                    try {
                        val changed=call(op)
                        operation=null;operationType=null
                        scopeText=changed.specification
                        criteria=changed.acceptanceCriteria
                        reason=changed.justification
                        onChanged(changed)
                    } catch(e:Exception) {
                        error=e.message ?: "Current authority or version rejected this action."
                    } finally {busy=false}
                }
            }
            if(need.canSubmit) OutlinedButton(enabled=!busy,onClick={
                act("submit"){ op ->
                    api.submit(need.id,ServicePurchaseNeedActionDto(
                        op,need.version),installationId).need
                }
            }) {Text("Submit for independent technical review")}

            if(need.canRevise) {
                OutlinedTextField(value=scopeText,onValueChange={
                    scopeText=it;operation=null
                },label={Text("Revised service scope")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(value=criteria,onValueChange={
                    criteria=it;operation=null
                },label={Text("Revised acceptance evidence")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(value=reason,onValueChange={
                    reason=it;operation=null
                },label={Text("Revised business reason")},modifier=Modifier.fillMaxWidth())
                OutlinedButton(enabled=!busy && scopeText.trim().length in 20..2000 &&
                    criteria.trim().length in 20..2000 && reason.trim().length in 12..500,
                    onClick={
                    act("revise"){ op ->
                        api.revise(need.id,ReviseServicePurchaseNeedDto(
                            op,need.version,scopeText.trim(),criteria.trim(),reason.trim(),
                        ),installationId).need
                    }
                }) { Text("Save immutable revision") }
            }
            if(need.canWithdraw) OutlinedButton(enabled=!busy,onClick={
                act("withdraw"){ op ->
                    api.withdraw(need.id,ServicePurchaseNeedActionDto(
                        op,need.version),installationId).need
                }
            }) {Text("Withdraw service request")}
            if(need.canTechnicalReview) {
                OutlinedTextField(value=reviewReason,onValueChange={
                    reviewReason=it;operation=null
                },label={Text("Reason for clarification or rejection")})
                OutlinedButton(enabled=!busy && need.sourceCurrent,onClick={
                    act("ready"){ op ->
                        api.review(need.id,ServicePurchaseNeedReviewDto(
                            op,need.version,"READY_FOR_SOURCING"),installationId).need
                    }
                }) {Text("Technical need ready — not purchasing approval")}
                OutlinedButton(enabled=!busy && reviewReason.trim().length in 3..500,onClick={
                    act("needs-info"){ op ->
                        api.review(need.id,ServicePurchaseNeedReviewDto(
                            op,need.version,"NEEDS_INFO",reviewReason.trim()),installationId).need
                    }
                }) {Text("Request clarification")}
                OutlinedButton(enabled=!busy && reviewReason.trim().length in 3..500,onClick={
                    act("reject"){ op ->
                        api.review(need.id,ServicePurchaseNeedReviewDto(
                            op,need.version,"REJECTED",reviewReason.trim()),installationId).need
                    }
                }) {Text("Reject technical need")}
            }
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        }
    }
}
