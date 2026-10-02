package com.example.data.payment

import com.example.BuildConfig
import com.example.data.local.SessionManager
import com.example.data.models.PayHerePaymentRequest
import com.example.data.repository.AgroMarketRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

data class PayHereFormData(
    val merchantId: String,
    val returnUrl: String = PayHerePaymentService.RETURN_URL,
    val cancelUrl: String = PayHerePaymentService.CANCEL_URL,
    val notifyUrl: String,
    val orderId: String,
    val items: String,
    val currency: String = "LKR",
    val amount: String,
    val firstName: String,
    val lastName: String = "Customer",
    val email: String,
    val phone: String,
    val address: String = "Sri Lanka",
    val city: String = "Colombo",
    val country: String = "Sri Lanka",
    val hash: String
)

class PayHerePaymentService(
    private val repository: AgroMarketRepository,
    private val sessionManager: SessionManager
) {

    companion object {
        const val CHECKOUT_URL = "https://sandbox.payhere.lk/pay/checkout"
        // Single source of truth for the PayHere domain entry (must match the registered
        // domain for merchant 1238313 in PayHere Dashboard -> Integrations -> Domains).
        const val BASE_HOST = "https://agromarket.com"
        const val BASE_DOMAIN = "$BASE_HOST/"
        const val RETURN_URL = "$BASE_HOST/payment/return"
        const val CANCEL_URL = "$BASE_HOST/payment/cancel"
    }

    suspend fun preparePaymentForm(
        orderId: String,
        fallbackAmount: Double,
        fallbackCropName: String
    ): PayHereFormData = withContext(Dispatchers.IO) {
        val buyerName = sessionManager.getUserName()?.ifBlank { "Customer" } ?: "Customer"
        val buyerPhone = sessionManager.getUserPhone()?.ifBlank { "0771234567" } ?: "0771234567"
        val buyerEmail = sessionManager.getUserEmail()?.ifBlank { "buyer@agromarket.lk" } ?: "buyer@agromarket.lk"

        // Server-side Edge Function calculates amount, order id & signature hash
        val serverRequestResult = repository.initiatePayHerePayment(orderId)
        val serverRequest: PayHerePaymentRequest = serverRequestResult.getOrNull()
            ?: run {
                val raw = serverRequestResult.exceptionOrNull()?.message ?: "Could not fetch payment hash from server"
                throw Exception(parsePaymentErrorMessage(raw))
            }

        if (serverRequest.hash.isBlank()) {
            throw Exception("Server did not return a valid PayHere payment security hash.")
        }

        val merchantId = serverRequest.merchantId.ifBlank { "1234567" }
        val payhereOrderId = serverRequest.payhereOrderId.ifBlank {
            serverRequest.orderId.ifBlank { orderId }
        }
        val amountFormatted = serverRequest.amount.ifBlank { "%.2f".format(Locale.US, fallbackAmount) }
        val currency = serverRequest.currency.ifBlank { "LKR" }
        val notifyUrl = serverRequest.notifyUrl.ifBlank {
            "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/payhere-notify"
        }
        val itemsDescription = serverRequest.items.ifBlank {
            serverRequest.itemDescription.ifBlank {
                "Harvest Order ($fallbackCropName - LKR $amountFormatted)"
            }
        }
        val retUrl = serverRequest.returnUrl.ifBlank { RETURN_URL }
        val canUrl = serverRequest.cancelUrl.ifBlank { CANCEL_URL }

        PayHereFormData(
            merchantId = merchantId,
            returnUrl = retUrl,
            cancelUrl = canUrl,
            notifyUrl = notifyUrl,
            orderId = payhereOrderId,
            items = itemsDescription,
            currency = currency,
            amount = amountFormatted,
            firstName = serverRequest.firstName.ifBlank { buyerName },
            lastName = serverRequest.lastName.ifBlank { "Customer" },
            email = serverRequest.email.ifBlank { buyerEmail },
            phone = serverRequest.phone.ifBlank { buyerPhone },
            address = serverRequest.address.ifBlank { "Sri Lanka" },
            city = serverRequest.city.ifBlank { "Colombo" },
            country = serverRequest.country.ifBlank { "Sri Lanka" },
            hash = serverRequest.hash
        )
    }

    /**
     * Polls the order status until confirmed as "paid" by the PayHere webhook,
     * or until timeout (~60 seconds = 30 attempts x 2000ms).
     */
    suspend fun pollPaymentStatus(
        orderId: String,
        maxRetries: Int = 30,
        delayMillis: Long = 2000
    ): Boolean = withContext(Dispatchers.IO) {
        for (i in 0 until maxRetries) {
            val orderRes = repository.getOrderById(orderId)
            val order = orderRes.getOrNull()
            if (order != null && (order.status == "paid" || order.status == "ready" || order.status == "dispatched" || order.status == "delivered" || order.status == "completed")) {
                return@withContext true
            }
            delay(delayMillis)
        }
        false
    }

    /**
     * Generates auto-submitting HTML form to initiate PayHere Sandbox checkout.
     */
    fun generateHtmlPostForm(data: PayHereFormData): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>PayHere Secure Checkout</title>
                <style>
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                        display: flex;
                        flex-direction: column;
                        align-items: center;
                        justify-content: center;
                        height: 100vh;
                        margin: 0;
                        background: #f7f9f6;
                        color: #1a1c19;
                        text-align: center;
                        padding: 16px;
                    }
                    .loader {
                        width: 48px;
                        height: 48px;
                        border: 4px solid #dbe6dc;
                        border-top-color: #2e7d32;
                        border-radius: 50%;
                        animation: spin 0.8s linear infinite;
                    }
                    @keyframes spin { 0% { transform: rotate(0deg); } 100% { transform: rotate(360deg); } }
                    .title { margin-top: 24px; font-weight: 700; font-size: 18px; color: #2e7d32; }
                    .subtitle { margin-top: 8px; font-size: 14px; color: #49454f; }
                    .secure-badge { margin-top: 16px; font-size: 12px; color: #2e7d32; font-weight: 600; display: inline-flex; align-items: center; }
                </style>
            </head>
            <body onload="document.getElementById('payhere_form').submit();">
                <div class="loader"></div>
                <div class="title">Secure Payment with PayHere</div>
                <div class="subtitle">Redirecting to PayHere Sandbox payment gateway...</div>
                <div class="secure-badge">🔒 256-bit SSL Encrypted Transaction</div>
                <form id="payhere_form" action="$CHECKOUT_URL" method="POST">
                    <input type="hidden" name="merchant_id" value="${data.merchantId}">
                    <input type="hidden" name="return_url" value="${data.returnUrl}">
                    <input type="hidden" name="cancel_url" value="${data.cancelUrl}">
                    <input type="hidden" name="notify_url" value="${data.notifyUrl}">
                    <input type="hidden" name="order_id" value="${data.orderId}">
                    <input type="hidden" name="items" value="${data.items}">
                    <input type="hidden" name="currency" value="${data.currency}">
                    <input type="hidden" name="amount" value="${data.amount}">
                    <input type="hidden" name="first_name" value="${data.firstName}">
                    <input type="hidden" name="last_name" value="${data.lastName}">
                    <input type="hidden" name="email" value="${data.email}">
                    <input type="hidden" name="phone" value="${data.phone}">
                    <input type="hidden" name="address" value="${data.address}">
                    <input type="hidden" name="city" value="${data.city}">
                    <input type="hidden" name="country" value="${data.country}">
                    <input type="hidden" name="hash" value="${data.hash}">
                </form>
            </body>
            </html>
        """.trimIndent()
    }
}

/**
 * Extracts and maps raw server payment error strings/JSON to user-friendly messages.
 */
fun parsePaymentErrorMessage(rawMsg: String): String {
    val lower = rawMsg.lowercase()
    return when {
        lower.contains("already paid") || lower.contains("order_already_paid") -> "Order already paid"
        lower.contains("cancelled") || lower.contains("canceled") || lower.contains("order_cancelled") -> "Order cancelled"
        lower.contains("expired") || lower.contains("order_expired") -> "Order payment window has expired"
        lower.contains("rejected") || lower.contains("order_rejected") -> "Order rejected by farmer"
        lower.contains("session") || lower.contains("unauthorized") || lower.contains("invalid user session") || lower.contains("missing authorization") || lower.contains("401") -> "Session expired, please log in"
        lower.contains("not found") || lower.contains("order_not_found") || lower.contains("404") -> "Order not found"
        lower.contains("only the order buyer") || lower.contains("forbidden") || lower.contains("403") -> "Only the buyer who placed this order can make payment."
        else -> {
            val jsonMatch = Regex("\"error\"\\s*:\\s*\"([^\"]+)\"").find(rawMsg)
            val extracted = jsonMatch?.groupValues?.get(1)
            extracted ?: rawMsg.removePrefix("Server payment initiation error: ").trim()
        }
    }
}
