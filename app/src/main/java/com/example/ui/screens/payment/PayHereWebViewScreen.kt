package com.example.ui.screens.payment

import androidx.compose.runtime.Composable
import com.example.AgroMarketApp
import androidx.compose.ui.platform.LocalContext

@Composable
fun PayHereWebViewScreen(
    orderId: String,
    orderNumber: String,
    amount: Double,
    cropSummary: String,
    userToken: String,
    onPaymentSuccess: () -> Unit,
    onPaymentCancelled: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as AgroMarketApp

    PayHereCheckoutScreen(
        orderId = orderId,
        totalAmount = amount,
        cropSummary = cropSummary,
        paymentService = app.paymentService,
        repository = app.repository,
        onBackClick = onDismiss,
        onPaymentSuccess = { _, _ -> onPaymentSuccess() },
        onPaymentFailed = { _, reason ->
            if (reason.contains("cancelled", ignoreCase = true)) {
                onPaymentCancelled()
            } else {
                onDismiss()
            }
        }
    )
}
