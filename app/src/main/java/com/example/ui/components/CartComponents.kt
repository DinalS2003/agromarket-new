package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.models.CartItem
import com.example.ui.theme.*

/**
 * CartItemCard: Displays a single cart listing with crop thumbnail, name,
 * price/kg, quantity stepper, stock warnings, and remove button.
 * Uses 16dp rounded shape and soft elevation.
 */
@Composable
fun CartItemCard(
    item: CartItem,
    onQuantityChange: (Double) -> Unit,
    onRemoveClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AgroCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("cart_item_${item.listingId}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            // Thumbnail / Icon
            Surface(
                modifier = Modifier
                    .size(72.dp)
                    .clip(AgroShapes.medium),
                color = AgroGreenContainer.copy(alpha = 0.4f),
                tonalElevation = 1.dp
            ) {
                if (!item.photoUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = resolveImageModel(item.photoUrl),
                        contentDescription = item.cropName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Eco,
                            contentDescription = null,
                            tint = AgroGreenPrimary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(AgroSpacing.md))

            // Details & Delete
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.cropName,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${item.farmerName}${if (!item.farmerDistrict.isNullOrBlank()) " • ${item.farmerDistrict}" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Remove button with 48dp touch target
                    IconButton(
                        onClick = onRemoveClick,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("cart_remove_${item.listingId}")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = "Remove from cart",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(AgroSpacing.xs))

                // Price per kg & Subtotal
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${formatLkr(item.pricePerKg)}/kg",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatLkr(item.subtotal),
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = AgroGreenPrimary
                        )
                    )
                }

                // Out of stock / exceeds stock banner
                if (item.isOutOfStock) {
                    Spacer(modifier = Modifier.height(AgroSpacing.xs))
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = AgroShapes.small
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Item out of stock or unavailable",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                } else if (item.exceedsStock) {
                    Spacer(modifier = Modifier.height(AgroSpacing.xs))
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                        shape = AgroShapes.small
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Only ${item.availableStock.toInt()} kg available",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(AgroSpacing.sm))

                // Quantity Stepper
                QuantityStepper(
                    value = item.quantityKg,
                    onValueChange = onQuantityChange,
                    min = minOf(item.minOrderKg, item.availableStock.coerceAtLeast(1.0)),
                    max = item.availableStock.coerceAtLeast(1.0),
                    step = 1.0,
                    unit = "kg",
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * DeliveryOptionCard: Selectable 16dp rounded card with icon, short label, and radio indicator.
 */
@Composable
fun DeliveryOptionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isSelected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    badgeText: String? = null
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(AgroShapes.card)
            .clickable(onClick = onSelect)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) AgroGreenPrimary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape = AgroShapes.card
            )
            .testTag("delivery_option_${title.lowercase().replace(" ", "_")}"),
        shape = AgroShapes.card,
        color = if (isSelected) AgroGreenContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface,
        tonalElevation = if (isSelected) 2.dp else 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AgroSpacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = if (isSelected) AgroGreenPrimary else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isSelected) AgroGreenOnPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(AgroSpacing.md))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (badgeText != null) {
                        Surface(
                            color = AgroGreenPrimary,
                            shape = AgroShapes.pill
                        ) {
                            Text(
                                text = badgeText,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = AgroGreenOnPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                fontSize = 10.sp
                            )
                        }
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            RadioButton(
                selected = isSelected,
                onClick = onSelect,
                colors = RadioButtonDefaults.colors(
                    selectedColor = AgroGreenPrimary
                ),
                modifier = Modifier.size(48.dp)
            )
        }
    }
}

/**
 * DeliveryOptionGroup: Two selectable options: Pickup (buyer arranges) vs Farmer Delivers.
 */
@Composable
fun DeliveryOptionGroup(
    selectedMethod: String,
    onMethodSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AgroSpacing.sm)
    ) {
        DeliveryOptionCard(
            title = "Pickup",
            subtitle = "Arrange your own transport from farmer landmark",
            icon = Icons.Filled.Storefront,
            isSelected = selectedMethod == "buyer_arranged",
            onSelect = { onMethodSelected("buyer_arranged") },
            badgeText = "Free"
        )

        DeliveryOptionCard(
            title = "Farmer Delivers",
            subtitle = "Farmer brings harvest to your address (fee quoted on accept)",
            icon = Icons.Filled.LocalShipping,
            isSelected = selectedMethod == "farmer_delivery",
            onSelect = { onMethodSelected("farmer_delivery") },
            badgeText = "Recommended"
        )
    }
}

/**
 * PriceSummaryCard: Clean price breakdown card with 16dp rounded corners,
 * items subtotal, delivery fee, total amount, and secure payment note.
 */
@Composable
fun PriceSummaryCard(
    itemCount: Int,
    subtotal: Double,
    deliveryFee: Double? = null,
    deliveryMethod: String = "buyer_arranged",
    discount: Double = 0.0,
    modifier: Modifier = Modifier
) {
    AgroCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("price_summary_card")
    ) {
        Text(
            text = "Payment Summary",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
        )

        Spacer(modifier = Modifier.height(AgroSpacing.sm))

        // Items Subtotal Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Items ($itemCount)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatLkr(subtotal),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
            )
        }

        if (discount > 0.0) {
            Spacer(modifier = Modifier.height(AgroSpacing.xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Order Discount",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AgroGreenPrimary
                )
                Text(
                    text = "-${formatLkr(discount)}",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = AgroGreenPrimary
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(AgroSpacing.xs))

        // Delivery Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Delivery Fee",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val feeText = when {
                deliveryMethod == "buyer_arranged" -> "Rs. 0.00 (Pickup)"
                deliveryFee != null && deliveryFee > 0 -> formatLkr(deliveryFee)
                else -> "Quoted by farmer"
            }
            Text(
                text = feeText,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = if (deliveryMethod == "buyer_arranged") AgroGreenPrimary else MaterialTheme.colorScheme.onSurface
                )
            )
        }

        Spacer(modifier = Modifier.height(AgroSpacing.sm))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        Spacer(modifier = Modifier.height(AgroSpacing.sm))

        // Total Row
        val total = subtotal + (deliveryFee ?: 0.0)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Total Payable",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
                if (deliveryMethod == "farmer_delivery" && (deliveryFee == null || deliveryFee == 0.0)) {
                    Text(
                        text = "Excl. delivery (quoted on accept)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = formatLkr(total),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = AgroGreenPrimary
                )
            )
        }

        Spacer(modifier = Modifier.height(AgroSpacing.md))

        // Secure Payment Note
        Surface(
            color = AgroGreenContainer.copy(alpha = 0.4f),
            shape = AgroShapes.small,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = AgroSpacing.md, vertical = AgroSpacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Shield,
                    contentDescription = "Secure Payment",
                    tint = AgroGreenPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(AgroSpacing.sm))
                Text(
                    text = "Secure PayHere Checkout • Direct Farmer Payout",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    color = AgroGreenOnContainer
                )
            }
        }
    }
}

// -------------------------------------------------------------------------
// Previews
// -------------------------------------------------------------------------

@Preview(showBackground = true)
@Composable
fun PreviewCartItemCard() {
    MyApplicationTheme {
        val sampleItem = CartItem(
            listingId = "1",
            farmerId = "f1",
            farmerName = "Sunil Bandara",
            farmerDistrict = "Nuwara Eliya",
            cropName = "Carrots (Nuwara Eliya)",
            pricePerKg = 240.0,
            quantityKg = 25.0,
            availableStock = 100.0,
            minOrderKg = 10.0
        )
        CartItemCard(
            item = sampleItem,
            onQuantityChange = {},
            onRemoveClick = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewDeliveryOptionGroup() {
    MyApplicationTheme {
        DeliveryOptionGroup(
            selectedMethod = "buyer_arranged",
            onMethodSelected = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewPriceSummaryCard() {
    MyApplicationTheme {
        PriceSummaryCard(
            itemCount = 2,
            subtotal = 8400.0,
            deliveryFee = 0.0,
            deliveryMethod = "buyer_arranged",
            modifier = Modifier.padding(16.dp)
        )
    }
}
