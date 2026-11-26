package com.hiltech.desktop

import com.hiltech.shared.core.design.HiltechLanguage
import com.hiltech.shared.core.procurement.ServiceSelectedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

/** System file dialogs; source authorization remains in the session-bound API. */
internal class DesktopSourceDocuments(
    private val language: HiltechLanguage,
    private val isSessionCurrent: () -> Boolean,
) {
    private fun text(ar: String, en: String) = language.text(ar, en)

    suspend fun pickDocument(): ServiceSelectedFile? = withContext(Dispatchers.IO) {
        check(isSessionCurrent())
        val chosen = AtomicReference<java.io.File?>()
        SwingUtilities.invokeAndWait {
            val picker = JFileChooser().apply {
                dialogTitle = text("اختيار مستند · حتى 16 ميجابايت", "Choose a document · up to 16 MiB")
                fileSelectionMode = JFileChooser.FILES_ONLY
                fileFilter = FileNameExtensionFilter(text("PDF أو صورة", "PDF or image"), "pdf", "jpg", "jpeg", "png")
                isAcceptAllFileFilterUsed = false
            }
            if (picker.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chosen.set(picker.selectedFile)
        }
        check(isSessionCurrent())
        val file = chosen.get() ?: return@withContext null
        val path = file.toPath()
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            text("اختر ملفًا عاديًا يمكن قراءته.", "Choose a readable regular file.")
        }
        val size = Files.size(path)
        require(size in 1L..16_777_216L) { text("اختر ملفًا غير فارغ، حتى 16 ميجابايت.", "Choose a non-empty file up to 16 MiB.") }
        val contentType = when (file.extension.lowercase()) {
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            else -> error(text("اختر PDF أو صورة PNG أو JPEG.", "Choose a PDF, PNG or JPEG file."))
        }
        // Read with a limit even if another process grows the file after size().
        val bytes = Files.newInputStream(path).use { it.readNBytes(16_777_217) }
        require(bytes.size.toLong() == size && bytes.size <= 16_777_216) {
            text("تغيّر الملف أثناء القراءة. اختره مرة أخرى.", "The file changed while being read. Select it again.")
        }
        check(isSessionCurrent())
        ServiceSelectedFile(contentType, file.name.take(200), bytes, sha256(bytes), Instant.now().toString())
    }

    suspend fun exportDocument(fileName: String, contentType: String, bytes: ByteArray, expectedSha256: String): Boolean =
        withContext(Dispatchers.IO) {
            check(isSessionCurrent())
            require(bytes.isNotEmpty() && sha256(bytes).equals(expectedSha256, ignoreCase = true)) {
                text("تعذّر التحقق من المستند. أعد تحميله.", "The document could not be verified. Download it again.")
            }
            val extension = when (contentType.lowercase()) {
                "application/zip" -> ".zip"
                "application/pdf" -> ".pdf"
                "image/png" -> ".png"
                "image/jpeg" -> ".jpg"
                else -> error(text("نوع المستند غير مدعوم.", "This document type is not supported."))
            }
            val name = fileName.substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("[\\p{Cntrl}<>:\"/\\\\|?*]"), "_").trim().trim('.').take(180)
                .ifBlank { "HILTECH-document$extension" }
            val chosen = AtomicReference<java.io.File?>()
            var replace = false
            SwingUtilities.invokeAndWait {
                val picker = JFileChooser().apply {
                    dialogTitle = text("حفظ المستند", "Save document")
                    selectedFile = java.io.File(if (name.endsWith(extension, true)) name else name + extension)
                    fileFilter = FileNameExtensionFilter(extension.removePrefix(".").uppercase(), extension.removePrefix("."))
                    isAcceptAllFileFilterUsed = false
                }
                if (picker.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                    val selected = picker.selectedFile
                    val target = if (selected.name.endsWith(extension, true)) selected else java.io.File(selected.parentFile, selected.name + extension)
                    if (!target.exists()) chosen.set(target)
                    else if (JOptionPane.showConfirmDialog(null,
                        text("استبدال الملف الموجود؟", "Replace the existing file?"), "HILTECH", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                        replace = true
                        chosen.set(target)
                    }
                }
            }
            check(isSessionCurrent())
            val target = chosen.get()?.toPath() ?: return@withContext false
            require(!Files.isSymbolicLink(target)) { text("اختر مسارًا لملف عادي.", "Choose a regular file destination.") }
            val temporary = Files.createTempFile(target.toAbsolutePath().parent, ".hiltech-export-", ".tmp")
            try {
                Files.write(temporary, bytes)
                require(Files.size(temporary) == bytes.size.toLong())
                check(isSessionCurrent())
                if (replace) Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
                else Files.move(temporary, target)
                true
            } finally {
                Files.deleteIfExists(temporary)
            }
        }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
