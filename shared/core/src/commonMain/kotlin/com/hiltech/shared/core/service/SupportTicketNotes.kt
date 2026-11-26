package com.hiltech.shared.core.service

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.design.*
import com.hiltech.shared.core.network.HiltechApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

@Composable
internal fun SupportTicketNotes(
    ticket: InternalSupportTicketDto,
    identityId: String,
    installationId: String,
    api: SupportDeskApiClient,
    newOperationId: () -> String,
    onReauthenticate: () -> Unit,
    onBack: () -> Unit,
) {
    val target = SupportNoteTarget(ticket.projectId, ticket.ticketId, identityId)
    val session = api.noteSession
    val jobs = rememberCoroutineScope()
    var notes by remember(api, target) { mutableStateOf(emptyList<InternalSupportNoteDto>()) }
    var loading by remember(api, target) { mutableStateOf(true) }
    var loadError by remember(api, target) { mutableStateOf(false) }
    var readNeedsAuthentication by remember(api, target) { mutableStateOf(false) }
    var refresh by remember(api, target) { mutableIntStateOf(0) }

    LaunchedEffect(api, target, installationId, refresh) {
        notes = emptyList()
        loading = true
        loadError = false
        readNeedsAuthentication = false
        try {
            val source = api.notes(target.projectId, target.ticketId, installationId)
            check(supportNotesMatch(target, source))
            notes = source
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            loadError = true
            readNeedsAuthentication = (failure as? HiltechApiException)?.code in
                setOf("REAUTH_REQUIRED", "UNAUTHENTICATED")
        } finally {
            loading = false
        }
    }

    fun send(intent: SupportNoteIntent) {
        jobs.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val result = api.addInternalNote(intent.target.projectId, intent.target.ticketId, intent.request, installationId)
                session.succeed(intent, result)
                notes = emptyList()
                loading = true
                refresh++
            } catch (cancelled: CancellationException) {
                session.fail(intent, cancelled)
                throw cancelled
            } catch (failure: Exception) {
                session.fail(intent, failure)
                if ((failure as? HiltechApiException)?.httpStatus in setOf(401, 403, 404)) {
                    notes = emptyList()
                    loadError = true
                }
            }
        }
    }

    val pending = session.pending
    val pendingHere = pending?.target == target
    val currentSource = !loading && !loadError
    val editable = currentSource && pending == null && !session.busy
    val note = session.draft(target)
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text(hiltechText("كل البلاغات", "All tickets")) }
        HiltechSectionHeading("SERVICE", ticket.title, hiltechText("البلاغ الداخلي", "Internal ticket"))
        Text(ticket.description, style = MaterialTheme.typography.bodyLarge)
        HiltechStateTag(ticket.internalCaseState, HiltechStateTone.NEUTRAL)
        Text(hiltechText("الضمان والتوقيت التعاقدي لهما سجل مستقل.", "Warranty and contractual timing have separate records."),
            style = MaterialTheme.typography.bodySmall)
        Text(hiltechText("ملاحظات الفريق", "Team notes"), style = MaterialTheme.typography.titleMedium)
        Text(hiltechText("الملاحظات داخلية فقط ولا تُرسل إلى العميل.", "Notes are internal only and are never sent to the client."),
            style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = !loading && !session.busy, onClick = { notes = emptyList(); loading = true; refresh++ }) {
            Text(hiltechText("تحديث الملاحظات", "Refresh notes"))
        }
        if (session.receipt == target) {
            Text(hiltechText("تم حفظ الملاحظة.", "Note recorded."))
        }
        when {
            loading -> Text(hiltechText("جارٍ تحميل الملاحظات…", "Loading notes…"))
            loadError -> Text(hiltechText("تعذر قراءة الملاحظات الحالية. حدّث السجل قبل إرسال أي طلب.",
                "Current notes could not be loaded. Refresh the record before sending a request."),
                color = MaterialTheme.colorScheme.error)
            notes.isEmpty() -> Text(hiltechText("لا توجد ملاحظات بعد.", "No notes yet."))
            else -> notes.forEach { entry ->
                HiltechPanel(Modifier.fillMaxWidth()) {
                    Text(entry.body)
                    Text(entry.createdAt, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (readNeedsAuthentication || (pendingHere && session.requiresReauthentication)) {
            OutlinedButton(enabled = !session.busy, onClick = onReauthenticate) {
                Text(hiltechText("تأكيد تسجيل الدخول", "Confirm sign-in"))
            }
        }
        if (pendingHere) {
            Text(hiltechText("الطلب السابق محفوظ بنفس محتواه ورقمه.", "The previous request retains its original content and identifier."))
            Text(pending!!.request.body)
            if (session.busy) Text(hiltechText("جارٍ حفظ الملاحظة…", "Recording note…"))
            else Button(enabled = currentSource, onClick = { session.retry(target)?.let(::send) }) {
                Text(hiltechText("متابعة نفس الطلب", "Resume the same request"))
            }
        } else if (pending != null) {
            Text(hiltechText("ارجع للبلاغ الذي يحتوي على طلب لم يُحسم قبل تسجيل ملاحظة أخرى.",
                "Return to the ticket with the unresolved request before recording another note."))
        } else {
            if (session.rejectedTarget == target) Text(hiltechText("لم يُقبل الطلب. الملاحظة محفوظة للتعديل.",
                "The request was rejected. Your note remains available to edit."), color = MaterialTheme.colorScheme.error)
            OutlinedTextField(note, { session.edit(target, it) }, enabled = editable,
                modifier = Modifier.fillMaxWidth(), label = { Text(hiltechText("ملاحظة داخلية", "Internal note")) },
                minLines = 3, isError = note.trim().length > 8000)
            Button(enabled = editable && note.trim().length in 1..8000,
                onClick = { session.begin(target, newOperationId)?.let(::send) }) {
                Text(hiltechText("حفظ الملاحظة", "Record note"))
            }
        }
    }
}
