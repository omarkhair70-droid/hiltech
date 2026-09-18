package com.hiltech.spike.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

object WindowsSpikeOps {
    private val dataDir: Path =
        Paths.get(
            System.getenv("LOCALAPPDATA")
                ?: System.getProperty("user.home"),
            "HILTECHSpike",
        )

    fun handle(args: Array<String>): Boolean {
        if (args.isEmpty()) {
            return false
        }

        dataDir.createDirectories()

        when {
            args[0] == "--probe-write" -> {
                val value = args.getOrNull(1) ?: "state-present"
                dataDir.resolve("state.txt").writeText(value)
                println("HILTECH_WINDOWS_STATE_WRITE=$value")
                return true
            }

            args[0] == "--probe-version" -> {
                val version = System.getProperty(
                    "hiltech.app.version",
                    "unknown",
                )
                dataDir.resolve("version.txt").writeText(version)
                println("HILTECH_WINDOWS_VERSION=$version")
                return true
            }

            args[0] == "--register-protocol" -> {
                registerProtocol()
                println("HILTECH_WINDOWS_PROTOCOL_REGISTERED=hiltech")
                return true
            }

            args[0].startsWith("hiltech://") -> {
                dataDir.resolve("last-deep-link.txt")
                    .writeText(args[0])
                println("HILTECH_WINDOWS_DEEP_LINK=" + args[0])
                return true
            }

            else -> return false
        }
    }

    private fun registerProtocol() {
        require(
            System.getProperty("os.name")
                .contains("Windows", ignoreCase = true),
        ) {
            "Protocol registration spike is Windows-only"
        }

        val executable = ProcessHandle.current()
            .info()
            .command()
            .orElseThrow {
                IllegalStateException("Cannot resolve packaged executable")
            }

        val root = "HKCU\\Software\\Classes\\hiltech"

        runReg(
            "ADD",
            root,
            "/ve",
            "/d",
            "URL:HILTECH Protocol",
            "/f",
        )
        runReg(
            "ADD",
            root,
            "/v",
            "URL Protocol",
            "/d",
            "",
            "/f",
        )
        runReg(
            "ADD",
            root + "\\shell\\open\\command",
            "/ve",
            "/d",
            "\"" + executable + "\" \"%1\"",
            "/f",
        )
    }

    private fun runReg(vararg args: String) {
        val process = ProcessBuilder(
            listOf("reg.exe") + args,
        )
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream
            .bufferedReader()
            .readText()

        val exit = process.waitFor()

        check(exit == 0) {
            "reg.exe failed ($exit): $output"
        }
    }
}
