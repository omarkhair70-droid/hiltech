package com.hiltech.spike.shared.local

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

@Database(
    entities = [PendingCommandEntity::class, OfflineBundleEntity::class],
    version = 2,
)
@ConstructedBy(HiltechLocalDatabaseConstructor::class)
abstract class HiltechLocalDatabase : RoomDatabase() {
    abstract fun pendingCommandDao(): PendingCommandDao
    abstract fun offlineBundleDao(): OfflineBundleDao
}

@Suppress("KotlinNoActualForExpect")
expect object HiltechLocalDatabaseConstructor : RoomDatabaseConstructor<HiltechLocalDatabase> {
    override fun initialize(): HiltechLocalDatabase
}

fun buildHiltechLocalDatabase(
    builder: RoomDatabase.Builder<HiltechLocalDatabase>,
): HiltechLocalDatabase =
    builder
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
