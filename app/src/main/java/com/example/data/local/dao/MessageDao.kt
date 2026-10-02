package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entities.FailureReason
import com.example.data.local.entities.MessageEntity
import com.example.data.local.entities.OutboxStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE conversationId = :convId ORDER BY createdAtTimestamp DESC, createdAt DESC, id DESC")
    fun getMessagesFlow(convId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :convId ORDER BY createdAtTimestamp DESC, createdAt DESC, id DESC")
    suspend fun getMessagesList(convId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE status = 'QUEUED' ORDER BY createdAtTimestamp ASC")
    suspend fun getQueuedMessages(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE status IN ('QUEUED', 'SENDING') ORDER BY createdAtTimestamp ASC")
    suspend fun getPendingOutboxMessages(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id OR (clientNonce = :clientNonce AND clientNonce != '') LIMIT 1")
    suspend fun findMessage(id: String, clientNonce: String?): MessageEntity?

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun findMessageById(id: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAll(messages: List<MessageEntity>)

    @Query("UPDATE messages SET status = :status WHERE id = :id OR (clientNonce = :clientNonce AND clientNonce != '')")
    suspend fun updateStatus(id: String, clientNonce: String?, status: OutboxStatus)

    @Query("UPDATE messages SET status = 'FAILED', failureReason = :reason, failureMessage = :message, failureCode = :code WHERE id = :id OR (clientNonce = :clientNonce AND clientNonce != '')")
    suspend fun markFailed(id: String, clientNonce: String?, reason: FailureReason, message: String?, code: Int?)

    @Query("UPDATE messages SET status = 'QUEUED', failureReason = 'NONE', failureMessage = null WHERE id = :id OR (clientNonce = :clientNonce AND clientNonce != '')")
    suspend fun resetToQueued(id: String, clientNonce: String?)

    @Query("UPDATE messages SET status = 'QUEUED' WHERE status = 'SENDING'")
    suspend fun resetSendingToQueued(): Int

    @Query("DELETE FROM messages WHERE id = :id OR (clientNonce = :clientNonce AND clientNonce != '')")
    suspend fun deleteMessage(id: String, clientNonce: String?)

    @Query("DELETE FROM messages WHERE conversationId = :convId")
    suspend fun deleteMessagesForConversation(convId: String)

    @Query("SELECT * FROM messages WHERE conversationId = :convId ORDER BY createdAtTimestamp DESC, createdAt DESC, id DESC LIMIT 1")
    suspend fun getLatestMessage(convId: String): MessageEntity?

    @Query("SELECT MAX(createdAtTimestamp) FROM messages WHERE conversationId = :convId")
    suspend fun getLatestMessageTimestamp(convId: String): Long?

    @Query("""
        SELECT COUNT(*) FROM messages 
        WHERE conversationId = :convId 
          AND senderId IS NOT NULL 
          AND senderId != :currentUserId 
          AND readAt IS NULL
    """)
    suspend fun countUnreadMessages(convId: String, currentUserId: String): Int

    @Query("""
        UPDATE messages
        SET readAt = :readAtTimestamp
        WHERE conversationId = :convId
          AND senderId IS NOT NULL
          AND senderId != :currentUserId
          AND readAt IS NULL
    """)
    suspend fun markMessagesRead(convId: String, currentUserId: String, readAtTimestamp: String)

    @Query("""
        SELECT COUNT(*) FROM messages
        WHERE senderId IS NOT NULL
          AND senderId != :currentUserId
          AND readAt IS NULL
    """)
    fun getTotalUnreadCountFlow(currentUserId: String): Flow<Int>
}
