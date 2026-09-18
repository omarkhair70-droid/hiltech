package com.hiltech.spike.shared.local

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface PendingCommandDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(command: PendingCommandEntity)

    @Query(
        """
        SELECT * FROM pending_command
        WHERE state = :state
        ORDER BY createdAtEpochMs ASC
        """,
    )
    suspend fun listByState(state: String): List<PendingCommandEntity>

    @Query(
        """
        UPDATE pending_command
        SET state = :state,
            attemptCount = :attemptCount,
            lastAttemptAtEpochMs = :lastAttemptAtEpochMs
        WHERE operationId = :operationId
        """,
    )
    suspend fun updateAttempt(
        operationId: String,
        state: String,
        attemptCount: Int,
        lastAttemptAtEpochMs: Long?,
    )

    @Query("SELECT COUNT(*) FROM pending_command")
    suspend fun count(): Int
}
