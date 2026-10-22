package com.hiltech.spike.people

import org.springframework.stereotype.Service

@Service
class PeopleService {
    fun employeeDisplayName(employeeId: String): String = "Employee " + employeeId
}
