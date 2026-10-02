package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.*

/**
 * Reusable App Logo component referencing agromarket_app_logo_1790890003742.
 */
@Composable
fun AppLogo(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    shape: androidx.compose.ui.graphics.Shape = AgroShapes.medium,
    contentDescription: String = "AgroMarket Logo"
) {
    Image(
        painter = painterResource(id = R.drawable.agromarket_app_logo_1790890003742),
        contentDescription = contentDescription,
        modifier = modifier
            .size(size)
            .clip(shape)
            .border(1.dp, AgroGreenPrimary.copy(alpha = 0.15f), shape),
        contentScale = ContentScale.Crop
    )
}

/**
 * AgroCard: Daraz/Uber Eats inspired rounded 16dp card with soft elevation and subtle border.
 */
@Composable
fun AgroCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    elevation: Dp = AgroElevation.soft,
    contentPadding: PaddingValues = PaddingValues(AgroSpacing.lg),
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else Modifier
            ),
        shape = AgroShapes.card,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.linearGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)
                )
            )
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            content = content
        )
    }
}

/**
 * PrimaryButton: Modern full/content-width M3 CTA button with loading support.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    containerColor: Color = AgroGreenPrimary,
    contentColor: Color = AgroGreenOnPrimary,
    minHeight: Dp = 50.dp
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .defaultMinSize(minHeight = minHeight)
            .testTag("primary_button"),
        enabled = enabled && !isLoading,
        shape = AgroShapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = containerColor.copy(alpha = 0.38f),
            disabledContentColor = contentColor.copy(alpha = 0.6f)
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp, pressedElevation = 4.dp),
        contentPadding = PaddingValues(horizontal = AgroSpacing.xl, vertical = AgroSpacing.md)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = contentColor
            )
            Spacer(modifier = Modifier.width(AgroSpacing.sm))
        } else if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(AgroSpacing.sm))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
        )
        if (trailingIcon != null && !isLoading) {
            Spacer(modifier = Modifier.width(AgroSpacing.sm))
            Icon(
                imageVector = trailingIcon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * StickyBottomCTA: Persistent bottom bar for checkout, place order, and status actions.
 */
@Composable
fun StickyBottomCTA(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    elevation: Dp = 8.dp,
    content: @Composable RowScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("sticky_bottom_cta"),
        color = containerColor,
        shadowElevation = elevation,
        tonalElevation = 2.dp,
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.verticalGradient(
                listOf(
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                )
            )
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = AgroSpacing.lg, vertical = AgroSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AgroSpacing.md),
            content = content
        )
    }
}

/**
 * ShimmerSkeleton: Smooth animated gradient effect for list items and cards.
 */
@Composable
fun ShimmerSkeleton(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = AgroShapes.small
) {
    val transition = rememberInfiniteTransition(label = "shimmer_transition")
    val translateAnim = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_translate"
    )

    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
        start = Offset(x = translateAnim.value - 400f, y = translateAnim.value - 400f),
        end = Offset(x = translateAnim.value, y = translateAnim.value)
    )

    Box(
        modifier = modifier
            .clip(shape)
            .background(shimmerBrush)
    )
}

@Composable
fun ShimmerCardSkeleton(
    modifier: Modifier = Modifier,
    height: Dp = 120.dp
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        shape = AgroShapes.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(AgroSpacing.md)) {
            ShimmerSkeleton(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp),
                shape = AgroShapes.medium
            )
            Spacer(modifier = Modifier.height(AgroSpacing.md))
            ShimmerSkeleton(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(18.dp)
            )
            Spacer(modifier = Modifier.height(AgroSpacing.xs))
            ShimmerSkeleton(
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(14.dp)
            )
            Spacer(modifier = Modifier.height(AgroSpacing.sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ShimmerSkeleton(
                    modifier = Modifier
                        .width(80.dp)
                        .height(20.dp)
                )
                ShimmerSkeleton(
                    modifier = Modifier
                        .width(60.dp)
                        .height(20.dp)
                )
            }
        }
    }
}

@Composable
fun ShimmerListItemSkeleton(
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(AgroShapes.medium)
            .background(MaterialTheme.colorScheme.surface)
            .padding(AgroSpacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ShimmerSkeleton(
            modifier = Modifier.size(56.dp),
            shape = AgroShapes.medium
        )
        Spacer(modifier = Modifier.width(AgroSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            ShimmerSkeleton(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(16.dp)
            )
            Spacer(modifier = Modifier.height(AgroSpacing.xs))
            ShimmerSkeleton(
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .height(12.dp)
            )
        }
        Spacer(modifier = Modifier.width(AgroSpacing.sm))
        ShimmerSkeleton(
            modifier = Modifier
                .width(48.dp)
                .height(24.dp),
            shape = AgroShapes.pill
        )
    }
}

/**
 * EmptyState: Friendly icon + 1 short line + optional action button.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String? = null,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val handleAction = onActionClick ?: onAction
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(AgroSpacing.xxl)
            .testTag("empty_state_view"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(AgroGreenContainer.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = AgroGreenPrimary,
                modifier = Modifier.size(36.dp)
            )
        }
        Spacer(modifier = Modifier.height(AgroSpacing.lg))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        if (!message.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(AgroSpacing.xs))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = AgroSpacing.md)
            )
        }
        if (actionLabel != null && handleAction != null) {
            Spacer(modifier = Modifier.height(AgroSpacing.lg))
            PrimaryButton(
                text = actionLabel,
                onClick = handleAction,
                minHeight = 42.dp
            )
        }
    }
}

/**
 * StatusChip: Icon + short label per order status.
 */
@Composable
fun StatusChip(
    status: String,
    modifier: Modifier = Modifier
) {
    val cleanStatus = status.lowercase().trim()
    val (bgColor, textColor, icon, label) = when (cleanStatus) {
        "requested" -> Quadruple(Color(0xFFFFF9C4), Color(0xFFF57F17), Icons.Filled.Pending, "Requested")
        "accepted" -> Quadruple(Color(0xFFE1F5FE), Color(0xFF0288D1), Icons.Filled.CheckCircle, "Accepted")
        "paid" -> Quadruple(Color(0xFFE8F5E9), Color(0xFF2E7D32), Icons.Filled.Payment, "Paid")
        "ready" -> Quadruple(Color(0xFFE0F2F1), Color(0xFF00796B), Icons.Filled.Inventory, "Ready")
        "dispatched" -> Quadruple(Color(0xFFEDE7F6), Color(0xFF512DA8), Icons.Filled.LocalShipping, "Dispatched")
        "delivered" -> Quadruple(Color(0xFFC8E6C9), Color(0xFF1B5E20), Icons.Filled.DoneAll, "Delivered")
        "completed" -> Quadruple(Color(0xFFDCEDC8), Color(0xFF33691E), Icons.Filled.Verified, "Completed")
        "cancelled" -> Quadruple(Color(0xFFFFEBEE), Color(0xFFC62828), Icons.Filled.Cancel, "Cancelled")
        "rejected" -> Quadruple(Color(0xFFFFEBEE), Color(0xFFC62828), Icons.Filled.Block, "Rejected")
        "expired" -> Quadruple(Color(0xFFEEEEEE), Color(0xFF616161), Icons.Filled.TimerOff, "Expired")
        "disputed" -> Quadruple(Color(0xFFFFE0B2), Color(0xFFE65100), Icons.Filled.ReportProblem, "Disputed")
        "refunded" -> Quadruple(Color(0xFFE0E0E0), Color(0xFF424242), Icons.Filled.CurrencyExchange, "Refunded")
        else -> Quadruple(Color(0xFFEEEEEE), Color(0xFF424242), Icons.Filled.Info, cleanStatus.replaceFirstChar { it.uppercase() })
    }

    Surface(
        modifier = modifier.testTag("status_chip_$cleanStatus"),
        color = bgColor,
        shape = AgroShapes.pill
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AgroSpacing.md, vertical = AgroSpacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(AgroSpacing.xs))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = textColor
            )
        }
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

/**
 * QuantityStepper: Modern stepper with 48dp touch targets, minus and plus buttons.
 */
@Composable
fun QuantityStepper(
    value: Double,
    onValueChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
    min: Double = 1.0,
    max: Double = 99999.0,
    step: Double = 1.0,
    unit: String = "kg"
) {
    var textInput by androidx.compose.runtime.remember(value) {
        androidx.compose.runtime.mutableStateOf(if (value % 1.0 == 0.0) value.toInt().toString() else value.toString())
    }

    Row(
        modifier = modifier
            .clip(AgroShapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), AgroShapes.medium)
            .padding(AgroSpacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = {
                val newVal = (value - step).coerceAtLeast(min)
                onValueChange(newVal)
            },
            enabled = value > min,
            modifier = Modifier
                .size(48.dp)
                .testTag("stepper_minus")
        ) {
            Icon(
                imageVector = Icons.Filled.Remove,
                contentDescription = "Decrease Quantity",
                tint = if (value > min) AgroGreenPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }

        Box(
            modifier = Modifier
                .widthIn(min = 72.dp, max = 120.dp)
                .padding(horizontal = AgroSpacing.xs),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.foundation.text.BasicTextField(
                value = textInput,
                onValueChange = { input ->
                    val filtered = input.filter { it.isDigit() || it == '.' }
                    textInput = filtered
                    val parsed = filtered.toDoubleOrNull()
                    if (parsed != null && parsed in min..max) {
                        onValueChange(parsed)
                    }
                },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("stepper_input_field"),
                decorationBox = { innerTextField ->
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        innerTextField()
                        if (unit.isNotBlank()) {
                            Text(
                                text = " $unit",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        }

        IconButton(
            onClick = {
                val newVal = (value + step).coerceAtMost(max)
                onValueChange(newVal)
            },
            enabled = value < max,
            modifier = Modifier
                .size(48.dp)
                .testTag("stepper_plus")
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "Increase Quantity",
                tint = if (value < max) AgroGreenPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
    }
}

/**
 * ChipSelector: Horizontal scrolling pill chips for filters, categories, and options.
 */
@Composable
fun <T> ChipSelector(
    items: List<T>,
    selectedItem: T?,
    onItemSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    labelProvider: (T) -> String = { it.toString() },
    iconProvider: ((T) -> ImageVector?)? = null
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AgroSpacing.sm),
        contentPadding = PaddingValues(horizontal = AgroSpacing.lg)
    ) {
        items(items) { item ->
            val isSelected = (item == selectedItem)
            val icon = iconProvider?.invoke(item)

            FilterChip(
                selected = isSelected,
                onClick = { onItemSelected(item) },
                label = {
                    Text(
                        text = labelProvider(item),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    )
                },
                leadingIcon = if (icon != null) {
                    {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else null,
                shape = AgroShapes.pill,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AgroGreenPrimary,
                    selectedLabelColor = AgroGreenOnPrimary,
                    selectedLeadingIconColor = AgroGreenOnPrimary,
                    containerColor = MaterialTheme.colorScheme.surface,
                    labelColor = MaterialTheme.colorScheme.onSurface
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = isSelected,
                    borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    selectedBorderColor = AgroGreenPrimary
                ),
                modifier = Modifier.defaultMinSize(minHeight = 40.dp)
            )
        }
    }
}

/**
 * SectionHeader: Clean title row with optional action / badge.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
    badgeText: String? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AgroSpacing.lg, vertical = AgroSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AgroSpacing.sm)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            if (badgeText != null) {
                Surface(
                    color = AgroGreenContainer,
                    shape = AgroShapes.pill
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = AgroGreenOnContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }

        if (actionLabel != null && onActionClick != null) {
            TextButton(
                onClick = onActionClick,
                contentPadding = PaddingValues(horizontal = AgroSpacing.sm)
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = AgroGreenPrimary
                )
            }
        }
    }
}

// -------------------------------------------------------------
// Component Previews
// -------------------------------------------------------------

@Preview(showBackground = true)
@Composable
fun PreviewAppLogo() {
    MyApplicationTheme {
        AppLogo(size = 64.dp)
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewAgroCard() {
    MyApplicationTheme {
        AgroCard(modifier = Modifier.padding(16.dp)) {
            Text("Sample Card Title", fontWeight = FontWeight.Bold)
            Text("This is an AgroCard with soft elevation and rounded 16dp corners.")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewPrimaryButton() {
    MyApplicationTheme {
        PrimaryButton(
            text = "Proceed to Checkout",
            onClick = {},
            leadingIcon = Icons.Filled.ShoppingBag,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewStickyBottomCTA() {
    MyApplicationTheme {
        StickyBottomCTA {
            Column(modifier = Modifier.weight(1f)) {
                Text("Total Amount", style = MaterialTheme.typography.bodySmall)
                Text("Rs. 14,500.00", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            PrimaryButton(text = "Place Order", onClick = {})
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewSkeletons() {
    MyApplicationTheme {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ShimmerCardSkeleton()
            ShimmerListItemSkeleton()
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewEmptyState() {
    MyApplicationTheme {
        EmptyState(
            icon = Icons.Outlined.SearchOff,
            title = "No Crops Found",
            message = "Try adjusting your search filters or district selection.",
            actionLabel = "Clear Filters",
            onActionClick = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewStatusChips() {
    MyApplicationTheme {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusChip(status = "accepted")
            StatusChip(status = "paid")
            StatusChip(status = "delivered")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewQuantityStepper() {
    MyApplicationTheme {
        var qty by remember { mutableDoubleStateOf(50.0) }
        QuantityStepper(
            value = qty,
            onValueChange = { qty = it },
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewChipSelector() {
    MyApplicationTheme {
        val categories = listOf("All Crops", "Vegetables", "Fruits", "Grains", "Spices")
        var selected by remember { mutableStateOf("All Crops") }
        ChipSelector(
            items = categories,
            selectedItem = selected,
            onItemSelected = { selected = it },
            modifier = Modifier.padding(vertical = 16.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewSectionHeader() {
    MyApplicationTheme {
        SectionHeader(
            title = "Featured Harvests",
            badgeText = "Fresh Today",
            actionLabel = "View all",
            onActionClick = {}
        )
    }
}
