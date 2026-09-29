package com.guardianlink.child.data

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "offline_items",
    primaryKeys = ["type", "itemId"],
    indices = [Index(value = ["status", "nextAttemptAt"])]
)
data class OfflineQueueEntity(
    val type: String,
    val itemId: String,
    val payload: String,
    val createdAt: Long,
    val retryCount: Int = 0,
    val lastAttempt: Long? = null,
    val nextAttemptAt: Long = createdAt,
    val status: String = "PENDING",
    val priority: Int = 0
)