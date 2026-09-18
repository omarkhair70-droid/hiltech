package com.hiltech.spike.android.e2e

import android.content.Context
import java.io.File

object Spike15State {
    fun write(
        context: Context,
        scenario: String,
        values: Map<String, String>,
    ) {
        val finalFile = File(
            context.filesDir,
            "spike15-$scenario.txt",
        )
        val temporary = File(
            context.filesDir,
            "spike15-$scenario.tmp",
        )

        val body = values.entries.joinToString("\n") {
            it.key + "=" + it.value
        } + "\n"

        temporary.writeText(body)
        if (!temporary.renameTo(finalFile)) {
            finalFile.writeText(temporary.readText())
            temporary.delete()
        }
    }
}
