package com.hiltech.spike.shared.local

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface OfflineBundleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bundle: OfflineBundleEntity)

    @Query(
        """
        SELECT * FROM offline_bundle
        WHERE workOrderId = :workOrderId
        LIMIT 1
        """,
    )
    suspend fun get(workOrderId: String): OfflineBundleEntity?

    @Query("DELETE FROM offline_bundle")
    suspend fun clear()
}
