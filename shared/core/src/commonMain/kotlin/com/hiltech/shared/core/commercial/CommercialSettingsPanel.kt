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
    mail: CommercialMailConnectionDto?,
    onConnectMail: (String,String,String) -> Unit,
    onDisconnectMail: () -> Unit,
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
        MailConnectionPanel(mail,busy,onConnectMail,onDisconnectMail)
        Button(enabled = !busy && !picking && draft != settings.profile && draft.legalName.isNotBlank() &&
            Regex("[A-Z0-9-]{1,12}").matches(draft.quotePrefix), onClick = { onSave(draft) }) {
            Text(hiltechText("حفظ إعدادات المستندات","Save document settings"))
        }
    }
}
@Composable
private fun MailConnectionPanel(mail: CommercialMailConnectionDto?,busy: Boolean,connect: (String,String,String) -> Unit,disconnect: () -> Unit) {
    HiltechPanel(Modifier.fillMaxWidth()) {
        Text(hiltechText("الحسابات المتصلة","Connected accounts"),style = MaterialTheme.typography.titleMedium)
        Text(hiltechText("بريد الشركة — SMTP","Company email — SMTP"))
        if (mail == null) {
            Text(hiltechText("تعذر قراءة حالة الاتصال.","Connection status unavailable."))
        } else {
            Text(if (mail.configured) hiltechText("حساب محفوظ: ","Saved account: ") + mail.username.orEmpty() else hiltechText("غير متصل","Not connected"))
            if (!mail.setupAvailable) Text(hiltechText("يتطلب تهيئة مفتاح تشفير السيرفر مرة واحدة.","Requires one-time server encryption setup."))
            if (mail.setupAvailable) {
                var host by remember(mail) { mutableStateOf(mail.host ?: mail.allowedHosts.firstOrNull().orEmpty()) }
                var username by remember(mail) { mutableStateOf(mail.username.orEmpty()) }
                var secret by remember(mail) { mutableStateOf("") }
                var editing by remember(mail) { mutableStateOf(!mail.configured) }
                var confirmDisconnect by remember(mail) { mutableStateOf(false) }
                if (mail.configured && !editing) {
                    TextButton(enabled = !busy,onClick = { editing = true }) { Text(hiltechText("تغيير الاتصال","Change connection")) }
                    if (!confirmDisconnect) TextButton(enabled = !busy,onClick = { confirmDisconnect = true }) { Text(hiltechText("فصل البريد","Disconnect email")) }
                    else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !busy,onClick = { disconnect(); confirmDisconnect = false }) { Text(hiltechText("تأكيد الفصل","Confirm disconnect")) }
                        TextButton(onClick = { confirmDisconnect = false }) { Text(hiltechText("إلغاء","Cancel")) }
                    }
                }
                if (editing) {
                    Text(hiltechText("اختر خادم البريد","Select mail server"))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        mail.allowedHosts.forEach { option ->
                            FilterChip(selected = host == option,onClick = { host = option },label = { Text(option) },enabled = !busy)
                        }
                    }
                    SettingField(hiltechText("عنوان البريد","Email address"),username) { username = it }
                    OutlinedTextField(secret,{ secret = it },label = { Text(hiltechText("كلمة مرور التطبيق","App password")) },modifier = Modifier.fillMaxWidth(),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),singleLine = true,enabled = !busy)
                    Text(hiltechText("استخدم بيانات SMTP التي يسمح بها مزود البريد. لا تُحفظ كلمة المرور في الجهاز.","Use SMTP credentials supported by your provider. Password is not saved on this device."))
                    Button(enabled = !busy && host in mail.allowedHosts && username.contains("@") && secret.isNotBlank(),onClick = {
                        val supplied = secret
                        secret = ""
                        connect(host,username.trim(),supplied)
                    }) { Text(hiltechText("اختبار الاتصال وربط البريد","Test and connect")) }
                    if (mail.configured) TextButton(onClick = { secret = ""; editing = false }) { Text(hiltechText("إلغاء","Cancel")) }
                }
            }
        }
        HorizontalDivider()
        Text(hiltechText("WhatsApp","WhatsApp"))
        Text(hiltechText("مشاركة PDF عبر تطبيقات الهاتف متاحة. الإرسال المباشر يحتاج اتصال Meta الخاص بالشركة على السيرفر.","Native PDF sharing is available on mobile. Direct WhatsApp requires the company Meta connection configured on the server."))
    }
}

@Composable
private fun SettingField(label: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(value,change,label = { Text(label) },modifier = Modifier.fillMaxWidth())
}
