package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entities.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations WHERE ownerUserId = :userId OR ownerUserId = '' OR :userId = '' OR buyerId = :userId OR farmerId = :userId OR counterpartId = :userId ORDER BY lastMessageTimestamp DESC, lastMessageAt DESC, updatedAt DESC")
    fun getConversationsFlow(userId: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY lastMessageTimestamp DESC, lastMessageAt DESC, updatedAt DESC")
    fun getConversationsFlow(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE ownerUserId = :userId OR ownerUserId = '' OR :userId = '' OR buyerId = :userId OR farmerId = :userId OR counterpartId = :userId ORDER BY lastMessageTimestamp DESC, lastMessageAt DESC, updatedAt DESC")
    suspend fun getConversationsList(userId: String): List<ConversationEntity>

    @Query("SELECT * FROM conversations ORDER BY lastMessageTimestamp DESC, lastMessageAt DESC, updatedAt DESC")
    suspend fun getConversationsList(): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE conversationId = :convId AND (ownerUserId = :userId OR :userId = '') LIMIT 1")
    suspend fun getConversation(convId: String, userId: String = ""): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE conversationId = :convId LIMIT 1")
    suspend fun getConversation(convId: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE (buyerId = :userA AND farmerId = :userB) OR (buyerId = :userB AND farmerId = :userA) OR (counterpartId = :userB AND (ownerUserId = :userA OR ownerUserId = '')) OR (counterpartId = :userA AND (ownerUserId = :userB OR ownerUserId = '')) LIMIT 1")
    suspend fun findConversationBetween(userA: String, userB: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE listingId = :listingId AND (ownerUserId = :userId OR :userId = '') LIMIT 1")
    suspend fun getConversationByListing(listingId: String, userId: String = ""): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE listingId = :listingId LIMIT 1")
    suspend fun getConversationByListing(listingId: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(conversation: ConversationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAll(conversations: List<ConversationEntity>)

    @Query("UPDATE conversations SET lastMessageId = :lastMsgId, lastMessagePreview = :preview, lastMessageSenderId = :senderId, lastMessageAt = :time, lastMessageTimestamp = :timestamp, updatedAt = :updatedAt WHERE conversationId = :convId")
    suspend fun updateLastMessage(convId: String, lastMsgId: String?, preview: String, senderId: String?, time: String, timestamp: Long, updatedAt: Long)

    @Query("UPDATE conversations SET lastMessageId = :lastMsgId, lastMessagePreview = :preview, lastMessageSenderId = :senderId, lastMessageAt = :time, updatedAt = :updatedAt WHERE conversationId = :convId")
    suspend fun updateLastMessage(convId: String, lastMsgId: String?, preview: String, senderId: String?, time: String, updatedAt: Long)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE conversationId = :convId AND (ownerUserId = :userId OR :userId = '')")
    suspend fun markRead(convId: String, userId: String = "")

    @Query("UPDATE conversations SET unreadCount = 0 WHERE conversationId = :convId")
    suspend fun markRead(convId: String)

    @Query("DELETE FROM conversations WHERE conversationId = :convId AND (ownerUserId = :userId OR :userId = '')")
    suspend fun deleteConversation(convId: String, userId: String = "")

    @Query("DELETE FROM conversations WHERE conversationId = :convId")
    suspend fun deleteConversation(convId: String)

    @Query("DELETE FROM conversations WHERE ownerUserId = :userId")
    suspend fun clearUserConversations(userId: String)
}
