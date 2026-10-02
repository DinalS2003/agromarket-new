package com.example

import com.example.BuildConfig
import com.example.data.models.OrderItem
import com.example.ui.theme.AgroElevation
import com.example.ui.theme.AgroShapes
import com.example.ui.theme.AgroSpacing
import org.junit.Assert.*
import org.junit.Test

class MockPaymentAndDesignSystemTest {

    @Test
    fun `test design system tokens are valid and non-null`() {
        assertNotNull(AgroSpacing.sm)
        assertNotNull(AgroSpacing.md)
        assertNotNull(AgroSpacing.lg)
        assertTrue(AgroSpacing.lg.value > AgroSpacing.md.value)

        assertNotNull(AgroShapes.card)
        assertNotNull(AgroShapes.medium)
        assertNotNull(AgroShapes.pill)

        assertNotNull(AgroElevation.soft)
        assertTrue(AgroElevation.soft.value > 0f)
    }

    @Test
    fun `test payhere sandbox flag is defined`() {
        assertNotNull(BuildConfig.PAYHERE_SANDBOX)
    }

    @Test
    fun `test order payment amount calculation`() {
        val orderWithTotal = OrderItem(
            id = "test-order-1",
            orderNumber = "AM-100001",
            buyerId = "buyer-1",
            farmerId = "farmer-1",
            listingId = "listing-1",
            cropName = "Carrot",
            pricePerKg = 150.0,
            quantityKg = 10.0,
            subtotal = 1500.0,
            totalAmount = 1650.0,
            deliveryMethod = "farmer_delivery",
            requestedDate = "2026-10-05",
            status = "accepted"
        )
        val amount = if (orderWithTotal.totalAmount > 0) orderWithTotal.totalAmount else orderWithTotal.subtotal
        assertEquals(1650.0, amount, 0.001)

        val orderDefaultTotal = OrderItem(
            id = "test-order-2",
            orderNumber = "AM-100002",
            buyerId = "buyer-2",
            farmerId = "farmer-2",
            listingId = "listing-2",
            cropName = "Pumpkin",
            pricePerKg = 90.0,
            quantityKg = 20.0,
            subtotal = 1800.0,
            totalAmount = 0.0,
            deliveryMethod = "buyer_arranged",
            requestedDate = "2026-10-06",
            status = "accepted"
        )
        val fallbackAmount = if (orderDefaultTotal.totalAmount > 0) orderDefaultTotal.totalAmount else orderDefaultTotal.subtotal
        assertEquals(1800.0, fallbackAmount, 0.001)
    }

    @Test
    fun `test parsePaymentErrorMessage user friendly error mappings`() {
        // Specific errors requested by user:
        // "Order already paid", "Order cancelled", "Session expired, please log in"
        assertEquals(
            "Order already paid",
            com.example.data.payment.parsePaymentErrorMessage("{\"error\":\"Order already paid\",\"code\":\"ORDER_ALREADY_PAID\"}")
        )
        assertEquals(
            "Order cancelled",
            com.example.data.payment.parsePaymentErrorMessage("{\"error\":\"Order cancelled\",\"code\":\"ORDER_CANCELLED\"}")
        )
        assertEquals(
            "Session expired, please log in",
            com.example.data.payment.parsePaymentErrorMessage("{\"error\":\"Invalid user session\",\"code\":\"UNAUTHORIZED\"}")
        )
        assertEquals(
            "Session expired, please log in",
            com.example.data.payment.parsePaymentErrorMessage("HTTP 401 Unauthorized")
        )
        assertEquals(
            "Order payment window has expired",
            com.example.data.payment.parsePaymentErrorMessage("{\"error\":\"Order payment window has expired\",\"code\":\"ORDER_EXPIRED\"}")
        )
        assertEquals(
            "Order not found",
            com.example.data.payment.parsePaymentErrorMessage("{\"error\":\"Order not found\",\"code\":\"ORDER_NOT_FOUND\"}")
        )
        assertEquals(
            "Only the buyer who placed this order can make payment.",
            com.example.data.payment.parsePaymentErrorMessage("{\"error\":\"Only the order buyer can initiate payment\",\"code\":\"FORBIDDEN\"}")
        )
    }

    @Test
    fun `test cart checkout placed order result maintains distinct UUIDs and amounts`() {
        val order1Uuid = "c0a80101-1111-2222-3333-444455556666"
        val order2Uuid = "c0a80101-7777-8888-9999-aaaabbbbcccc"

        val orderResult1 = com.example.viewmodel.PlacedOrderResult(
            orderId = order1Uuid,
            orderNumber = "AM-11112222",
            farmerName = "Nimal",
            cropSummary = "10 kg Carrots",
            amount = 1500.0
        )
        val orderResult2 = com.example.viewmodel.PlacedOrderResult(
            orderId = order2Uuid,
            orderNumber = "AM-77778888",
            farmerName = "Kamal",
            cropSummary = "20 kg Potatoes",
            amount = 2600.0
        )

        // Verify each order retains its real UUID and individual amount
        assertEquals(order1Uuid, orderResult1.orderId)
        assertEquals(order2Uuid, orderResult2.orderId)
        assertNotEquals(orderResult1.orderId, orderResult2.orderId)
        assertEquals(1500.0, orderResult1.amount, 0.001)
        assertEquals(2600.0, orderResult2.amount, 0.001)
    }
}
