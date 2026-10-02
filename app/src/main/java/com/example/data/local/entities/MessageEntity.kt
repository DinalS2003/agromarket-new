package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.data.models.MessageItem

enum class OutboxStatus {
    QUEUED,
    SENDING,
    SENT,
    FAILED
}

enum class FailureReason {
    NONE,
    BLOCKED,
    CLOSED,
    RATE_LIMIT,
    SUSPENDED,
    NETWORK_ERROR,
    SERVER_ERROR
}

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["conversationId", "createdAtTimestamp"]),
        Index(value = ["clientNonce"])
    ]
)
data class MessageEntity(
    @PrimaryKey
    val id: String,
    val conversationId: String,
    val orderId: String? = null,
    val senderId: String? = null, // null = system message
    val kind: String = if (senderId == null) "system" else "user",
    val body: String,
    val clientNonce: String = id,
    val createdAt: String,
    val createdAtTimestamp: Long = System.currentTimeMillis(),
    val isMine: Boolean = true,
    val senderRole: String = "buyer",
    val status: OutboxStatus = OutboxStatus.SENT,
    val failureReason: FailureReason = FailureReason.NONE,
    val failureMessage: String? = null,
    val failureCode: Int? = null,
    val senderName: String? = null,
    val readAt: String? = null,
    val isSending: Boolean = false,
    val sendFailed: Boolean = false
) {
    fun toMessageItem(): MessageItem {
        return MessageItem(
            id = id,
            conversationId = conversationId,
            orderId = orderId,
            senderId = senderId,
            kind = if (senderId == null) "system" else kind,
            body = body,
            clientNonce = clientNonce,
            createdAt = createdAt,
            readAt = readAt,
            isMine = isMine,
            senderRole = senderRole,
            senderName = senderName,
            isSending = isSending,
            sendFailed = sendFailed
        )
    }

    companion object {
        fun fromMessageItem(item: MessageItem, currentUserId: String = ""): MessageEntity {
            val ts = try {
                java.time.Instant.parse(item.createdAt).toEpochMilli()
            } catch (_: Exception) {
                System.currentTimeMillis()
            }
            val mine = if (item.isMine != null) {
                item.isMine
            } else if (currentUserId.isNotBlank() && item.senderId != null) {
                item.senderId == currentUserId
            } else {
                false
            }
            return MessageEntity(
                id = item.id,
                conversationId = item.conversationId ?: "",
                orderId = item.orderId,
                senderId = item.senderId,
                kind = if (item.senderId == null) "system" else item.kind,
                body = item.body,
                clientNonce = item.clientNonce ?: item.id,
                createdAt = item.createdAt,
                createdAtTimestamp = ts,
                isMine = mine,
                senderRole = item.senderRole ?: "buyer",
                status = if (item.isSending) OutboxStatus.SENDING else if (item.sendFailed) OutboxStatus.FAILED else OutboxStatus.SENT,
                readAt = item.readAt,
                isSending = item.isSending,
                sendFailed = item.sendFailed
            )
        }
    }
}
