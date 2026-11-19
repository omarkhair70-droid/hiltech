package com.hiltech.server

import com.hiltech.server.warehouse.provisionWarehouseManager
import com.hiltech.server.warehouse.provisionInventoryAdjustmentApprover
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
    if (args.firstOrNull() == "provision-inventory-adjustment-approver") {
        provisionInventoryAdjustmentApprover(args.drop(1))
        return
    }
    runApplication<HiltechServerApplication>(*args)
}
