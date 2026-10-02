package com.example.ui.screens.farmer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.viewmodel.FarmerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FarmerDashboardScreen(
    farmerId: String,
    viewModel: FarmerViewModel,
    onAddListingClick: () -> Unit
) {
    val state by viewModel.dashboardState.collectAsState()

    LaunchedEffect(farmerId) {
        viewModel.loadDashboard(farmerId)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddListingClick,
                containerColor = AgroGreenPrimary,
                contentColor = AgroGreenOnPrimary,
                shape = AgroShapes.large,
                modifier = Modifier.testTag("add_listing_fab")
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add Listing")
            }
        }
    ) { padding ->
        if (state.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ShimmerCardSkeleton()
                ShimmerCardSkeleton()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Reliability Score Hero Card
                item {
                    AgroCard(
                        containerColor = AgroGreenContainer.copy(alpha = 0.45f)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Reliability Score",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = AgroGreenOnContainer
                            )
                            if (state.stats?.isTopFarmer == true) {
                                TopFarmerBadge()
                            }
                        }

                        Spacer(modifier = Modifier.height(AgroSpacing.md))

                        Row(verticalAlignment = Alignment.Bottom) {
                            if (state.completedOrdersCount >= 3) {
                                Text(
                                    text = "${state.stats?.reliabilityScore ?: 0.0}%",
                                    style = MaterialTheme.typography.displaySmall.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = AgroGreenPrimary
                                    )
                                )
                            } else {
                                Text(
                                    text = "New Farmer",
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = AgroGreenPrimary
                                    )
                                )
                            }
                            Spacer(modifier = Modifier.width(AgroSpacing.sm))
                            Text(
                                text = "(${state.completedOrdersCount} completed)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AgroGreenOnContainer.copy(alpha = 0.8f),
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(AgroSpacing.md))
                        HorizontalDivider(color = AgroGreenOnContainer.copy(alpha = 0.15f))
                        Spacer(modifier = Modifier.height(AgroSpacing.md))

                        // Reliability Metrics
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Fulfillment", style = MaterialTheme.typography.labelSmall)
                                Text(
                                    "${((state.stats?.fulfillmentRate ?: 0.0) * 100).toInt()}%",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                            Column {
                                Text("On-Time Rate", style = MaterialTheme.typography.labelSmall)
                                Text(
                                    "${((state.stats?.onTimeRate ?: 0.0) * 100).toInt()}%",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                            Column {
                                Text("Rating", style = MaterialTheme.typography.labelSmall)
                                RatingStars(rating = state.stats?.ratingAvg ?: 0.0, reviewCount = state.stats?.ratingCount)
                            }
                        }
                    }
                }

                // Financial Earnings Card
                item {
                    AgroCard {
                        Text(
                            text = "Earnings & Payouts",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Spacer(modifier = Modifier.height(AgroSpacing.md))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "Total Earned",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = formatLkr(state.totalEarnings),
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = AgroGreenPrimary
                                    )
                                )
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "Pending Payout",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = formatLkr(state.pendingPayouts),
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = AgroEarthBrownSecondary
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(AgroSpacing.sm))
                        Text(
                            text = "Transferred to bank within 1-2 business days.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Active Listings quick count
                item {
                    AgroCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = AgroShapes.medium,
                                    color = AgroGreenContainer,
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Filled.Inventory2, contentDescription = null, tint = AgroGreenPrimary)
                                    }
                                }
                                Spacer(modifier = Modifier.width(AgroSpacing.md))
                                Column {
                                    Text("Active Listings", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                    Text("${state.activeListingsCount} crops visible to buyers", style = MaterialTheme.typography.bodySmall)
                                }
                            }

                            PrimaryButton(
                                text = "New Crop",
                                onClick = onAddListingClick,
                                minHeight = 40.dp
                            )
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(70.dp))
                }
            }
        }
    }
}

