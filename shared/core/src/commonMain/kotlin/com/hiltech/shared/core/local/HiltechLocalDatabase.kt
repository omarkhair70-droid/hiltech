package com.hiltech.shared.core.local

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

@Database(
    entities = [
        LocalProjectContextEntity::class,
        LocalSiteContextEntity::class,
        LocalWorkOrderEntity::class,
        LocalPolicyBindingEntity::class,
        LocalRequirementInstanceEntity::class,
        LocalAssignmentEntity::class,
        LocalDocumentRefEntity::class,
        LocalAssetRefEntity::class,
        PendingCommandEntity::class,
        PendingCommandDependencyEntity::class,
        LocalEvidenceEntity::class,
        ConflictRecordEntity::class,
        SyncCursorEntity::class,
    ],
    version = OfflineSchemaContract.ROOM_SCHEMA_VERSION,
    exportSchema = true,
)
@ConstructedBy(HiltechLocalDatabaseConstructor::class)
abstract class HiltechLocalDatabase : RoomDatabase() {
    abstract fun workBundleDao(): WorkBundleDao
    abstract fun pendingCommandDao(): PendingCommandDao
    abstract fun pendingCommandDependencyDao(): PendingCommandDependencyDao
    abstract fun evidenceQueueDao(): EvidenceQueueDao
    abstract fun conflictDao(): ConflictDao
    abstract fun syncCursorDao(): SyncCursorDao
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
