package com.example.data.local.entities

import androidx.room.Entity
import com.example.data.models.ConversationItem

@Entity(
    tableName = "conversations",
    primaryKeys = ["conversationId", "ownerUserId"]
)
data class ConversationEntity(
    val conversationId: String,
    val ownerUserId: String = "",
    val listingId: String = "",
    val cropName: String = "",
    val listingThumbnail: String? = null,
    val pricePerKg: Double = 0.0,
    val buyerId: String = "",
    val farmerId: String = "",
    val counterpartId: String = "",
    val counterpartName: String = "",
    val counterpartAvatar: String? = null,
    val isCounterpartFarmer: Boolean = false,
    val lastMessageId: String? = null,
    val lastMessagePreview: String = "",
    val lastMessageSenderId: String? = null,
    val lastMessageAt: String = "",
    val lastMessageTimestamp: Long = 0L,
    val unreadCount: Int = 0,
    val orderId: String? = null,
    val orderNumber: String? = null,
    val orderStatus: String? = null,
    val status: String = "active", // active, closed, blocked
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toConversationItem(): ConversationItem {
        val resolvedTimestamp = if (lastMessageTimestamp > 0L) {
            lastMessageTimestamp
        } else {
            try {
                java.time.Instant.parse(lastMessageAt).toEpochMilli()
            } catch (_: Exception) {
                0L
            }
        }
        return ConversationItem(
            conversationId = conversationId,
            listingId = listingId,
            cropName = cropName,
            listingThumbnail = listingThumbnail,
            pricePerKg = pricePerKg,
            counterpartId = counterpartId,
            counterpartName = counterpartName,
            counterpartAvatar = counterpartAvatar,
            isCounterpartFarmer = isCounterpartFarmer,
            lastMessageId = lastMessageId,
            lastMessagePreview = lastMessagePreview,
            lastMessageSenderId = lastMessageSenderId,
            lastMessageAt = lastMessageAt,
            lastMessageTimestamp = resolvedTimestamp,
            unreadCount = unreadCount,
            orderId = orderId,
            orderNumber = orderNumber,
            orderStatus = orderStatus,
            status = status,
            ownerUserId = ownerUserId,
            buyerId = buyerId,
            farmerId = farmerId
        )
    }

    companion object {
        fun fromConversationItem(item: ConversationItem, ownerUserId: String = ""): ConversationEntity {
            val resolvedOwner = ownerUserId.ifBlank { item.ownerUserId }
            val resolvedTimestamp = if (item.lastMessageTimestamp > 0L) {
                item.lastMessageTimestamp
            } else {
                try {
                    java.time.Instant.parse(item.lastMessageAt).toEpochMilli()
                } catch (_: Exception) {
                    System.currentTimeMillis()
                }
            }

            return ConversationEntity(
                conversationId = item.conversationId,
                ownerUserId = resolvedOwner,
                listingId = item.listingId,
                cropName = item.cropName,
                listingThumbnail = item.listingThumbnail,
                pricePerKg = item.pricePerKg,
                buyerId = if (item.isCounterpartFarmer) resolvedOwner else item.counterpartId,
                farmerId = if (item.isCounterpartFarmer) item.counterpartId else resolvedOwner,
                counterpartId = item.counterpartId,
                counterpartName = item.counterpartName,
                counterpartAvatar = item.counterpartAvatar,
                isCounterpartFarmer = item.isCounterpartFarmer,
                lastMessageId = item.lastMessageId,
                lastMessagePreview = item.lastMessagePreview,
                lastMessageSenderId = item.lastMessageSenderId,
                lastMessageAt = item.lastMessageAt,
                lastMessageTimestamp = resolvedTimestamp,
                unreadCount = item.unreadCount,
                orderId = item.orderId,
                orderNumber = item.orderNumber,
                orderStatus = item.orderStatus,
                status = item.status,
                updatedAt = System.currentTimeMillis()
            )
        }
    }
}
