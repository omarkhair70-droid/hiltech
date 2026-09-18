package com.hiltech.spike.android.sync

import android.content.Context
import java.io.File

object SyncProbeState {
    fun write(
        context: Context,
        probeId: String,
        state: String,
        attempts: Int,
    ) {
        val finalFile = File(
            context.filesDir,
            "sync-$probeId.txt",
        )
        val temporary = File(
            context.filesDir,
            "sync-$probeId.tmp",
        )

        temporary.writeText(
            "state=$state\nattempts=$attempts\n",
        )

        if (!temporary.renameTo(finalFile)) {
            finalFile.writeText(temporary.readText())
            temporary.delete()
        }
    }
}
