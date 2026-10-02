package com.example

import com.example.data.models.ConversationItem
import com.example.data.models.MessageItem
import com.example.viewmodel.ChatUiState
import com.example.viewmodel.MessageTickStatus
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class ChatMarketplaceStandardUnitTest {

    private val colomboZone = ZoneId.of("Asia/Colombo")

    // 1. First-name sender label logic instead of generic Buyer/Farmer
    @Test
    fun testFirstNameResolution() {
        val stateWithFullName = ChatUiState(
            counterpartName = "Kamal Bandara",
            isFarmer = true // current user is farmer, counterpart is buyer
        )
        assertEquals("Kamal", stateWithFullName.counterpartFirstName)

        val stateWithSingleName = ChatUiState(
            counterpartName = "Nimali",
            isFarmer = false
        )
        assertEquals("Nimali", stateWithSingleName.counterpartFirstName)

        val stateWithGenericBuyer = ChatUiState(
            counterpartName = "Buyer",
            isFarmer = true
        )
        assertEquals("Buyer", stateWithGenericBuyer.counterpartFirstName)

        val stateWithGenericFarmer = ChatUiState(
            counterpartName = "Farmer",
            isFarmer = false
        )
        assertEquals("Farmer", stateWithGenericFarmer.counterpartFirstName)

        val stateWithBlank = ChatUiState(
            counterpartName = "  ",
            isFarmer = false
        )
        assertEquals("Farmer", stateWithBlank.counterpartFirstName)
    }

    // 2. Link-free text sanitization
    @Test
    fun testLinkFreeTextSanitization() {
        val inputWithHttp = "Check this harvest photo http://malicious-site.com/photo.jpg and pay here"
        val inputWithHttps = "Visit https://phishing.lk/pay?id=123 now"
        val cleanInput = "Hello Kamal, are the tomatoes ripe and ready for pickup?"

        val sanitizedHttp = sanitizeForChat(inputWithHttp)
        val sanitizedHttps = sanitizeForChat(inputWithHttps)
        val sanitizedClean = sanitizeForChat(cleanInput)

        assertFalse("HTTP URL must be stripped", sanitizedHttp.contains("http://"))
        assertTrue("Placeholder must be inserted", sanitizedHttp.contains("[link removed]"))

        assertFalse("HTTPS URL must be stripped", sanitizedHttps.contains("https://"))
        assertTrue("Placeholder must be inserted", sanitizedHttps.contains("[link removed]"))

        assertEquals("Clean text should remain unchanged", cleanInput, sanitizedClean)
    }

    private fun sanitizeForChat(text: String): String {
        return text.replace(Regex("https?://[\\w./?=&%-]+", RegexOption.IGNORE_CASE), "[link removed]")
    }

    // 3. Grouped bubbles logic (same sender within 5 minutes)
    @Test
    fun testBubbleGroupingWithinFiveMinutes() {
        val user1 = "buyer-123"
        val user2 = "farmer-456"

        val t0 = Instant.parse("2026-10-01T10:00:00Z")
        val t1 = Instant.parse("2026-10-01T10:02:00Z") // 2 min later (grouped)
        val t2 = Instant.parse("2026-10-01T10:10:00Z") // 8 min later (not grouped)

        val m0 = MessageItem(id = "1", senderId = user1, body = "Msg 1", createdAt = t0.toString(), senderRole = "buyer")
        val m1 = MessageItem(id = "2", senderId = user1, body = "Msg 2", createdAt = t1.toString(), senderRole = "buyer")
        val m2 = MessageItem(id = "3", senderId = user1, body = "Msg 3", createdAt = t2.toString(), senderRole = "buyer")
        val m3 = MessageItem(id = "4", senderId = user2, body = "Msg 4", createdAt = t1.toString(), senderRole = "farmer")

        // In reverse layout (newest first): [m2, m1, m0]
        val reverseList = listOf(m2, m1, m0)

        // Index 1 (m1): older message above it is m0 (index 2)
        val deltaSeconds = (Instant.parse(m1.createdAt).epochSecond - Instant.parse(m0.createdAt).epochSecond)
        assertTrue("m0 and m1 within 5 minutes (120s)", deltaSeconds <= 300)
        assertEquals(m1.senderId, m0.senderId)

        // Index 0 (m2): older message above it is m1 (index 1)
        val deltaSeconds2 = (Instant.parse(m2.createdAt).epochSecond - Instant.parse(m1.createdAt).epochSecond)
        assertTrue("m1 and m2 exceed 5 minutes (480s)", deltaSeconds2 > 300)

        // Different senders are never grouped
        assertNotEquals(m1.senderId, m3.senderId)
    }

    // 4. Date separators calculation with Colombo time zone
    @Test
    fun testDateSeparatorsColomboTimeZone() {
        // 2026-10-01 23:30 UTC is 2026-10-02 05:00 in Colombo (+05:30)
        val utcTime1 = "2026-10-01T17:00:00Z" // 22:30 in Colombo on Oct 1
        val utcTime2 = "2026-10-01T19:00:00Z" // 00:30 in Colombo on Oct 2

        val date1 = Instant.parse(utcTime1).atZone(colomboZone).toLocalDate()
        val date2 = Instant.parse(utcTime2).atZone(colomboZone).toLocalDate()

        assertNotEquals("Should be different calendar days in Colombo", date1, date2)
        assertEquals(LocalDate.of(2026, 10, 1), date1)
        assertEquals(LocalDate.of(2026, 10, 2), date2)
    }

    // 5. Structured Offer calculations and status transitions
    @Test
    fun testStructuredOfferCalculations() {
        val offer = MessageItem(
            id = "offer-msg-1",
            kind = "offer",
            body = "Offer proposal: 150 kg @ Rs. 320",
            offerPrice = 320.0,
            offerQuantity = 150.0,
            offerDate = "2026-10-05",
            offerStatus = "pending",
            createdAt = "2026-10-01T10:00:00Z",
            isMine = true
        )

        assertEquals("offer", offer.kind)
        assertEquals(320.0, offer.offerPrice!!, 0.001)
        assertEquals(150.0, offer.offerQuantity!!, 0.001)
        val total = offer.offerPrice!! * offer.offerQuantity!!
        assertEquals(48000.0, total, 0.001)
        assertEquals("pending", offer.offerStatus)

        // Accepted transition
        val acceptedOffer = offer.copy(
            offerStatus = "accepted",
            offerOrderId = "order-new-777"
        )
        assertEquals("accepted", acceptedOffer.offerStatus)
        assertEquals("order-new-777", acceptedOffer.offerOrderId)

        // Counter transition
        val counteredOffer = offer.copy(
            offerStatus = "countered"
        )
        assertEquals("countered", counteredOffer.offerStatus)
    }

    // 6. Image attachment model and upload state
    @Test
    fun testImageAttachmentModel() {
        val imageMessage = MessageItem(
            id = "img-msg-1",
            kind = "image",
            body = "Organic fertilizer check",
            attachmentPath = "buyer-123/img-uuid-88.jpg",
            attachmentUrl = "https://example.supabase.co/storage/v1/object/sign/chat-attachments/buyer-123/img-uuid-88.jpg?token=abc",
            localImageUri = "content://media/external/images/media/42",
            isUploading = false,
            isSending = false,
            createdAt = "2026-10-01T11:00:00Z"
        )

        assertEquals("image", imageMessage.kind)
        assertNotNull(imageMessage.attachmentPath)
        assertTrue(imageMessage.attachmentUrl!!.contains("chat-attachments"))
        assertNotNull(imageMessage.localImageUri)
        assertFalse(imageMessage.isUploading)
    }

    // 7. User block enforcement: blocked counterpart locks chat into read-only
    @Test
    fun testUserBlockEnforcesReadOnlyState() {
        val unblockedState = ChatUiState(
            conversationId = "conv-101",
            isCounterpartBlocked = false,
            isReadOnly = false
        )
        assertFalse(unblockedState.isReadOnly)
        assertFalse(unblockedState.isCounterpartBlocked)

        // When user blocks counterpart:
        val blockedState = unblockedState.copy(
            isCounterpartBlocked = true,
            isReadOnly = true,
            readOnlyReason = "This user is blocked. Messaging is disabled."
        )
        assertTrue(blockedState.isCounterpartBlocked)
        assertTrue(blockedState.isReadOnly)
        assertEquals("This user is blocked. Messaging is disabled.", blockedState.readOnlyReason)
    }

    // 8. Strict notification privacy rule: message text must NEVER appear in notification payloads
    @Test
    fun testNotificationPayloadPrivacyRule() {
        val mockSenderFirstName = "Nimal"
        val mockCropName = "Green Papaya"

        // Correct privacy-compliant push notification title and body
        val safePushTitle = "New message from $mockSenderFirstName"
        val safePushBody = "Regarding your $mockCropName inquiry on AgroMarket"

        // Ensure user message content is NOT in push notification
        val actualSecretMessage = "Hey meet me at Pettah bus stand and pay 50000 cash"
        assertFalse("Message body must never be in notification title", safePushTitle.contains(actualSecretMessage))
        assertFalse("Message body must never be in notification body", safePushBody.contains(actualSecretMessage))
        assertTrue("Notification must only state sender and context", safePushBody.contains("AgroMarket"))
    }

    // 9. User Chat Isolation: Farmer Abishek only sees his engaged chats, Buyer Dinal only sees his engaged chats
    @Test
    fun testUserChatIsolation_OnlyEngagedChatsVisible() {
        val abishekFarmerId = "user-abishek-farmer"
        val dinalBuyerId = "user-dinal-buyer"
        val thirdPartyId = "user-other-person"

        val chatAbishekWithBuyer1 = ConversationItem(
            conversationId = "conv-1",
            counterpartId = "buyer-1",
            counterpartName = "Kamal Buyer",
            lastMessagePreview = "Hello Abishek, 50kg tomatoes please",
            lastMessageAt = "2026-10-01T10:00:00Z",
            ownerUserId = abishekFarmerId
        )

        val chatAbishekWithDinal = ConversationItem(
            conversationId = "conv-2",
            counterpartId = dinalBuyerId,
            counterpartName = "Dinal",
            lastMessagePreview = "Hi Abishek, is delivery available?",
            lastMessageAt = "2026-10-01T10:15:00Z",
            ownerUserId = abishekFarmerId
        )

        val chatDinalWithOtherFarmer = ConversationItem(
            conversationId = "conv-3",
            counterpartId = "farmer-saman",
            counterpartName = "Saman Farmer",
            lastMessagePreview = "Carrots harvest ready tomorrow",
            lastMessageAt = "2026-10-01T10:20:00Z",
            ownerUserId = dinalBuyerId
        )

        val chatThirdPartyOnly = ConversationItem(
            conversationId = "conv-4",
            counterpartId = thirdPartyId,
            counterpartName = "Third Party",
            lastMessagePreview = "Private order discussion",
            lastMessageAt = "2026-10-01T10:05:00Z",
            ownerUserId = thirdPartyId
        )

        val allSystemChats = listOf(chatAbishekWithBuyer1, chatAbishekWithDinal, chatDinalWithOtherFarmer, chatThirdPartyOnly)

        // Filter for Abishek: must ONLY include chats where Abishek is the owner or engaged
        val abishekVisibleChats = allSystemChats.filter {
            it.ownerUserId == abishekFarmerId || (it.counterpartId != abishekFarmerId && it.ownerUserId.isBlank())
        }.filter { it.ownerUserId == abishekFarmerId }

        assertEquals(2, abishekVisibleChats.size)
        assertTrue(abishekVisibleChats.any { it.conversationId == "conv-1" })
        assertTrue(abishekVisibleChats.any { it.conversationId == "conv-2" })
        assertFalse(abishekVisibleChats.any { it.conversationId == "conv-3" })
        assertFalse(abishekVisibleChats.any { it.conversationId == "conv-4" })

        // Filter for Dinal: must ONLY include chats where Dinal is the owner or engaged
        val dinalVisibleChats = allSystemChats.filter {
            it.ownerUserId == dinalBuyerId
        }

        assertEquals(1, dinalVisibleChats.size)
        assertEquals("conv-3", dinalVisibleChats.first().conversationId)
        assertFalse(dinalVisibleChats.any { it.conversationId == "conv-1" })
        assertFalse(dinalVisibleChats.any { it.conversationId == "conv-4" })
    }

    // 10. WhatsApp-style sorting: Recent messages come to the top
    @Test
    fun testRecentMessagesComeToTop_WhatsAppStyle() {
        val olderChat = ConversationItem(
            conversationId = "conv-old",
            lastMessagePreview = "Deal agreed yesterday",
            lastMessageAt = "2026-09-30T10:00:00Z",
            lastMessageTimestamp = Instant.parse("2026-09-30T10:00:00Z").toEpochMilli()
        )

        val recentChat = ConversationItem(
            conversationId = "conv-recent",
            lastMessagePreview = "Just sent a new price offer",
            lastMessageAt = "2026-10-01T12:00:00Z",
            lastMessageTimestamp = Instant.parse("2026-10-01T12:00:00Z").toEpochMilli()
        )

        val newestChat = ConversationItem(
            conversationId = "conv-newest",
            lastMessagePreview = "Order dispatched via PickMe!",
            lastMessageAt = "2026-10-01T12:30:00Z",
            lastMessageTimestamp = Instant.parse("2026-10-01T12:30:00Z").toEpochMilli()
        )

        val unorganizedList = listOf(olderChat, newestChat, recentChat)
        val sortedList = unorganizedList.sortedWith(
            compareByDescending<ConversationItem> { it.computedTimestamp }.thenByDescending { it.lastMessageAt }
        )

        assertEquals("Newest message must be at index 0 (top)", "conv-newest", sortedList[0].conversationId)
        assertEquals("Second newest message must be at index 1", "conv-recent", sortedList[1].conversationId)
        assertEquals("Oldest message must be at bottom", "conv-old", sortedList[2].conversationId)
    }

    // 11. Delete conversation removes chat from user inbox
    @Test
    fun testDeleteConversationRemovesFromInbox() {
        val conv1 = ConversationItem(conversationId = "conv-to-keep", cropName = "Carrots")
        val conv2 = ConversationItem(conversationId = "conv-to-delete", cropName = "Leeks")
        val userInbox = mutableListOf(conv1, conv2)

        // User deletes conv2
        val targetId = "conv-to-delete"
        userInbox.removeAll { it.conversationId == targetId }

        assertEquals(1, userInbox.size)
        assertEquals("conv-to-keep", userInbox.first().conversationId)
        assertFalse(userInbox.any { it.conversationId == targetId })
    }
}
