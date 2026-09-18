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
        ORDER BY localSequence ASC
        """,
    )
    suspend fun listByState(state: String): List<PendingCommandEntity>

    @Query(
        """
        SELECT * FROM pending_command
        WHERE state IN ('PENDING', 'RETRYABLE')
        ORDER BY localSequence ASC
        """,
    )
    suspend fun listSyncable(): List<PendingCommandEntity>

    @Query(
        """
        SELECT * FROM pending_command
        ORDER BY localSequence ASC
        """,
    )
    suspend fun listAll(): List<PendingCommandEntity>

    @Query(
        """
        SELECT * FROM pending_command
        WHERE operationId = :operationId
        LIMIT 1
        """,
    )
    suspend fun get(operationId: String): PendingCommandEntity?

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

    @Query(
        """
        UPDATE pending_command
        SET state = :state,
            attemptCount = :attemptCount,
            lastAttemptAtEpochMs = :lastAttemptAtEpochMs,
            lastResultCode = :lastResultCode,
            serverVersion = :serverVersion
        WHERE operationId = :operationId
        """,
    )
    suspend fun updateSyncResult(
        operationId: String,
        state: String,
        attemptCount: Int,
        lastAttemptAtEpochMs: Long?,
        lastResultCode: String?,
        serverVersion: Long?,
    )

    @Query(
        """
        UPDATE pending_command
        SET state = 'BLOCKED_BY_CONFLICT',
            lastResultCode = :resultCode,
            serverVersion = :serverVersion
        WHERE objectType = :objectType
          AND objectId = :objectId
          AND localSequence > :afterSequence
          AND state IN ('PENDING', 'RETRYABLE')
        """,
    )
    suspend fun blockLaterCommandsForObject(
        objectType: String,
        objectId: String,
        afterSequence: Long,
        resultCode: String,
        serverVersion: Long?,
    )

    @Query("SELECT COUNT(*) FROM pending_command")
    suspend fun count(): Int
}
