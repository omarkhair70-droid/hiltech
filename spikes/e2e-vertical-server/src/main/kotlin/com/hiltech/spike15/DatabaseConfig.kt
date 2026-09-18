package com.hiltech.spike15

import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import javax.sql.DataSource

@Configuration
class DatabaseConfig {
    @Bean
    fun dslContext(dataSource: DataSource): DSLContext =
        DSL.using(
            TransactionAwareDataSourceProxy(dataSource),
            SQLDialect.POSTGRES,
        )
}
