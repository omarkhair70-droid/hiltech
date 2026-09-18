package com.hiltech.spike.desktop

import com.hiltech.spike.shared.network.HiltechApiClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

object Spike15DesktopOps {
    fun handle(args: Array<String>): Boolean {
        if (args.isEmpty() || !args[0].startsWith("--spike15-")) {
            return false
        }

        require(args.size >= 3) {
            "Expected command BASE_URL TOKEN [extra]"
        }

        val command = args[0]
        val baseUrl = args[1]
        val token = args[2]

        runBlocking {
            HiltechApiClient(
                baseUrl = baseUrl,
                tokenProvider = { token },
            ).use { api ->
                when (command) {
                    "--spike15-pm-create" -> {
                        val created = api.createWorkOrder(
                            operationId = "spike15-pm-create",
                            correlationId = "spike15-pm-create",
                            payloadJson = """{"projectId":"project-a","siteId":"site-a"}""",
                        )
                        check(created.resultCode == "APPLIED")
                        check(created.serverVersion == 1L)

                        val assigned = api.assignWork(
                            operationId = "spike15-pm-assign",
                            correlationId = "spike15-pm-assign",
                            workOrderId = "wo-42",
                            baseVersion = 1,
                            assignee = "tech1",
                        )
                        check(assigned.resultCode == "APPLIED")
                        check(assigned.serverVersion == 2L)
                        check(assigned.state == "ASSIGNED")

                        println(
                            "HILTECH_SPIKE15_DESKTOP_CREATE_PASS " +
                                "work=wo-42 version=2 state=ASSIGNED",
                        )
                    }

                    "--spike15-reset" -> {
                        api.reset("spike15-reset")
                        println("HILTECH_SPIKE15_RESET_PASS")
                    }

                    "--spike15-pm-touch" -> {
                        val version = args.getOrNull(3)?.toLong()
                            ?: error("Expected base version")
                        val result = api.touchWork(
                            operationId = "spike15-pm-touch",
                            correlationId = "spike15-pm-touch",
                            workOrderId = "wo-42",
                            baseVersion = version,
                        )
                        check(result.resultCode == "APPLIED")
                        println(
                            "HILTECH_SPIKE15_TOUCH_PASS " +
                                "version=${result.serverVersion}",
                        )
                    }

                    "--spike15-supervisor-accept" -> {
                        val version = args.getOrNull(3)?.toLong()
                            ?: error("Expected submitted version")
                        val result = api.acceptWork(
                            operationId = "spike15-supervisor-accept",
                            correlationId = "spike15-supervisor-accept",
                            workOrderId = "wo-42",
                            baseVersion = version,
                        )
                        check(result.resultCode == "APPLIED")
                        check(result.state == "ACCEPTED")
                        println(
                            "HILTECH_SPIKE15_SUPERVISOR_PASS " +
                                "version=${result.serverVersion} state=ACCEPTED",
                        )
                    }

                    "--spike15-pm-read" -> {
                        val expected = args.getOrNull(3) ?: "ACCEPTED"
                        val work = api.getWorkOrder(
                            workOrderId = "wo-42",
                            correlationId = "spike15-pm-read",
                        )
                        check(work.state == expected) {
                            "Expected $expected, got ${work.state}"
                        }
                        println(
                            "HILTECH_SPIKE15_DESKTOP_READ_PASS " +
                                "version=${work.version} state=${work.state}",
                        )
                    }

                    "--spike15-pm-audit" -> {
                        var lastProjectionCount = 0
                        var eventCount = 0

                        repeat(40) {
                            val audit = api.audit("spike15-pm-audit")
                            lastProjectionCount = audit.projectionCount
                            eventCount = audit.events.size
                            if (lastProjectionCount >= 2) {
                                val actors = audit.events.map { it.actor }.toSet()
                                val commands = audit.events.map { it.commandType }.toSet()
                                check("pm1" in actors)
                                check("tech1" in actors)
                                check("supervisor1" in actors)
                                check("CreateWorkOrder" in commands)
                                check("AssignWork" in commands)
                                check("StartWork" in commands)
                                check("SubmitWorkCompletion" in commands)
                                check("AcceptWork" in commands)
                                check(
                                    audit.events.all {
                                        it.traceparent.startsWith("00-") &&
                                            it.correlationId.isNotBlank()
                                    },
                                )

                                println(
                                    "HILTECH_SPIKE15_AUDIT_PASS " +
                                        "events=$eventCount projections=$lastProjectionCount",
                                )
                                return@runBlocking
                            }
                            delay(250)
                        }

                        error(
                            "Timed out waiting for Modulith projections; " +
                                "events=$eventCount projections=$lastProjectionCount",
                        )
                    }

                    else -> error("Unknown SPIKE-15 desktop command: $command")
                }
            }
        }

        return true
    }
}
