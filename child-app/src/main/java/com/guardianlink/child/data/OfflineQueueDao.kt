package com.guardianlink.child.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface OfflineQueueDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(item: OfflineQueueEntity): Long

    @Query("SELECT * FROM offline_items WHERE status = 'PENDING' AND nextAttemptAt <= :nowMs ORDER BY priority DESC, createdAt ASC LIMIT :limit")
    suspend fun pending(nowMs: Long, limit: Int = 50): List<OfflineQueueEntity>

    @Update
    suspend fun update(item: OfflineQueueEntity)

    @Query("DELETE FROM offline_items WHERE type = :type AND itemId = :itemId")
    suspend fun delete(type: String, itemId: String)

    @Query("SELECT COUNT(*) FROM offline_items WHERE status IN ('PENDING', 'IN_FLIGHT')")
    suspend fun pendingCount(): Int

    @Query("UPDATE offline_items SET status = 'PENDING' WHERE status = 'IN_FLIGHT'")
    suspend fun resetInFlight()
}