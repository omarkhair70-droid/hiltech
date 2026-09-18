package com.hiltech.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.modulith.Modulith

@Modulith
@SpringBootApplication
class HiltechServerApplication

fun main(args: Array<String>) {
    runApplication<HiltechServerApplication>(*args)
}
