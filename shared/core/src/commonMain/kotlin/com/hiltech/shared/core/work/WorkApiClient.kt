package com.hiltech.shared.core.work

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

class WorkApiClient(private val api:HiltechApiClient,private val json:Json=Json{ignoreUnknownKeys=true;explicitNulls=false}){
    suspend fun list(projectId:String,installationId:String): List<WorkOrderDto> = api.request(HttpMethod.Get,"/v1/projects/${id(projectId)}/work-orders",HiltechRequestOptions(installationId=installationId),decode={json.decodeFromString(it)})
    suspend fun get(workOrderId:String,installationId:String): WorkOrderDto = api.request(HttpMethod.Get,"/v1/work-orders/${id(workOrderId)}",HiltechRequestOptions(installationId=installationId),decode={json.decodeFromString(it)})
    suspend fun workTypes(projectId:String,installationId:String): List<WorkTypeChoiceDto> = api.request(HttpMethod.Get,"/v1/projects/${id(projectId)}/work-types",HiltechRequestOptions(installationId=installationId),decode={json.decodeFromString(it)})
    suspend fun create(projectId:String,r:CreateWorkOrderRequestDto,installationId:String)=workCommand(HttpMethod.Post,"/v1/projects/${id(projectId)}/work-orders",r.operationId,r,CreateWorkOrderRequestDto.serializer(),installationId)
    suspend fun update(workOrderId:String,r:UpdateWorkOrderRequestDto,installationId:String)=workCommand(HttpMethod.Put,"/v1/work-orders/${id(workOrderId)}/details",r.operationId,r,UpdateWorkOrderRequestDto.serializer(),installationId)
    suspend fun plan(workOrderId:String,r:PlanWorkRequestDto,installationId:String)=workCommand(HttpMethod.Post,"/v1/work-orders/${id(workOrderId)}/plan",r.operationId,r,PlanWorkRequestDto.serializer(),installationId)
    suspend fun revise(workOrderId:String,r:ReviseInstructionRequestDto,installationId:String)=workCommand(HttpMethod.Post,"/v1/work-orders/${id(workOrderId)}/instruction-revisions",r.operationId,r,ReviseInstructionRequestDto.serializer(),installationId)
    suspend fun createTask(workOrderId:String,r:CreateWorkTaskRequestDto,installationId:String):WorkTaskMutationResponseDto=command(HttpMethod.Post,"/v1/work-orders/${id(workOrderId)}/tasks",r.operationId,r,CreateWorkTaskRequestDto.serializer(),WorkTaskMutationResponseDto.serializer(),installationId)
    suspend fun updateTask(taskId:String,r:UpdateWorkTaskRequestDto,installationId:String):WorkTaskMutationResponseDto=command(HttpMethod.Put,"/v1/work-tasks/${id(taskId)}",r.operationId,r,UpdateWorkTaskRequestDto.serializer(),WorkTaskMutationResponseDto.serializer(),installationId)
    suspend fun addDependency(workOrderId:String,r:AddWorkDependencyRequestDto,installationId:String):WorkDependencyMutationResponseDto=command(HttpMethod.Post,"/v1/work-orders/${id(workOrderId)}/dependencies",r.operationId,r,AddWorkDependencyRequestDto.serializer(),WorkDependencyMutationResponseDto.serializer(),installationId)
    suspend fun removeDependency(workOrderId:String,dependencyId:String,r:RemoveWorkDependencyRequestDto,installationId:String):WorkDependencyMutationResponseDto=command(HttpMethod.Delete,"/v1/work-orders/${id(workOrderId)}/dependencies/${id(dependencyId)}",r.operationId,r,RemoveWorkDependencyRequestDto.serializer(),WorkDependencyMutationResponseDto.serializer(),installationId)
    suspend fun activate(projectId:String,r:ActivateProjectRequestDto,installationId:String):ProjectActivationResponseDto=command(HttpMethod.Post,"/v1/projects/${id(projectId)}/activate",r.operationId,r,ActivateProjectRequestDto.serializer(),ProjectActivationResponseDto.serializer(),installationId)
    private suspend fun<T>workCommand(m:HttpMethod,p:String,o:String,r:T,s:KSerializer<T>,i:String):WorkOrderMutationResponseDto=command(m,p,o,r,s,WorkOrderMutationResponseDto.serializer(),i)
    private suspend fun<T,R>command(m:HttpMethod,p:String,o:String,r:T,s:KSerializer<T>,result:KSerializer<R>,i:String):R{ id(o);return api.request(m,p,HiltechRequestOptions(installationId=i,idempotencyKey=o),json.encodeToString(s,r),decode={json.decodeFromString(result,it)})}
    private fun id(value:String)=value.trim().also{require(UUID.matches(it)){"Identifiers must use canonical UUID format."}}
    private companion object{val UUID=Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")}
}
