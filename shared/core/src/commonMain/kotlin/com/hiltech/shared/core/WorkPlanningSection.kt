package com.hiltech.shared.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.projects.ProjectPlanDto
import com.hiltech.shared.core.work.WorkOrderDto
import com.hiltech.shared.core.work.WorkTypeChoiceDto

sealed interface WorkPlanningUiAction {
    data class Create(val projectId:String,val siteId:String,val projectSiteId:String,val areaId:String?,val workPackageId:String?,val workTypeCode:String,val workTypeRevision:Int,val title:String,val description:String?,val priorityCode:String?,val baseProjectVersion:Long,val baselineVersion:Int):WorkPlanningUiAction
    data class Update(val workOrderId:String,val areaId:String?,val workPackageId:String?,val title:String,val description:String?,val priorityCode:String,val baseVersion:Long):WorkPlanningUiAction
    data class Plan(val workOrderId:String,val workTypeCode:String,val workTypeRevision:Int,val instructionJson:String?,val instructionSummary:String?,val baseVersion:Long,val baselineVersion:Int):WorkPlanningUiAction
    data class ReviseInstruction(val workOrderId:String,val instructionJson:String,val summary:String?,val changeReason:String,val baseVersion:Long):WorkPlanningUiAction
    data class CreateTask(val workOrderId:String,val taskCode:String?,val title:String,val sortOrder:Int,val mandatory:Boolean,val baseVersion:Long):WorkPlanningUiAction
    data class AddDependency(val workOrderId:String,val predecessorWorkOrderId:String,val lagMinutes:Long,val baseVersion:Long):WorkPlanningUiAction
    data class Activate(val projectId:String,val baseVersion:Long,val baselineVersion:Int):WorkPlanningUiAction
}

@Composable
fun WorkPlanningSection(plan:ProjectPlanDto,workTypes:List<WorkTypeChoiceDto>,orders:List<WorkOrderDto>,onAction:(WorkPlanningUiAction)->Unit){
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("أوامر العمل وربط السياسات",style=MaterialTheme.typography.titleMedium)
        Text("كل أمر مخطط يحتفظ بمراجعات نوع العمل والسياسات والتعليمات وقائمة الفحص كما كانت عند التخطيط.")
        Text("الجاهزية: لم تُقيّم بعد — لا توجد مطالبة بتوفر أفراد أو مواد أو أدوات في هذه المرحلة.")
        if(workTypes.isEmpty()) Text("لا توجد مراجعة WorkType نشطة قابلة للاختيار.") else Text("الأنواع النشطة: "+workTypes.joinToString{"${it.workType.code} r${it.workType.revision}"})
        if(orders.isEmpty()) Text("لا توجد أوامر عمل لهذا المشروع بعد.")
        orders.forEach{WorkOrderCard(it,workTypes,onAction)}
        if(plan.project.lifecycleState in setOf("PLANNING","READY")&&plan.projectSites.isNotEmpty()&&workTypes.isNotEmpty()) CreateWorkOrderForm(plan,workTypes,onAction)
        if(plan.project.lifecycleState=="READY"){
            Button(enabled=orders.any{it.countsTowardProjectProgress}&&orders.none{it.countsTowardProjectProgress&&it.lifecycleState=="DRAFT"},onClick={onAction(WorkPlanningUiAction.Activate(plan.project.projectId,plan.project.version,plan.project.baselineVersion))}){Text("تفعيل المشروع من خط الأساس الحالي")}
        }
    }
}

@Composable private fun WorkOrderCard(order:WorkOrderDto,types:List<WorkTypeChoiceDto>,onAction:(WorkPlanningUiAction)->Unit){
    var editing by remember(order.workOrderId){mutableStateOf(false)};var revising by remember(order.workOrderId){mutableStateOf(false)};var taskTitle by remember(order.workOrderId){mutableStateOf("")};var predecessor by remember(order.workOrderId){mutableStateOf("")}
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
        Text("${order.workOrderCode} · ${order.title} · ${order.lifecycleState} · v${order.version}",style=MaterialTheme.typography.titleSmall)
        Text("الأولوية ${order.priorityCode} · وزن ${order.progressWeight} · baseline ${order.baselineVersion}")
        Text("الجاهزية: ${if(order.readinessState=="NOT_EVALUATED") "لم تُقيّم بعد" else order.readinessState}")
        order.binding?.let{b->Text("WorkType ${b.workType.code} r${b.workType.revision} · Assignment r${b.assignmentPolicy.revision} · Readiness r${b.readinessPolicy.revision} · Evidence r${b.evidencePolicy.revision} · Review r${b.reviewPolicy.revision}")}
        order.instruction?.let{Text("تعليمات r${it.revision} · schema ${it.payloadSchemaVersion} · ${it.summary?:"دون ملخص"}")}
        if(order.checklist.isNotEmpty()){Text("قائمة الفحص (تعريف فقط)");order.checklist.forEach{Text("• ${it.label} · ${if(it.required)"مطلوب" else "اختياري"} · ${it.completionState}")}}
        if(order.requirements.isNotEmpty()){Text("المتطلبات (غير مُشبعة افتراضياً)");order.requirements.forEach{Text("• ${it.family}/${it.key} · ${it.satisfactionState}")}}
        order.tasks.forEach{Text("مهمة تخطيطية: ${it.taskCode?:"—"} · ${it.title} · ${it.state}")}
        order.dependencies.filter{it.successorWorkOrderId==order.workOrderId}.forEach{Text("يعتمد على ${it.predecessorWorkOrderId.take(8)} · ${it.dependencyType} · ${it.lagMinutes} دقيقة")}
        if(order.lifecycleState in setOf("DRAFT","PLANNED")){
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(onClick={editing=!editing}){Text("تعديل النطاق")};if(order.lifecycleState=="PLANNED")OutlinedButton(onClick={revising=!revising}){Text("مراجعة التعليمات")}}
            if(editing) EditWorkForm(order){title,description,priority->onAction(WorkPlanningUiAction.Update(order.workOrderId,order.areaId,order.workPackageId,title,description,priority,order.version));editing=false}
            if(order.lifecycleState=="DRAFT"&&types.isNotEmpty()) PlanForm(order,types,onAction)
            if(revising) ReviseForm(order){json,summary,reason->onAction(WorkPlanningUiAction.ReviseInstruction(order.workOrderId,json,summary,reason,order.version));revising=false}
            OutlinedTextField(taskTitle,{taskTitle=it},label={Text("مهمة فرعية اختيارية")});Button(enabled=taskTitle.isNotBlank(),onClick={onAction(WorkPlanningUiAction.CreateTask(order.workOrderId,null,taskTitle.trim(),order.tasks.size,true,order.version));taskTitle=""}){Text("إضافة مهمة تخطيطية")}
            OutlinedTextField(predecessor,{predecessor=it},label={Text("WorkOrder ID السابق")});Button(enabled=predecessor.isNotBlank(),onClick={onAction(WorkPlanningUiAction.AddDependency(order.workOrderId,predecessor.trim(),0,order.version));predecessor=""}){Text("إضافة FINISH_TO_START")}
        }
    }
}

@Composable private fun CreateWorkOrderForm(plan:ProjectPlanDto,types:List<WorkTypeChoiceDto>,onAction:(WorkPlanningUiAction)->Unit){val site=plan.projectSites.first();val type=types.first();var title by remember(plan.project.projectId){mutableStateOf("")};var description by remember(plan.project.projectId){mutableStateOf("")};var area by remember(plan.project.projectId){mutableStateOf("")};var pkg by remember(plan.project.projectId){mutableStateOf("")};OutlinedTextField(title,{title=it},label={Text("عنوان أمر العمل")});OutlinedTextField(description,{description=it},label={Text("نطاق العمل — اختياري")});OutlinedTextField(area,{area=it},label={Text("Area ID — اختياري")});OutlinedTextField(pkg,{pkg=it},label={Text("WorkPackage ID — اختياري")});Text("سيُنشأ بـ ${type.workType.code} r${type.workType.revision}; الكود يخصصه الخادم من CodePolicy.");Button(enabled=title.isNotBlank(),onClick={onAction(WorkPlanningUiAction.Create(plan.project.projectId,site.siteId,site.projectSiteId,area.blankNull(),pkg.blankNull(),type.workType.code,type.workType.revision,title.trim(),description.blankNull(),type.defaultPriorityCode,plan.project.version,plan.project.baselineVersion));title="";description=""}){Text("إنشاء DRAFT")}}
@Composable private fun EditWorkForm(order:WorkOrderDto,submit:(String,String?,String)->Unit){var title by remember(order.version){mutableStateOf(order.title)};var description by remember(order.version){mutableStateOf(order.description.orEmpty())};var priority by remember(order.version){mutableStateOf(order.priorityCode)};OutlinedTextField(title,{title=it},label={Text("العنوان")});OutlinedTextField(description,{description=it},label={Text("الوصف")});OutlinedTextField(priority,{priority=it},label={Text("الأولوية")});Button(enabled=title.isNotBlank()&&priority.isNotBlank(),onClick={submit(title.trim(),description.blankNull(),priority.trim())}){Text("حفظ")}}
@Composable private fun PlanForm(order:WorkOrderDto,types:List<WorkTypeChoiceDto>,onAction:(WorkPlanningUiAction)->Unit){val type=types.first();var summary by remember(order.workOrderId){mutableStateOf("")};var json by remember(order.workOrderId){mutableStateOf("")};OutlinedTextField(summary,{summary=it},label={Text("ملخص التعليمات")});OutlinedTextField(json,{json=it},label={Text("JSON تعليمات اختياري؛ القالب عند الفراغ")});Button(onClick={onAction(WorkPlanningUiAction.Plan(order.workOrderId,type.workType.code,type.workType.revision,json.blankNull(),summary.blankNull(),order.version,order.baselineVersion))}){Text("تخطيط وربط المراجعات")}}
@Composable private fun ReviseForm(order:WorkOrderDto,submit:(String,String?,String)->Unit){var json by remember(order.version){mutableStateOf(order.instruction?.structuredPayloadJson?:"{}")};var summary by remember(order.version){mutableStateOf(order.instruction?.summary.orEmpty())};var reason by remember(order.version){mutableStateOf("")};OutlinedTextField(json,{json=it},label={Text("JSON التعليمات")});OutlinedTextField(summary,{summary=it},label={Text("الملخص")});OutlinedTextField(reason,{reason=it},label={Text("سبب التغيير")});Button(enabled=reason.isNotBlank()&&json.isNotBlank(),onClick={submit(json,summary.blankNull(),reason.trim())}){Text("إنشاء مراجعة جديدة")}}
private fun String.blankNull()=trim().takeIf{it.isNotEmpty()}
