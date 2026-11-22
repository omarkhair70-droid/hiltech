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

/** An actual HILTECH team can request catalogued company goods. The material
 * must be received by Warehouse before any office issuance. This is NOT a
 * supplier-service form or a general Asset creation shortcut. */
@Composable
fun OfficeStockNeedSection(
    api:OfficeStockNeedApiClient,organizationId:String,installationId:String,
    newOperationId:()->String,
) {
    val scope=rememberCoroutineScope()
    var loading by remember(organizationId) { mutableStateOf(false) }
    var error by remember(organizationId) { mutableStateOf<String?>(null) }
    var teams by remember(organizationId) { mutableStateOf<List<OfficeTeamOptionDto>>(emptyList()) }
    var chosenTeam by remember(organizationId) { mutableStateOf<OfficeTeamOptionDto?>(null) }
    var places by remember(organizationId) { mutableStateOf<List<OfficeDestinationOptionDto>>(emptyList()) }
    var place by remember(organizationId) { mutableStateOf<OfficeDestinationOptionDto?>(null) }
    var items by remember(organizationId) { mutableStateOf<List<OfficeStockItemOptionDto>>(emptyList()) }
    var item by remember(organizationId) { mutableStateOf<OfficeStockItemOptionDto?>(null) }
    var needs by remember(organizationId) { mutableStateOf<List<OfficeStockNeedDto>>(emptyList()) }
    var showTeams by remember(organizationId) { mutableStateOf(false) }
    var showPlaces by remember(organizationId) { mutableStateOf(false) }
    var showItems by remember(organizationId) { mutableStateOf(false) }
    var search by remember(organizationId) { mutableStateOf("") }
    var qty by remember(organizationId) { mutableStateOf("") }
    var reason by remember(organizationId) { mutableStateOf("") }
    var pendingCreate by remember(organizationId) { mutableStateOf<String?>(null) }

    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Office and team stocked purchases",style=MaterialTheme.typography.titleLarge)
        Text("A company team requests a real stocked item. Technical review is separate from purchasing and Warehouse receiving.",
            style=MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled=!loading,onClick={
            loading=true
            error=null
            scope.launch {
                try { teams=api.teams(organizationId,installationId) }
                catch(e:Exception) { error=e.message?:"Company team purchase permission denied." }
                finally { loading=false }
            }
        }) { Text("Load my authorized office teams") }
        OutlinedButton(enabled=!loading&&teams.isNotEmpty(),onClick={showTeams=true}) {
            Text(chosenTeam?.let { it.code+" · "+it.name }?:"Select actual company team")
        }
        DropdownMenu(expanded=showTeams,onDismissRequest={showTeams=false}) {
            teams.forEach { team ->
                DropdownMenuItem(text={Text(team.code+" · "+team.name)},onClick={
                    chosenTeam=team
                    showTeams=false
                    place=null
                    item=null
                    places=emptyList()
                    items=emptyList()
                    needs=emptyList()
                    pendingCreate=null
                    loading=true
                    scope.launch {
                        try {
                            places=api.destinations(team.teamId,installationId)
                            needs=api.list(team.teamId,installationId)
                            items=api.items(team.teamId,"",installationId)
                        } catch(e:Exception) { error=e.message?:"Unable to read team purchasing sources." }
                        finally {loading=false}
                    }
                })
            }
        }
        val team=chosenTeam
        if(team!=null) {
            Text(if(team.canRequest) "May request office goods" else "Authorized technical reviewer",
                style=MaterialTheme.typography.bodySmall)
            if(team.canRequest) {
                OutlinedButton(enabled=!loading&&places.isNotEmpty(),onClick={showPlaces=true}) {
                    Text(place?.let { it.code+" · "+it.name }?:"Select approved delivery Warehouse")
                }
                DropdownMenu(expanded=showPlaces,onDismissRequest={showPlaces=false}) {
                    places.forEach { dest ->
                        DropdownMenuItem(text={Text(dest.code+" · "+dest.name)},onClick={
                            place=dest;showPlaces=false;pendingCreate=null
                        })
                    }
                }
                OutlinedTextField(value=search,onValueChange={search=it},
                    label={Text("Find canonical stock item")})
                OutlinedButton(enabled=!loading,onClick={
                    loading=true
                    error=null
                    scope.launch {
                        try {items=api.items(team.teamId,search,installationId)}
                        catch(e:Exception) {error=e.message?:"Stock catalog could not be searched."}
                        finally {loading=false}
                    }
                }) { Text("Search stock catalog") }
                OutlinedButton(enabled=!loading&&items.isNotEmpty(),onClick={showItems=true}) {
                    Text(item?.let { it.code+" · "+it.name }?:"Select stock item")
                }
                DropdownMenu(expanded=showItems,onDismissRequest={showItems=false}) {
                    items.forEach { candidate ->
                        DropdownMenuItem(text={
                            Text(candidate.code+" · "+candidate.name+" · "+candidate.unitCode)
                        },onClick={
                            item=candidate;showItems=false;pendingCreate=null
                        })
                    }
                }
                OutlinedTextField(value=qty,onValueChange={qty=it;pendingCreate=null},
                    label={Text("Quantity")})
                item?.let {
                    Text("Unit: "+it.unitCode+" · item version "+it.version,
                        style=MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(value=reason,onValueChange={reason=it;pendingCreate=null},
                    label={Text("Reason and business use for requested goods")})
                val selectedPlace=place
                val selectedItem=item
                if(selectedPlace!=null&&selectedItem!=null) {
                    OutlinedButton(enabled=!loading&&
                        (qty.toDoubleOrNull()?.let {it>0.0}==true)&&
                        reason.trim().length in 12..500,onClick={
                        loading=true
                        error=null
                        scope.launch {
                            try {
                                val op=pendingCreate?:newOperationId().also {pendingCreate=it}
                                api.create(team.teamId,CreateOfficeStockNeedDto(
                                    op,team.version,selectedPlace.locationId,
                                    selectedPlace.version,selectedItem.id,selectedItem.version,
                                    qty,selectedItem.unitCode,reason.trim(),
                                ),installationId)
                                pendingCreate=null
                                qty=""
                                reason=""
                                needs=api.list(team.teamId,installationId)
                            } catch(e:Exception) {
                                error=e.message?:"Office source changed; refresh current options."
                            } finally {loading=false}
                        }
                    }) {Text(if(loading) "Saving…" else "Create company-team goods request")}
                }
            }
            OutlinedButton(enabled=!loading,onClick={
                loading=true
                error=null
                scope.launch {
                    try {needs=api.list(team.teamId,installationId)}
                    catch(e:Exception) {error=e.message?:"Unable to refresh team needs."}
                    finally {loading=false}
                }
            }) {Text("Refresh team purchase requests")}
            needs.forEach { row ->
                OfficeStockNeedCard(row,api,installationId,newOperationId) { updated ->
                    needs=needs.map { if(it.id==updated.id) updated else it }
                }
            }
        }
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    }
}

@Composable
private fun OfficeStockNeedCard(
    need:OfficeStockNeedDto,api:OfficeStockNeedApiClient,installationId:String,
    newOperationId:()->String,onChanged:(OfficeStockNeedDto)->Unit,
) {
    val scope=rememberCoroutineScope()
    var busy by remember(need.id) { mutableStateOf(false) }
    var error by remember(need.id) { mutableStateOf<String?>(null) }
    var pendingOp by remember(need.id) { mutableStateOf<String?>(null) }
    var pendingType by remember(need.id) { mutableStateOf<String?>(null) }
    var editQty by remember(need.id) { mutableStateOf(need.requestedQuantity) }
    var editReason by remember(need.id) { mutableStateOf(need.justification) }
    var managerReason by remember(need.id) { mutableStateOf("") }
    Card(modifier=Modifier.fillMaxWidth()) {
        Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text("Need #"+need.id.take(8)+" · "+need.state,
                style=MaterialTheme.typography.titleMedium)
            Text(need.teamName+" → "+need.locationName)
            Text(need.requestedQuantity+" "+need.unitCode+
                " · source version "+need.teamVersion,
                style=MaterialTheme.typography.bodySmall)
            Text(need.justification)
            Text(if(need.sourceCurrent) "Current verified source" else
                "Team, Warehouse or stock source changed: approval blocked",
                style=MaterialTheme.typography.bodySmall)
            need.lastReviewReason?.let {Text("Manager feedback: "+it)}
            fun act(kind:String,operation:suspend(String)->OfficeStockNeedDto) {
                val op=if(pendingType==kind) pendingOp?:newOperationId().also {pendingOp=it}
                else newOperationId().also {pendingOp=it;pendingType=kind}
                busy=true
                error=null
                scope.launch {
                    try {
                        val updated=operation(op)
                        pendingOp=null
                        pendingType=null
                        editQty=updated.requestedQuantity
                        editReason=updated.justification
                        onChanged(updated)
                    } catch(e:Exception) {
                        error=e.message?:"Source changed or permission was revoked."
                    } finally {busy=false}
                }
            }
            if(need.canSubmit) OutlinedButton(enabled=!busy,onClick={
                act("submit"){op->
                    api.submit(need.id,SubmitPurchaseRequirementDraftDto(
                        op,need.version,
                    ),installationId).need
                }
            }) {Text("Send to technical reviewer")}
            if(need.canRevise) {
                OutlinedTextField(value=editQty,onValueChange={editQty=it;pendingOp=null},
                    label={Text("Revised quantity")})
                OutlinedTextField(value=editReason,onValueChange={editReason=it;pendingOp=null},
                    label={Text("Revised business reason")})
                OutlinedButton(enabled=!busy&&editQty.toDoubleOrNull()?.let {it>0.0}==true &&
                    editReason.trim().length in 12..500,onClick={
                    act("revise"){op->
                        api.revise(need.id,ReviseOfficeStockNeedDto(
                            op,need.version,need.teamVersion,need.stockItemVersion,
                            editQty,need.unitCode,editReason.trim(),
                        ),installationId).need
                    }
                }) {Text("Save revised request")}
            }
            if(need.canWithdraw) OutlinedButton(enabled=!busy,onClick={
                act("withdraw"){op->
                    api.withdraw(need.id,WithdrawPurchaseRequirementDraftDto(
                        op,need.version,
                    ),installationId).need
                }
            }) {Text("Withdraw request")}
            if(need.canTechnicalReview) {
                OutlinedTextField(value=managerReason,onValueChange={
                    managerReason=it;pendingOp=null
                },label={Text("Technical decision reason")})
                if(need.state=="SUBMITTED") {
                    OutlinedButton(enabled=!busy&&need.sourceCurrent,onClick={
                        act("ready"){op->
                            api.review(need.id,ReviewPurchaseRequirementDto(
                                op,need.version,"READY_FOR_SOURCING",
                            ),installationId).need
                        }
                    }) {Text("Approve technical need, not purchasing")}
                }
                OutlinedButton(enabled=!busy&&managerReason.isNotBlank(),onClick={
                    act("needs-info"){op->
                        api.review(need.id,ReviewPurchaseRequirementDto(
                            op,need.version,"NEEDS_INFO",managerReason.trim(),
                        ),installationId).need
                    }
                }) {Text("Request clarification")}
                OutlinedButton(enabled=!busy&&managerReason.isNotBlank(),onClick={
                    act("reject"){op->
                        api.review(need.id,ReviewPurchaseRequirementDto(
                            op,need.version,"REJECTED",managerReason.trim(),
                        ),installationId).need
                    }
                }) {Text("Reject technical need")}
            }
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        }
    }
}
