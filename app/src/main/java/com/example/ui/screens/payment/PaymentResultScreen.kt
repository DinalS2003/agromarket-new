package com.example.ui.screens.payment

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ui.components.*
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentResultScreen(
    orderId: String,
    isSuccess: Boolean,
    amount: Double,
    failureReason: String? = null,
    onViewOrderClick: (orderId: String) -> Unit,
    onBrowseMarketplaceClick: () -> Unit,
    onRetryPaymentClick: () -> Unit,
    onBackClick: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isSuccess) "Payment Successful" else "Payment Failed", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick, modifier = Modifier.testTag("result_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(AgroSpacing.xl),
            contentAlignment = Alignment.Center
        ) {
            if (isSuccess) {
                // Success View
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Surface(
                        shape = CircleShape,
                        color = AgroGreenContainer,
                        modifier = Modifier.size(96.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = "Payment Successful",
                                tint = AgroGreenPrimary,
                                modifier = Modifier.size(56.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(AgroSpacing.lg))

                    Text(
                        text = "Payment Received!",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(AgroSpacing.xs))

                    Text(
                        text = "Your payment of ${formatLkr(amount)} has been confirmed. The farmer has been notified to prepare your harvest.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(AgroSpacing.lg))

                    AgroCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Order Number",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "AM-${orderId.take(8).uppercase()}",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                            StatusChip(status = "paid")
                        }
                    }

                    Spacer(modifier = Modifier.height(AgroSpacing.xl))

                    PrimaryButton(
                        text = "View Order",
                        onClick = { onViewOrderClick(orderId) },
                        leadingIcon = Icons.Filled.ReceiptLong,
                        modifier = Modifier.fillMaxWidth().testTag("view_order_button")
                    )

                    Spacer(modifier = Modifier.height(AgroSpacing.sm))

                    OutlinedButton(
                        onClick = onBrowseMarketplaceClick,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = AgroShapes.medium
                    ) {
                        Text("Back to Marketplace", fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                // Failure / Cancelled View
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.size(96.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.ErrorOutline,
                                contentDescription = "Payment Incomplete",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(56.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(AgroSpacing.lg))

                    Text(
                        text = "Payment Not Completed",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(AgroSpacing.xs))

                    Text(
                        text = failureReason ?: "The payment session was cancelled or could not be completed with the payment provider.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(AgroSpacing.lg))

                    AgroCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Order Saved",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "AM-${orderId.take(8).uppercase()}",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                            StatusChip(status = "requested")
                        }
                    }

                    Spacer(modifier = Modifier.height(AgroSpacing.xl))

                    PrimaryButton(
                        text = "Retry Payment",
                        onClick = onRetryPaymentClick,
                        leadingIcon = Icons.Filled.Refresh,
                        modifier = Modifier.fillMaxWidth().testTag("retry_payment_button")
                    )

                    Spacer(modifier = Modifier.height(AgroSpacing.sm))

                    OutlinedButton(
                        onClick = { onViewOrderClick(orderId) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = AgroShapes.medium
                    ) {
                        Text("View Order Details", fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(modifier = Modifier.height(AgroSpacing.sm))

                    TextButton(
                        onClick = onBrowseMarketplaceClick,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Return to Marketplace")
                    }
                }
            }
        }
    }
}
