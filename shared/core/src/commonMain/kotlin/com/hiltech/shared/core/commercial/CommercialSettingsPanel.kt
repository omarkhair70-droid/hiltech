package com.hiltech.shared.core.commercial

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.design.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
@Composable
internal fun CommercialSettingsPanel(
    settings: CommercialDocumentSettingsDto,
    busy: Boolean,
    pickLogo: (suspend () -> ByteArray?)?,
    onSave: (CommercialDocumentProfileDto) -> Unit,
    onBack: () -> Unit,
) {
    var draft by remember(settings) { mutableStateOf(settings.profile) }
    var picking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onBack, enabled = !busy && !picking) { Text(hiltechText("الرجوع للمبيعات", "Back to sales")) }
        HiltechSectionHeading("HILTECH / SETTINGS",hiltechText("إعدادات الشركة والمستندات","Company & document settings"),
            hiltechText("تطبق البيانات على العروض الجديدة؛ الإصدارات السابقة لا تتغير.","New releases use these details; earlier releases remain unchanged."))
        HiltechPanel(Modifier.fillMaxWidth()) {
            Text(hiltechText("بيانات الإصدار","Issuer details"), style = MaterialTheme.typography.titleMedium)
            SettingField(hiltechText("الاسم القانوني","Legal name"), draft.legalName) { draft = draft.copy(legalName = it) }
            SettingField(hiltechText("العنوان","Address"), draft.address) { draft = draft.copy(address = it) }
            SettingField(hiltechText("الهاتف","Phone"), draft.phone) { draft = draft.copy(phone = it) }
            SettingField(hiltechText("البريد الإلكتروني","Email"), draft.email) { draft = draft.copy(email = it) }
            SettingField(hiltechText("الموقع","Website"), draft.website) { draft = draft.copy(website = it) }
            SettingField(hiltechText("السجل التجاري","Registration number"), draft.registration) { draft = draft.copy(registration = it) }
            SettingField(hiltechText("الرقم الضريبي","Tax number"), draft.taxNumber) { draft = draft.copy(taxNumber = it) }
            Text(if (draft.logoBase64.isBlank()) hiltechText("لا يوجد شعار مرفوع","No logo uploaded") else hiltechText("تم اختيار شعار","Logo selected"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pickLogo != null) OutlinedButton(enabled = !picking && !busy, onClick = {
                    picking = true; error = false
                    scope.launch {
                        try {
                            pickLogo()?.let { bytes ->
                                require(bytes.size in 1..1048576)
                                draft = draft.copy(logoBase64 = Base64.encode(bytes))
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { error = true }
                        finally { picking = false }
                    }
                }) { Text(hiltechText("اختيار شعار PNG/JPEG","Choose PNG/JPEG logo")) }
                if (draft.logoBase64.isNotBlank()) TextButton(enabled = !busy && !picking,onClick = { draft = draft.copy(logoBase64 = "") }) { Text(hiltechText("إزالة","Remove")) }
            }
            if (error) Text(hiltechText("تعذر قراءة الصورة (الحد 1 ميجابايت).","Could not read image (1 MiB limit)."), color = MaterialTheme.colorScheme.error)
        }
        HiltechPanel(Modifier.fillMaxWidth()) {
            Text(hiltechText("قواعد المستندات","Document defaults"), style = MaterialTheme.typography.titleMedium)
            SettingField(hiltechText("بادئة رقم العرض","Quote number prefix"), draft.quotePrefix) { draft = draft.copy(quotePrefix = it.uppercase()) }
            SettingField(hiltechText("شروط الدفع الافتراضية","Default payment terms"), draft.paymentTerms) { draft = draft.copy(paymentTerms = it) }
            SettingField(hiltechText("شروط التسليم الافتراضية","Default delivery terms"), draft.deliveryTerms) { draft = draft.copy(deliveryTerms = it) }
            Text(hiltechText("يولّد النظام الرقم الفريد تلقائيًا. مبالغ الخصم والضريبة تُحدد لكل عرض.","Unique numbers are generated automatically. Discount and tax amounts are specified per quote."))
        }
        Button(enabled = !busy && !picking && draft != settings.profile && draft.legalName.isNotBlank() &&
            Regex("[A-Z0-9-]{1,12}").matches(draft.quotePrefix), onClick = { onSave(draft) }) {
            Text(hiltechText("حفظ إعدادات المستندات","Save document settings"))
        }
    }
}
@Composable
private fun SettingField(label: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(value,change,label = { Text(label) },modifier = Modifier.fillMaxWidth())
}
