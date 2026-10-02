package com.example.ui.screens.checkout

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.viewmodel.CheckoutViewModel
import com.example.viewmodel.FarmerCheckoutConfig
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckoutScreen(
    viewModel: CheckoutViewModel,
    onBackClick: () -> Unit,
    onTrackOrderClick: (String) -> Unit,
    onMarketplaceClick: () -> Unit,
    onNavigateToPayHere: (orderId: String, amount: Double, cropSummary: String) -> Unit = { _, _, _ -> }
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.initCheckout()
    }

    LaunchedEffect(state.navigateToPaymentOrderId) {
        val orderId = state.navigateToPaymentOrderId
        if (!orderId.isNullOrBlank()) {
            onNavigateToPayHere(orderId, state.paymentAmount, state.paymentCropSummary)
            viewModel.onPaymentNavigationHandled()
        }
    }

    var activeDatePickerFarmerId by remember { mutableStateOf<String?>(null) }

    // Date Picker Dialog for zero-typing date selection
    if (activeDatePickerFarmerId != null) {
        val targetConfig = state.configs.find { it.farmerId == activeDatePickerFarmerId }
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = run {
                try {
                    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    sdf.timeZone = TimeZone.getTimeZone("UTC")
                    sdf.parse(targetConfig?.requestedDate ?: targetConfig?.earliestDate ?: "")?.time
                        ?: System.currentTimeMillis()
                } catch (_: Exception) {
                    System.currentTimeMillis()
                }
            }
        )

        DatePickerDialog(
            onDismissRequest = { activeDatePickerFarmerId = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        val selectedMillis = datePickerState.selectedDateMillis
                        if (selectedMillis != null) {
                            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                            sdf.timeZone = TimeZone.getTimeZone("UTC")
                            val formatted = sdf.format(Date(selectedMillis))
                            activeDatePickerFarmerId?.let { fId ->
                                viewModel.onRequestedDateChanged(fId, formatted)
                            }
                        }
                        activeDatePickerFarmerId = null
                    }
                ) {
                    Text("Select Date", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { activeDatePickerFarmerId = null }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(
                state = datePickerState,
                title = {
                    Text(
                        text = "Select Fulfillment Date",
                        modifier = Modifier.padding(AgroSpacing.md),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                },
                headline = {
                    Text(
                        text = "Earliest available: ${targetConfig?.earliestDate ?: "Today"}",
                        modifier = Modifier.padding(horizontal = AgroSpacing.md, vertical = AgroSpacing.xs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        }
    }

    // 1. Order Request Created - Pay Prompt Screen
    if (state.isCompleted) {
        val firstOrder = state.placedOrders.firstOrNull()
        val primaryOrderId = firstOrder?.orderId ?: ""
        val primaryCrop = state.paymentCropSummary.ifBlank { firstOrder?.cropSummary ?: "Produce" }
        val primaryAmount = if (state.paymentAmount > 0) state.paymentAmount else state.totalAmount

        Scaffold { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(AgroSpacing.xl),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Surface(
                        shape = CircleShape,
                        color = AgroGreenContainer,
                        modifier = Modifier.size(88.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.Payment,
                                contentDescription = "Payment Prompt",
                                tint = AgroGreenPrimary,
                                modifier = Modifier.size(48.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(AgroSpacing.lg))

                    Text(
                        text = "Complete Payment to Confirm",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(AgroSpacing.xs))

                    Text(
                        text = "Your order request is reserved. Complete payment with PayHere within 30 minutes to notify the farmer to prepare your harvest.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = AgroSpacing.md),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(AgroSpacing.lg))

                    // List of created orders awaiting payment
                    state.placedOrders.forEach { placed ->
                        AgroCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = AgroSpacing.xs)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(AgroSpacing.sm)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = placed.cropSummary,
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                        )
                                        Text(
                                            text = "Farmer: ${placed.farmerName} • Order: ${placed.orderNumber}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    StatusChip(status = "requested")
                                }
                                if (state.placedOrders.size > 1) {
                                    Spacer(modifier = Modifier.height(AgroSpacing.xs))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        Button(
                                            onClick = {
                                                onNavigateToPayHere(placed.orderId, placed.amount, placed.cropSummary)
                                            },
                                            modifier = Modifier.testTag("pay_order_${placed.orderId}")
                                        ) {
                                            Icon(Icons.Filled.Payment, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Pay ${formatLkr(placed.amount)}")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (state.failedFarmerNames.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(AgroSpacing.sm))
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = AgroShapes.small
                        ) {
                            Text(
                                text = "Some items for ${state.failedFarmerNames.joinToString(", ")} could not be placed and remain in your cart.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(AgroSpacing.lg))

                    if (state.placedOrders.size <= 1) {
                        val singleOrder = state.placedOrders.firstOrNull()
                        val singleAmount = if (singleOrder != null && singleOrder.amount > 0) singleOrder.amount else primaryAmount
                        val singleOrderId = singleOrder?.orderId ?: primaryOrderId
                        val singleCrop = singleOrder?.cropSummary ?: primaryCrop
                        PrimaryButton(
                            text = "Pay Now with PayHere (${formatLkr(singleAmount)})",
                            onClick = {
                                if (singleOrderId.isNotBlank()) {
                                    onNavigateToPayHere(singleOrderId, singleAmount, singleCrop)
                                } else {
                                    onTrackOrderClick("")
                                }
                            },
                            leadingIcon = Icons.Filled.Lock,
                            modifier = Modifier.fillMaxWidth().testTag("proceed_to_payhere_button")
                        )
                        Spacer(modifier = Modifier.height(AgroSpacing.sm))
                    }

                    OutlinedButton(
                        onClick = {
                            onTrackOrderClick(primaryOrderId)
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("pay_later_button"),
                        shape = AgroShapes.medium
                    ) {
                        Text(if (state.placedOrders.size > 1) "View in My Orders" else "Pay Later (My Orders)", fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(modifier = Modifier.height(AgroSpacing.xs))

                    TextButton(
                        onClick = onMarketplaceClick,
                        modifier = Modifier.testTag("back_to_market_button")
                    ) {
                        Text("Back to Marketplace")
                    }
                }
            }
        }
        return
    }

    // 2. Checkout Screen
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Checkout", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick, modifier = Modifier.testTag("checkout_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            StickyBottomCTA {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Total Payable",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatLkr(state.totalAmount),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = AgroGreenPrimary
                        )
                    )
                }

                PrimaryButton(
                    text = "Place Order",
                    onClick = viewModel::submitCheckout,
                    isLoading = state.isSubmitting,
                    leadingIcon = Icons.Filled.Send,
                    minHeight = 50.dp,
                    modifier = Modifier.testTag("place_order_cta_button")
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = AgroSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AgroSpacing.lg),
            contentPadding = PaddingValues(top = AgroSpacing.sm, bottom = AgroSpacing.xxl)
        ) {
            // Error Banner
            if (!state.errorMessage.isNullOrBlank()) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = AgroShapes.card,
                        modifier = Modifier.fillMaxWidth().testTag("checkout_error_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(AgroSpacing.md),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(AgroSpacing.md))
                            Text(
                                text = state.errorMessage!!,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = viewModel::dismissError) {
                                Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                }
            }

            // Farmer Groups (1 to 4)
            items(state.configs, key = { it.farmerId }) { config ->
                FarmerCheckoutSection(
                    config = config,
                    districts = state.districts,
                    onDeliveryMethodChanged = { method ->
                        viewModel.onDeliveryMethodChanged(config.farmerId, method)
                    },
                    onOpenDatePicker = {
                        activeDatePickerFarmerId = config.farmerId
                    },
                    onDistrictChanged = { dId ->
                        viewModel.onDistrictChanged(config.farmerId, dId)
                    },
                    onCityChanged = { cId ->
                        viewModel.onCityChanged(config.farmerId, cId)
                    },
                    onAddressChanged = { addr ->
                        viewModel.onAddressChanged(config.farmerId, addr)
                    }
                )
            }

            // Price Summary Card
            item {
                PriceSummaryCard(
                    itemCount = state.totalItems,
                    subtotal = state.totalAmount,
                    deliveryFee = 0.0,
                    deliveryMethod = if (state.configs.any { it.deliveryMethod == "farmer_delivery" }) "farmer_delivery" else "buyer_arranged"
                )
            }

            item {
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FarmerCheckoutSection(
    config: FarmerCheckoutConfig,
    districts: List<com.example.data.models.District>,
    onDeliveryMethodChanged: (String) -> Unit,
    onOpenDatePicker: () -> Unit,
    onDistrictChanged: (Int) -> Unit,
    onCityChanged: (Int) -> Unit,
    onAddressChanged: (String) -> Unit
) {
    var districtMenuExpanded by remember { mutableStateOf(false) }
    var cityMenuExpanded by remember { mutableStateOf(false) }

    AgroCard {
        // Section Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = AgroGreenContainer,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Person, contentDescription = null, tint = AgroGreenPrimary, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(modifier = Modifier.width(AgroSpacing.sm))
                Column {
                    Text(
                        text = config.farmerName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = config.farmerDistrict ?: "Sri Lanka",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text = formatLkr(config.subtotal),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = AgroGreenPrimary
                )
            )
        }

        Spacer(modifier = Modifier.height(AgroSpacing.sm))

        // Items list summary
        config.items.forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "• ${item.quantityKg.toInt()} kg ${item.cropName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = formatLkr(item.subtotal),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(AgroSpacing.md))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        Spacer(modifier = Modifier.height(AgroSpacing.md))

        // 2 Big Delivery Option Cards
        Text(
            text = "Delivery Method",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
        )
        Spacer(modifier = Modifier.height(AgroSpacing.xs))
        DeliveryOptionGroup(
            selectedMethod = config.deliveryMethod,
            onMethodSelected = onDeliveryMethodChanged
        )

        Spacer(modifier = Modifier.height(AgroSpacing.md))

        // Date Picker Field (Zero typing)
        Text(
            text = if (config.deliveryMethod == "buyer_arranged") "Pickup Date" else "Delivery Date",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
        )
        Spacer(modifier = Modifier.height(AgroSpacing.xs))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(AgroShapes.medium)
                .clickable(onClick = onOpenDatePicker)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), AgroShapes.medium)
                .padding(AgroSpacing.md)
                .testTag("date_picker_trigger_${config.farmerId}")
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.CalendarMonth,
                        contentDescription = "Select Date",
                        tint = AgroGreenPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(AgroSpacing.sm))
                    Column {
                        Text(
                            text = config.requestedDate,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Earliest available: ${config.earliestDate}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Delivery Address Fields (only when Farmer Delivers is chosen)
        AnimatedVisibility(visible = config.deliveryMethod == "farmer_delivery") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = AgroSpacing.md),
                verticalArrangement = Arrangement.spacedBy(AgroSpacing.sm)
            ) {
                Text(
                    text = "Delivery Destination",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )

                // District Dropdown
                ExposedDropdownMenuBox(
                    expanded = districtMenuExpanded,
                    onExpandedChange = { districtMenuExpanded = !districtMenuExpanded }
                ) {
                    val distName = districts.find { it.id == config.deliveryDistrictId }?.name ?: "Select District"
                    OutlinedTextField(
                        value = distName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("District") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = districtMenuExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = AgroShapes.medium
                    )
                    ExposedDropdownMenu(
                        expanded = districtMenuExpanded,
                        onDismissRequest = { districtMenuExpanded = false }
                    ) {
                        districts.forEach { dist ->
                            DropdownMenuItem(
                                text = { Text(dist.name) },
                                onClick = {
                                    onDistrictChanged(dist.id)
                                    districtMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                // Town Dropdown
                ExposedDropdownMenuBox(
                    expanded = cityMenuExpanded,
                    onExpandedChange = { cityMenuExpanded = !cityMenuExpanded }
                ) {
                    val cityName = config.availableCities.find { it.id == config.deliveryCityId }?.name ?: "Select Town"
                    OutlinedTextField(
                        value = cityName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Town / City") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = cityMenuExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = AgroShapes.medium
                    )
                    ExposedDropdownMenu(
                        expanded = cityMenuExpanded,
                        onDismissRequest = { cityMenuExpanded = false }
                    ) {
                        config.availableCities.forEach { city ->
                            DropdownMenuItem(
                                text = { Text(city.name) },
                                onClick = {
                                    onCityChanged(city.id)
                                    cityMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                // Street Address Field
                OutlinedTextField(
                    value = config.deliveryAddress,
                    onValueChange = onAddressChanged,
                    label = { Text("Street Delivery Address") },
                    placeholder = { Text("e.g. 12/A, Kandy Road") },
                    supportingText = {
                        Text("Shared with farmer only after order acceptance.")
                    },
                    modifier = Modifier.fillMaxWidth().testTag("checkout_address_${config.farmerId}"),
                    shape = AgroShapes.medium
                )
            }
        }
    }
}
