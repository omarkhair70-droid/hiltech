package com.hiltech.spike.android.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import java.io.File
import java.security.MessageDigest

class CameraEvidenceSpikeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when (intent.getStringExtra(EXTRA_MODE) ?: MODE_CAPTURE) {
            MODE_PROHIBITED -> {
                writeState(
                    state = "CAMERA_PROHIBITED",
                    details = listOf(
                        "fallback=MANUAL_ASSET_ID_ALLOWED",
                        "manualAssetId=ASSET-FLUKE-03",
                    ),
                )
                finish()
            }

            MODE_CAPTURE -> {
                if (
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.CAMERA,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    writeState(
                        state = "PERMISSION_DENIED",
                        details = listOf(
                            "fallback=MANUAL_ASSET_ID_ALLOWED",
                        ),
                    )
                    finish()
                    return
                }

                captureEvidence()
            }

            else -> {
                writeState(
                    state = "INVALID_MODE",
                    details = emptyList(),
                )
                finish()
            }
        }
    }

    private fun captureEvidence() {
        val providerFuture = ProcessCameraProvider.getInstance(this)

        providerFuture.addListener(
            {
                try {
                    val provider = providerFuture.get()
                    val imageCapture = ImageCapture.Builder()
                        .setCaptureMode(
                            ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY,
                        )
                        .build()

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        imageCapture,
                    )

                    val evidenceDir = File(
                        filesDir,
                        "evidence",
                    ).apply {
                        mkdirs()
                    }

                    val file = File(
                        evidenceDir,
                        "evidence-001.jpg",
                    )
                    if (file.exists()) {
                        file.delete()
                    }

                    val options = ImageCapture.OutputFileOptions.Builder(
                        file,
                    ).build()

                    imageCapture.takePicture(
                        options,
                        ContextCompat.getMainExecutor(this),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(
                                outputFileResults: ImageCapture.OutputFileResults,
                            ) {
                                val bytes = file.readBytes()
                                val sha = MessageDigest
                                    .getInstance("SHA-256")
                                    .digest(bytes)
                                    .joinToString("") {
                                        "%02x".format(it)
                                    }

                                File(
                                    evidenceDir,
                                    "evidence-001.meta",
                                ).writeText(
                                    buildString {
                                        appendLine("evidenceId=evidence-001")
                                        appendLine("state=LOCAL_READY")
                                        appendLine("uploadState=PENDING_UPLOAD")
                                        appendLine("sizeBytes=" + bytes.size)
                                        appendLine("sha256=" + sha)
                                        appendLine("localPath=" + file.absolutePath)
                                    },
                                )

                                writeState(
                                    state = "CAPTURED",
                                    details = listOf(
                                        "sizeBytes=" + bytes.size,
                                        "sha256=" + sha,
                                        "uploadState=PENDING_UPLOAD",
                                    ),
                                )

                                provider.unbindAll()
                                finish()
                            }

                            override fun onError(
                                exception: ImageCaptureException,
                            ) {
                                writeState(
                                    state = "CAPTURE_FAILED",
                                    details = listOf(
                                        "errorCode=" + exception.imageCaptureError,
                                        "message=" +
                                            exception.message
                                                .orEmpty()
                                                .take(160),
                                    ),
                                )
                                provider.unbindAll()
                                finish()
                            }
                        },
                    )
                } catch (throwable: Throwable) {
                    writeState(
                        state = "CAMERA_BIND_FAILED",
                        details = listOf(
                            "error=" +
                                throwable::class.java.simpleName,
                        ),
                    )
                    finish()
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun writeState(
        state: String,
        details: List<String>,
    ) {
        val target = File(
            filesDir,
            "camera-spike-state.txt",
        )
        val temp = File(
            filesDir,
            "camera-spike-state.tmp",
        )

        temp.writeText(
            buildString {
                appendLine("state=$state")
                details.forEach(::appendLine)
            },
        )

        if (!temp.renameTo(target)) {
            target.writeText(temp.readText())
            temp.delete()
        }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_CAPTURE = "capture"
        const val MODE_PROHIBITED = "prohibited"
    }
}
