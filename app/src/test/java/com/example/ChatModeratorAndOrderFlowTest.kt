package com.example

import com.example.util.ChatModerator
import org.junit.Assert.*
import org.junit.Test

class ChatModeratorAndOrderFlowTest {

    @Test
    fun testBlockPhoneNumbers() {
        val testCases = listOf(
            "Call me at 0771234567 to arrange pickup",
            "My number is +94 71 234 5678",
            "Call on 011 234 5678",
            "Contact: 0 7 7 1 2 3 4 5 6 7"
        )
        for (tc in testCases) {
            val result = ChatModerator.checkMessage(tc)
            assertTrue("Expected '$tc' to be blocked", result.isBlocked)
            assertTrue("Expected warning message to contain 'Phone numbers'", result.warningMessage?.contains("Phone numbers") == true)
        }
    }

    @Test
    fun testBlockWebLinks() {
        val testCases = listOf(
            "Check out https://external-market.com",
            "Visit www.directdeal.lk for discounts",
            "Go to farmdeal.me to buy directly"
        )
        for (tc in testCases) {
            val result = ChatModerator.checkMessage(tc)
            assertTrue("Expected '$tc' to be blocked", result.isBlocked)
            assertTrue("Expected warning message to contain 'Web links'", result.warningMessage?.contains("Web links") == true)
        }
    }

    @Test
    fun testBlockSocialMedia() {
        val testCases = listOf(
            "Send me details on WhatsApp",
            "Ping me on Telegram for photos",
            "Check my Facebook page",
            "Add me on Viber"
        )
        for (tc in testCases) {
            val result = ChatModerator.checkMessage(tc)
            assertTrue("Expected '$tc' to be blocked", result.isBlocked)
            assertTrue("Expected warning message to contain 'Social media names'", result.warningMessage?.contains("Social media names") == true)
        }
    }

    @Test
    fun testBlockStreetAddresses() {
        val testCases = listOf(
            "My address is No. 45 Temple Road",
            "Bring it to 12/A Galle Road",
            "Located at Kandy Road No 15"
        )
        for (tc in testCases) {
            val result = ChatModerator.checkMessage(tc)
            assertTrue("Expected '$tc' to be blocked", result.isBlocked)
            assertTrue("Expected warning message to contain 'Street addresses'", result.warningMessage?.contains("Street addresses") == true)
        }
    }

    @Test
    fun testAllowLegitimateAgriculturalChat() {
        val allowedMessages = listOf(
            "I have 50 kg of carrots available at Rs. 250 per kg",
            "Can you harvest 100 kg tomorrow at 4 pm?",
            "Is the fresh harvest ready for pickup?",
            "Total cost will be Rs. 15,000 for 60 kg of cabbage",
            "What time will your vehicle arrive at the town?",
            "Everything is packed and ready in boxes."
        )
        for (msg in allowedMessages) {
            val result = ChatModerator.checkMessage(msg)
            assertFalse("Expected legitimate message '$msg' NOT to be blocked", result.isBlocked)
        }
    }

    @Test
    fun testPlatformCommissionCalculation() {
        val cropQuantity = 100.0
        val pricePerKg = 250.0
        val cropSubtotal = cropQuantity * pricePerKg // 25,000.00
        val commissionRate = 0.03 // 3%
        val platformCommission = cropSubtotal * commissionRate // 750.00
        val deliveryFee = 2500.00

        // Buyer pays full total (crop price + delivery fee)
        val buyerTotal = cropSubtotal + deliveryFee // 27,500.00
        assertEquals(27500.00, buyerTotal, 0.001)

        // Commission is on crop price ONLY, not on delivery fee
        assertEquals(750.00, platformCommission, 0.001)

        // Farmer receives: (subtotal - commission) + delivery fee (100% of delivery fee)
        val farmerReceives = (cropSubtotal - platformCommission) + deliveryFee
        assertEquals(26750.00, farmerReceives, 0.001)
    }

    @Test
    fun testPrivacyRuleAddressAndLandmarkVisibility() {
        fun isPrivateDetailsVisible(status: String): Boolean {
            return status.lowercase() in listOf("paid", "ready", "dispatched")
        }

        // Before payment -> hidden
        assertFalse(isPrivateDetailsVisible("requested"))
        assertFalse(isPrivateDetailsVisible("accepted"))

        // After payment until delivered -> visible
        assertTrue(isPrivateDetailsVisible("paid"))
        assertTrue(isPrivateDetailsVisible("ready"))
        assertTrue(isPrivateDetailsVisible("dispatched"))

        // After delivery / completion / cancellation -> hidden
        assertFalse(isPrivateDetailsVisible("delivered"))
        assertFalse(isPrivateDetailsVisible("completed"))
        assertFalse(isPrivateDetailsVisible("cancelled"))
        assertFalse(isPrivateDetailsVisible("expired"))
    }
}
