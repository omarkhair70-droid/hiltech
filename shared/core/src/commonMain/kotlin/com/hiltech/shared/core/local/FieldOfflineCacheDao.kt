package com.hiltech.shared.core.local

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface FieldOfflineCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertToday(row: FieldTodayCacheEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBundle(row: FieldJobBundleCacheEntity)

    @Query("SELECT * FROM field_today_cache WHERE identity_id = :identityId LIMIT 1")
    suspend fun today(identityId: String): FieldTodayCacheEntity?

    @Query("SELECT * FROM field_job_bundle_cache WHERE identity_id = :identityId AND work_order_id = :workOrderId LIMIT 1")
    suspend fun bundle(identityId: String, workOrderId: String): FieldJobBundleCacheEntity?

    @Query("DELETE FROM field_today_cache WHERE identity_id = :identityId")
    suspend fun clearToday(identityId: String)

    @Query("DELETE FROM field_job_bundle_cache WHERE identity_id = :identityId")
    suspend fun clearBundles(identityId: String)
}
