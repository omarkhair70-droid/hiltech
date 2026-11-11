package com.hiltech.server

import com.hiltech.server.warehouse.provisionWarehouseManager
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.modulith.Modulith

@Modulith
@SpringBootApplication
class HiltechServerApplication

fun main(args: Array<String>) {
    if (args.firstOrNull() == "provision-warehouse-manager") {
        provisionWarehouseManager(args.drop(1))
        return
    }
    runApplication<HiltechServerApplication>(*args)
}
