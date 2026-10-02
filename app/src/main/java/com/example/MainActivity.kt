package com.example

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.example.BuildConfig
import com.example.data.models.AppMode
import com.example.data.models.ListingItem
import com.example.data.models.PayHerePaymentRequest
import com.example.ui.components.AgroBottomNavigation
import com.example.ui.components.AgroTopBar
import com.example.ui.components.OfflineBanner
import com.example.ui.screens.auth.LoginScreen
import com.example.ui.screens.auth.SignUpScreen
import com.example.ui.screens.auth.WelcomeScreen
import com.example.ui.screens.chat.ChatScreen
import com.example.ui.screens.chat.MessagesScreen
import com.example.ui.screens.farmer.AddEditListingScreen
import com.example.ui.screens.farmer.FarmerDashboardScreen
import com.example.ui.screens.farmer.MyListingsScreen
import com.example.ui.screens.listing.ListingDetailScreen
import com.example.ui.screens.notifications.NotificationsScreen
import com.example.ui.screens.onboarding.OnboardingScreen
import com.example.ui.screens.orders.OrderDetailScreen
import com.example.ui.screens.orders.OrdersListScreen
import com.example.ui.screens.orders.PlaceOrderScreen
import com.example.ui.screens.marketplace.MarketplaceScreen
import com.example.ui.screens.profile.ProfileScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.*
import kotlinx.coroutines.launch
import lk.payhere.androidsdk.PHConfigs
import lk.payhere.androidsdk.PHConstants
import lk.payhere.androidsdk.PHMainActivity
import lk.payhere.androidsdk.PHResponse
import lk.payhere.androidsdk.model.InitRequest
import lk.payhere.androidsdk.model.Item
import lk.payhere.androidsdk.model.StatusResponse

class MainActivity : ComponentActivity() {

    private val app get() = AgroMarketApp.instance

    private var pendingPayingOrderId: String? = null
    private var activeOrderViewModel: OrderViewModel? = null

    private val payHereLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val orderId = pendingPayingOrderId ?: ""
        val vm = activeOrderViewModel
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val response = data.getSerializableExtra(PHConstants.INTENT_EXTRA_RESULT) as? PHResponse<StatusResponse>
            if (response != null && response.isSuccess) {
                vm?.pollPaymentStatus(orderId)
            } else {
                vm?.onPaymentSdkResult(result.resultCode, orderId)
            }
        } else {
            vm?.onPaymentSdkResult(result.resultCode, orderId)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                val navController = rememberNavController()
                val scope = rememberCoroutineScope()

                // State from SessionManager
                val authToken by app.sessionManager.authTokenFlow.collectAsState(initial = null)
                val userId by app.sessionManager.userIdFlow.collectAsState(initial = null)
                val isFarmer by app.sessionManager.isFarmerFlow.collectAsState(initial = false)
                val appMode by app.sessionManager.appModeFlow.collectAsState(initial = AppMode.BUYING)
                val userDistrictId by app.sessionManager.userDistrictIdFlow.collectAsState(initial = 1)

                // ViewModels
                val authViewModel = remember { AuthViewModel(app.repository) }
                val marketplaceViewModel = remember { MarketplaceViewModel(app.repository, null) }
                val orderViewModel = remember { OrderViewModel(app.repository) }
                activeOrderViewModel = orderViewModel
                val farmerViewModel = remember { FarmerViewModel(app.repository) }
                val chatViewModel = remember { ChatViewModel(app.repository) }
                val profileViewModel = remember { ProfileViewModel(app.repository, app.sessionManager) }
                val cartViewModel = remember { CartViewModel(app.cartRepository) }
                val checkoutViewModel = remember { CheckoutViewModel(app.repository, app.sessionManager, cartViewModel) }
                val cartState by cartViewModel.uiState.collectAsState()

                // Selected Listing Cache for Navigation
                var selectedListing by remember { mutableStateOf<ListingItem?>(null) }
                var chatListing by remember { mutableStateOf<ListingItem?>(null) }
                var chatCounterpartName by remember { mutableStateOf<String?>(null) }
                var unreadNotificationsCount by remember { mutableIntStateOf(0) }

                // Determine start destination: Farmers go to dashboard first upon login / start
                val startDestination = if (authToken.isNullOrEmpty()) "welcome" else if (isFarmer) "dashboard" else "marketplace"

                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route ?: startDestination
                val authRoutes = listOf("welcome", "signup", "login", "onboarding")

                // Session Expiration handler: When expired, silently redirect to welcome
                LaunchedEffect(Unit) {
                    app.sessionManager.sessionExpiredFlow.collect {
                        authViewModel.resetAuth()
                        if (currentRoute !in authRoutes) {
                            navController.navigate("welcome") {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                    }
                }

                LaunchedEffect(authToken) {
                    if (authToken.isNullOrEmpty() && currentRoute !in authRoutes) {
                        authViewModel.resetAuth()
                        navController.navigate("welcome") {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                }

                LaunchedEffect(userId, isFarmer) {
                    marketplaceViewModel.setCurrentUserId(userId)
                    if (!userId.isNullOrBlank()) {
                        chatViewModel.setCurrentUser(userId!!)
                        chatViewModel.loadConversations(userId!!, isFarmer)
                        val notifs = app.repository.getNotifications(userId!!).getOrNull() ?: emptyList()
                        unreadNotificationsCount = notifs.count { it.readAt == null }
                    }
                }

                val conversations by chatViewModel.conversations.collectAsState()
                val liveUnreadCount by chatViewModel.totalUnreadCount.collectAsState()
                val unreadMessagesCount = remember(liveUnreadCount, conversations) {
                    if (liveUnreadCount > 0) liveUnreadCount else conversations.sumOf { it.unreadCount }
                }

                val showBars = currentRoute in listOf(
                    "marketplace", "my_orders", "notifications", "profile",
                    "my_listings", "farmer_orders", "dashboard", "messages"
                )

                Scaffold(
                    contentWindowInsets = if (showBars) ScaffoldDefaults.contentWindowInsets else WindowInsets(0, 0, 0, 0),
                    topBar = {
                        if (showBars) {
                            AgroTopBar(
                                currentMode = appMode,
                                isFarmer = isFarmer,
                                unreadNotifications = unreadNotificationsCount,
                                unreadMessages = unreadMessagesCount,
                                onModeToggle = { newMode ->
                                    scope.launch {
                                        app.sessionManager.setAppMode(newMode)
                                        if (newMode == AppMode.BUYING) {
                                            navController.navigate("marketplace") {
                                                popUpTo("marketplace") { inclusive = true }
                                            }
                                        } else {
                                            navController.navigate("my_listings") {
                                                popUpTo("my_listings") { inclusive = true }
                                            }
                                        }
                                    }
                                },
                                onNotificationsClick = { navController.navigate("notifications") },
                                onMessagesClick = { navController.navigate("messages") },
                                onBecomeFarmerClick = {
                                    authViewModel.initFarmerOnboarding()
                                    navController.navigate("onboarding")
                                },
                                cartItemCount = cartState.totalItemCount,
                                onCartClick = { navController.navigate("cart") }
                            )
                        }
                    },
                    bottomBar = {
                        if (showBars) {
                            AgroBottomNavigation(
                                currentRoute = currentRoute,
                                currentMode = appMode,
                                unreadNotifications = unreadNotificationsCount,
                                unreadMessages = unreadMessagesCount,
                                onNavigate = { route ->
                                    navController.navigate(route) {
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            )
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (showBars) Modifier.padding(innerPadding) else Modifier)
                    ) {
                        NavHost(
                            navController = navController,
                            startDestination = startDestination
                        ) {
                            // 1. Auth & Onboarding Flow
                            composable("welcome") {
                                WelcomeScreen(
                                    onCreateAccountClick = {
                                        authViewModel.resetAuth()
                                        navController.navigate("signup")
                                    },
                                    onLoginClick = {
                                        authViewModel.resetAuth()
                                        navController.navigate("login")
                                    }
                                )
                            }

                            composable("signup") {
                                SignUpScreen(
                                    viewModel = authViewModel,
                                    onBackToWelcome = {
                                        navController.popBackStack()
                                    },
                                    onSignUpSuccess = {
                                        scope.launch {
                                            app.sessionManager.setAppMode(AppMode.BUYING)
                                            marketplaceViewModel.refreshMarketplace()
                                            navController.navigate("marketplace") {
                                                popUpTo("welcome") { inclusive = true }
                                            }
                                        }
                                    },
                                    onFarmerSignUpSuccess = {
                                        scope.launch {
                                            app.sessionManager.setAppMode(AppMode.SELLING)
                                            val currentId = app.sessionManager.getUserId() ?: ""
                                            farmerViewModel.loadMyListings(currentId)
                                            marketplaceViewModel.refreshMarketplace()
                                            navController.navigate("my_listings") {
                                                popUpTo("welcome") { inclusive = true }
                                            }
                                        }
                                    }
                                )
                            }

                            composable("login") {
                                LoginScreen(
                                    viewModel = authViewModel,
                                    onSessionReady = {
                                        scope.launch {
                                            val farmer = app.sessionManager.isFarmer()
                                            if (farmer) {
                                                app.sessionManager.setAppMode(AppMode.SELLING)
                                                val currentId = app.sessionManager.getUserId() ?: ""
                                                farmerViewModel.loadDashboard(currentId)
                                                farmerViewModel.loadMyListings(currentId)
                                                navController.navigate("dashboard") {
                                                    popUpTo("welcome") { inclusive = true }
                                                }
                                            } else {
                                                app.sessionManager.setAppMode(AppMode.BUYING)
                                                marketplaceViewModel.refreshMarketplace()
                                                navController.navigate("marketplace") {
                                                    popUpTo("welcome") { inclusive = true }
                                                }
                                            }
                                        }
                                    },
                                    onNeedsOnboarding = {
                                        navController.navigate("onboarding") {
                                            popUpTo("login") { inclusive = true }
                                        }
                                    },
                                    onBackClick = {
                                        navController.popBackStack()
                                    }
                                )
                            }

                            composable("onboarding") {
                                OnboardingScreen(
                                    viewModel = authViewModel,
                                    onComplete = {
                                        scope.launch {
                                            val farmer = app.sessionManager.isFarmer()
                                            if (farmer) {
                                                app.sessionManager.setAppMode(AppMode.SELLING)
                                                val currentId = app.sessionManager.getUserId() ?: ""
                                                farmerViewModel.loadMyListings(currentId)
                                                navController.navigate("my_listings") {
                                                    popUpTo("onboarding") { inclusive = true }
                                                }
                                            } else {
                                                app.sessionManager.setAppMode(AppMode.BUYING)
                                                marketplaceViewModel.refreshMarketplace()
                                                navController.navigate("marketplace") {
                                                    popUpTo("onboarding") { inclusive = true }
                                                }
                                            }
                                        }
                                    }
                                )
                            }

                            // 2. Marketplace
                            composable("marketplace") {
                                MarketplaceScreen(
                                    viewModel = marketplaceViewModel,
                                    onListingClick = { listing ->
                                        selectedListing = listing
                                        navController.navigate("listing_detail")
                                    },
                                    onChatClick = { listing ->
                                        chatListing = listing
                                        chatCounterpartName = listing.farmerFirstName ?: "Farmer"
                                        navController.navigate("chat_listing/${listing.id}")
                                    }
                                )
                            }

                            composable("listing_detail") {
                                val listing = selectedListing
                                if (listing != null) {
                                    ListingDetailScreen(
                                        listing = listing,
                                        reviews = emptyList(),
                                        currentUserId = userId,
                                        onBackClick = { navController.popBackStack() },
                                        onOrderClick = { qty ->
                                            orderViewModel.initPlaceOrder(listing, userDistrictId, 1, qty)
                                            navController.navigate("place_order")
                                        },
                                        onChatClick = {
                                            chatListing = listing
                                            chatCounterpartName = listing.farmerFirstName ?: "Farmer"
                                            navController.navigate("chat_listing/${listing.id}")
                                        },
                                        canDelete = isFarmer || appMode == AppMode.SELLING,
                                        onDeleteClick = {
                                            farmerViewModel.deleteListing(listing.id, userId ?: "farmer_me") {
                                                marketplaceViewModel.refreshMarketplace()
                                                navController.popBackStack()
                                            }
                                        },
                                        onFarmerClick = { farmerId ->
                                            navController.navigate("farmer_profile/$farmerId")
                                        },
                                        cartItemCount = cartState.totalItemCount,
                                        onCartClick = {
                                            navController.navigate("cart")
                                        },
                                        onAddToCart = { qty ->
                                            cartViewModel.addToCart(listing, qty)
                                        }
                                    )
                                }
                            }

                            composable("cart") {
                                com.example.ui.screens.cart.CartScreen(
                                    viewModel = cartViewModel,
                                    onBackClick = { navController.popBackStack() },
                                    onCheckoutClick = {
                                        navController.navigate("checkout")
                                    },
                                    onBrowseMarketClick = {
                                        navController.navigate("marketplace") {
                                            popUpTo("marketplace") { inclusive = true }
                                        }
                                    }
                                )
                            }

                            composable("checkout") {
                                val rootDest = if (isFarmer) "dashboard" else "marketplace"
                                com.example.ui.screens.checkout.CheckoutScreen(
                                    viewModel = checkoutViewModel,
                                    onBackClick = {
                                        android.util.Log.i("NavAudit", "[checkout:onBackClick] popBackStack | BackStack: ${navController.currentBackStack.value.map { it.destination.route }}")
                                        navController.popBackStack()
                                    },
                                    onTrackOrderClick = { orderId ->
                                        android.util.Log.i("NavAudit", "[checkout:onTrackOrderClick] orderId=$orderId | BackStack: ${navController.currentBackStack.value.map { it.destination.route }}")
                                        if (orderId.isNotBlank()) {
                                            navController.navigate("order_detail/$orderId") {
                                                popUpTo(rootDest) { inclusive = false }
                                                launchSingleTop = true
                                            }
                                        } else {
                                            navController.navigate("my_orders") {
                                                popUpTo(rootDest) { inclusive = false }
                                                launchSingleTop = true
                                            }
                                        }
                                    },
                                    onMarketplaceClick = {
                                        android.util.Log.i("NavAudit", "[checkout:onMarketplaceClick] rootDest=$rootDest")
                                        navController.navigate(rootDest) {
                                            popUpTo(rootDest) { inclusive = false }
                                            launchSingleTop = true
                                        }
                                    },
                                    onNavigateToPayHere = { orderId, amount, crop ->
                                        val encCrop = java.net.URLEncoder.encode(crop, "UTF-8")
                                        android.util.Log.i("NavAudit", "[checkout:onNavigateToPayHere] orderId=$orderId amount=$amount")
                                        navController.navigate("payhere_checkout/$orderId?amount=$amount&crop=$encCrop")
                                    }
                                )
                            }

                            composable(
                                route = "payhere_checkout/{orderId}?amount={amount}&crop={crop}",
                                arguments = listOf(
                                    navArgument("orderId") { type = NavType.StringType },
                                    navArgument("amount") { type = NavType.StringType; defaultValue = "0.0" },
                                    navArgument("crop") { type = NavType.StringType; defaultValue = "Produce" }
                                )
                            ) { backStackEntry ->
                                val orderId = backStackEntry.arguments?.getString("orderId") ?: ""
                                val amount = backStackEntry.arguments?.getString("amount")?.toDoubleOrNull() ?: 0.0
                                val cropRaw = backStackEntry.arguments?.getString("crop") ?: "Produce"
                                val crop = try { java.net.URLDecoder.decode(cropRaw, "UTF-8") } catch (_: Exception) { cropRaw }
                                val rootDest = if (isFarmer) "dashboard" else "marketplace"

                                com.example.ui.screens.payment.PayHereCheckoutScreen(
                                    orderId = orderId,
                                    totalAmount = amount,
                                    cropSummary = crop,
                                    paymentService = app.paymentService,
                                    repository = app.repository,
                                    onBackClick = {
                                        android.util.Log.i("NavAudit", "[payhere_checkout:onBackClick] popBackStack | BackStack: ${navController.currentBackStack.value.map { it.destination.route }}")
                                        navController.popBackStack()
                                    },
                                    onPaymentSuccess = { paidId, paidAmount ->
                                        android.util.Log.i("NavAudit", "[payhere_checkout:onPaymentSuccess] paidId=$paidId amount=$paidAmount | BackStack: ${navController.currentBackStack.value.map { it.destination.route }}")
                                        navController.navigate("payment_result/$paidId?success=true&amount=$paidAmount") {
                                            popUpTo(rootDest) { inclusive = false }
                                            launchSingleTop = true
                                        }
                                    },
                                    onPaymentFailed = { failedId, reason ->
                                        android.util.Log.i("NavAudit", "[payhere_checkout:onPaymentFailed] failedId=$failedId reason=$reason | BackStack: ${navController.currentBackStack.value.map { it.destination.route }}")
                                        val encReason = java.net.URLEncoder.encode(reason, "UTF-8")
                                        navController.navigate("payment_result/$failedId?success=false&amount=$amount&reason=$encReason") {
                                            popUpTo(rootDest) { inclusive = false }
                                            launchSingleTop = true
                                        }
                                    }
                                )
                            }

                            composable(
                                route = "payment_result/{orderId}?success={success}&amount={amount}&reason={reason}",
                                arguments = listOf(
                                    navArgument("orderId") { type = NavType.StringType },
                                    navArgument("success") { type = NavType.BoolType; defaultValue = false },
                                    navArgument("amount") { type = NavType.StringType; defaultValue = "0.0" },
                                    navArgument("reason") { type = NavType.StringType; defaultValue = "" }
                                )
                            ) { backStackEntry ->
                                val orderId = backStackEntry.arguments?.getString("orderId") ?: ""
                                val isSuccess = backStackEntry.arguments?.getBoolean("success") ?: false
                                val amount = backStackEntry.arguments?.getString("amount")?.toDoubleOrNull() ?: 0.0
                                val rawReason = backStackEntry.arguments?.getString("reason") ?: ""
                                val reason = try { java.net.URLDecoder.decode(rawReason, "UTF-8").ifBlank { null } } catch (_: Exception) { rawReason.ifBlank { null } }
                                val rootDest = if (isFarmer) "dashboard" else "marketplace"

                                com.example.ui.screens.payment.PaymentResultScreen(
                                    orderId = orderId,
                                    isSuccess = isSuccess,
                                    amount = amount,
                                    failureReason = reason,
                                    onViewOrderClick = { oId ->
                                        android.util.Log.i("NavAudit", "[payment_result:onViewOrderClick] oId=$oId rootDest=$rootDest | BackStack: ${navController.currentBackStack.value.map { it.destination.route }}")
                                        if (oId.isNotBlank()) {
                                            navController.navigate("order_detail/$oId") {
                                                popUpTo(rootDest) { inclusive = false }
                                                launchSingleTop = true
                                            }
                                        } else {
                                            navController.navigate("my_orders") {
                                                popUpTo(rootDest) { inclusive = false }
                                                launchSingleTop = true
                                            }
                                        }
                                    },
                                    onBrowseMarketplaceClick = {
                                        android.util.Log.i("NavAudit", "[payment_result:onBrowseMarketplaceClick] rootDest=$rootDest")
                                        navController.navigate(rootDest) {
                                            popUpTo(rootDest) { inclusive = false }
                                            launchSingleTop = true
                                        }
                                    },
                                    onRetryPaymentClick = {
                                        android.util.Log.i("NavAudit", "[payment_result:onRetryPaymentClick] orderId=$orderId")
                                        val encCrop = java.net.URLEncoder.encode("Produce", "UTF-8")
                                        navController.navigate("payhere_checkout/$orderId?amount=$amount&crop=$encCrop") {
                                            popUpTo(rootDest) { inclusive = false }
                                            launchSingleTop = true
                                        }
                                    },
                                    onBackClick = {
                                        android.util.Log.i("NavAudit", "[payment_result:onBackClick] navigating to my_orders")
                                        navController.navigate("my_orders") {
                                            popUpTo(rootDest) { inclusive = false }
                                            launchSingleTop = true
                                        }
                                    }
                                )
                            }

                            composable("farmer_profile/{farmerId}") { backStackEntry ->
                                val farmerId = backStackEntry.arguments?.getString("farmerId") ?: ""
                                val listing = selectedListing
                                val marketState by marketplaceViewModel.uiState.collectAsState()
                                val farmerListings = marketState.allListings.filter { it.farmerId == farmerId }
                                com.example.ui.screens.farmer.FarmerProfileScreen(
                                    farmerId = farmerId,
                                    farmerName = listing?.farmerFirstName ?: "Farmer Profile",
                                    districtName = listing?.cultivationDistrictName ?: "Sri Lanka",
                                    cityName = listing?.cultivationCityName,
                                    ratingAvg = listing?.ratingAvg ?: 4.8,
                                    ratingCount = listing?.ratingCount ?: 24,
                                    reliabilityScore = (listing?.reliabilityScore ?: 98.0).toInt(),
                                    completedOrders = listing?.completedOrders ?: 38,
                                    isTopFarmer = listing?.isTopFarmer ?: true,
                                    mainCrops = farmerListings.map { it.cropName }.distinct().ifEmpty { listOf("Fresh Produce", "Vegetables", "Spices") },
                                    listings = farmerListings,
                                    onBackClick = { navController.popBackStack() },
                                    onListingClick = { item ->
                                        selectedListing = item
                                        navController.navigate("listing_detail")
                                    },
                                    onChatClick = {
                                        if (listing != null) {
                                            chatListing = listing
                                            navController.navigate("chat_listing/${listing.id}")
                                        }
                                    }
                                )
                            }

                            composable("place_order") {
                                val rootDest = if (isFarmer) "dashboard" else "marketplace"
                                PlaceOrderScreen(
                                    viewModel = orderViewModel,
                                    districts = marketplaceViewModel.uiState.collectAsState().value.districts,
                                    currentUserId = userId,
                                    onBackClick = {
                                        android.util.Log.i("NavAudit", "[place_order:onBackClick] popBackStack | BackStack: ${navController.currentBackStack.value.map { it.destination.route }}")
                                        navController.popBackStack()
                                    },
                                    onOrderPlaced = { orderId ->
                                        android.util.Log.i("NavAudit", "[place_order:onOrderPlaced] orderId=$orderId rootDest=$rootDest")
                                        if (orderId.isNotBlank()) {
                                            navController.navigate("order_detail/$orderId") {
                                                popUpTo(rootDest) { inclusive = false }
                                                launchSingleTop = true
                                            }
                                        } else {
                                            navController.navigate("my_orders") {
                                                popUpTo(rootDest) { inclusive = false }
                                                launchSingleTop = true
                                            }
                                        }
                                    }
                                )
                            }

                            // 3. Orders
                            composable("my_orders") {
                                val orders by orderViewModel.buyerOrders.collectAsState()
                                val loading by orderViewModel.isLoadingOrders.collectAsState()

                                LaunchedEffect(Unit) {
                                    val currentId = userId ?: ""
                                    orderViewModel.loadBuyerOrders(currentId)
                                }

                                OrdersListScreen(
                                    orders = orders,
                                    isFarmer = false,
                                    isLoading = loading,
                                    onRefresh = {
                                        scope.launch {
                                            val currentId = userId ?: ""
                                            orderViewModel.loadBuyerOrders(currentId)
                                        }
                                    },
                                    onOrderClick = { order ->
                                        android.util.Log.i("NavAudit", "[my_orders:onOrderClick] orderId=${order.id}")
                                        navController.navigate("order_detail/${order.id}")
                                    },
                                    onPayNowClick = { order ->
                                        val encCrop = java.net.URLEncoder.encode(order.cropName, "UTF-8")
                                        android.util.Log.i("NavAudit", "[my_orders:onPayNowClick] orderId=${order.id}")
                                        navController.navigate("payhere_checkout/${order.id}?amount=${order.totalAmount}&crop=$encCrop") {
                                            launchSingleTop = true
                                        }
                                    },
                                    onCancelClick = { order ->
                                        orderViewModel.cancelOrderFromList(order.id, isFarmer = false, currentUserId = userId ?: "")
                                    }
                                )
                            }

                            composable("farmer_orders") {
                                val orders by orderViewModel.farmerOrders.collectAsState()
                                val loading by orderViewModel.isLoadingOrders.collectAsState()

                                LaunchedEffect(Unit) {
                                    val currentId = userId ?: ""
                                    orderViewModel.loadFarmerOrders(currentId)
                                }

                                OrdersListScreen(
                                    orders = orders,
                                    isFarmer = true,
                                    isLoading = loading,
                                    onRefresh = {
                                        scope.launch {
                                            val currentId = userId ?: ""
                                            orderViewModel.loadFarmerOrders(currentId)
                                        }
                                    },
                                    onOrderClick = { order ->
                                        navController.navigate("order_detail/${order.id}")
                                    },
                                    onCancelClick = { order ->
                                        orderViewModel.cancelOrderFromList(order.id, isFarmer = true, currentUserId = userId ?: "", reason = "Cancelled by farmer")
                                    }
                                )
                            }

                            composable(
                                route = "order_detail/{orderId}",
                                arguments = listOf(navArgument("orderId") { type = NavType.StringType })
                            ) { backStackEntry ->
                                val orderId = backStackEntry.arguments?.getString("orderId") ?: ""
                                OrderDetailScreen(
                                    orderId = orderId,
                                    currentUserId = userId ?: "",
                                    viewModel = orderViewModel,
                                    onBackClick = {
                                        android.util.Log.i("NavAudit", "[order_detail:onBackClick] popBackStack | BackStack: ${navController.currentBackStack.value.map { it.destination.route }}")
                                        val canPop = navController.popBackStack()
                                        if (!canPop) {
                                            val rootDest = if (isFarmer) "dashboard" else "marketplace"
                                            navController.navigate("my_orders") {
                                                popUpTo(rootDest) { inclusive = false }
                                                launchSingleTop = true
                                            }
                                        }
                                    },
                                    onChatClick = { oId, status ->
                                        val existingConv = conversations.find { it.orderId == oId || it.conversationId == oId }
                                        chatCounterpartName = existingConv?.counterpartName
                                        navController.navigate("chat/$oId/$status")
                                    },
                                    onLaunchPayHere = { payReq ->
                                        val oId = if (orderId.isNotBlank()) orderId else payReq.orderId.ifBlank { payReq.payhereOrderId }
                                        val encCrop = java.net.URLEncoder.encode(payReq.itemDescription.ifBlank { "Produce" }, "UTF-8")
                                        android.util.Log.i("NavAudit", "[order_detail:onLaunchPayHere] oId=$oId amount=${payReq.amount}")
                                        navController.navigate("payhere_checkout/$oId?amount=${payReq.amount}&crop=$encCrop") {
                                            launchSingleTop = true
                                        }
                                    }
                                )
                            }

                            // 4. Chat & Messages
                            composable("messages") {
                                val isSelling = (appMode == AppMode.SELLING)
                                val currentUserId = userId ?: ""
                                MessagesScreen(
                                    userId = currentUserId,
                                    isFarmer = isSelling,
                                    chatViewModel = chatViewModel,
                                    onConversationClick = { conv ->
                                        val foundListing = marketplaceViewModel.uiState.value.allListings.find { it.id == conv.listingId }
                                        val targetListing = foundListing ?: ListingItem(
                                            id = conv.listingId,
                                            farmerId = if (isSelling) currentUserId else conv.counterpartId,
                                            cropName = conv.cropName,
                                            pricePerKg = conv.pricePerKg,
                                            quantityAvailable = 100.0,
                                            minOrderKg = 1.0,
                                            harvestDate = "2026-10-01",
                                            photos = listOfNotNull(conv.listingThumbnail),
                                            farmerFirstName = if (isSelling) "Me" else conv.counterpartName
                                        )
                                        chatListing = targetListing
                                        selectedListing = targetListing
                                        chatCounterpartName = conv.counterpartName
                                        navController.navigate("chat/${conv.conversationId}/${conv.status}")
                                    },
                                    onBackClick = {
                                        navController.navigate(if (isSelling) "my_listings" else "marketplace")
                                    }
                                )
                            }

                            composable(
                                route = "chat/{orderId}/{status}",
                                arguments = listOf(
                                    navArgument("orderId") { type = NavType.StringType },
                                    navArgument("status") { type = NavType.StringType }
                                )
                            ) { backStackEntry ->
                                val orderId = backStackEntry.arguments?.getString("orderId") ?: ""
                                val status = backStackEntry.arguments?.getString("status") ?: ""
                                val isSelling = (appMode == AppMode.SELLING)
                                val currentUserId = userId ?: ""
                                ChatScreen(
                                    orderId = orderId,
                                    orderStatus = status,
                                    currentUserId = currentUserId,
                                    isFarmerView = isSelling,
                                    listing = chatListing ?: selectedListing,
                                    viewModel = chatViewModel,
                                    counterpartName = chatCounterpartName,
                                    onRequestOrderFromChat = { l ->
                                        selectedListing = l
                                        orderViewModel.initPlaceOrder(l, userDistrictId, 1)
                                        navController.navigate("place_order")
                                    },
                                    onBackClick = { navController.popBackStack() }
                                )
                            }

                            composable(
                                route = "chat_listing/{listingId}",
                                arguments = listOf(navArgument("listingId") { type = NavType.StringType })
                            ) { backStackEntry ->
                                val listingId = backStackEntry.arguments?.getString("listingId") ?: ""
                                val listing = chatListing ?: marketplaceViewModel.uiState.value.allListings.find { it.id == listingId }
                                val isSelling = (appMode == AppMode.SELLING)
                                val currentUserId = userId ?: ""
                                val displayCp = chatCounterpartName ?: if (isSelling) "Buyer" else listing?.farmerFirstName ?: "Farmer"
                                ChatScreen(
                                    orderId = "listing_$listingId",
                                    orderStatus = "active",
                                    currentUserId = currentUserId,
                                    isFarmerView = isSelling,
                                    listing = listing,
                                    viewModel = chatViewModel,
                                    counterpartName = displayCp,
                                    onRequestOrderFromChat = { l ->
                                        selectedListing = l
                                        orderViewModel.initPlaceOrder(l, userDistrictId, 1)
                                        navController.navigate("place_order")
                                    },
                                    onBackClick = { navController.popBackStack() }
                                )
                            }

                            // 5. Selling Mode Screens
                            composable("dashboard") {
                                FarmerDashboardScreen(
                                    farmerId = userId ?: "farmer_me",
                                    viewModel = farmerViewModel,
                                    onAddListingClick = {
                                        selectedListing = null
                                        navController.navigate("add_edit_listing")
                                    }
                                )
                            }

                            composable("my_listings") {
                                MyListingsScreen(
                                    farmerId = userId ?: "farmer_me",
                                    viewModel = farmerViewModel,
                                    onAddListingClick = {
                                        selectedListing = null
                                        navController.navigate("add_edit_listing")
                                    },
                                    onEditListingClick = { listing ->
                                        selectedListing = listing
                                        navController.navigate("add_edit_listing")
                                    }
                                )
                            }

                            composable("add_edit_listing") {
                                AddEditListingScreen(
                                    farmerId = userId ?: "farmer_me",
                                    existingListing = selectedListing,
                                    viewModel = farmerViewModel,
                                    onBackClick = { navController.popBackStack() },
                                    onSaved = {
                                        marketplaceViewModel.refreshMarketplace()
                                        navController.popBackStack()
                                    }
                                )
                            }

                            // 6. Notifications & Profile
                            composable("notifications") {
                                DisposableEffect(Unit) {
                                    onDispose {
                                        scope.launch {
                                            if (!userId.isNullOrBlank()) {
                                                val notifs = app.repository.getNotifications(userId!!).getOrNull() ?: emptyList()
                                                unreadNotificationsCount = notifs.count { it.readAt == null }
                                            }
                                        }
                                    }
                                }
                                NotificationsScreen(
                                    userId = userId ?: "buyer_me",
                                    repository = app.repository,
                                    onOrderClick = { orderId ->
                                        navController.navigate("order_detail/$orderId")
                                    }
                                )
                            }

                            composable("profile") {
                                ProfileScreen(
                                    userId = userId ?: "buyer_me",
                                    viewModel = profileViewModel,
                                    onBecomeFarmerClick = {
                                        profileViewModel.openFarmerRegistrationDialog()
                                    },
                                    onLoggedOut = {
                                        authViewModel.resetAuth()
                                        scope.launch {
                                            app.sessionManager.clearSession()
                                            navController.navigate("login") {
                                                popUpTo(0) { inclusive = true }
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun launchPayHerePayment(req: PayHerePaymentRequest) {
        pendingPayingOrderId = req.payhereOrderId.substringBefore("-")
        try {
            if (BuildConfig.PAYHERE_SANDBOX) {
                PHConfigs.setBaseUrl(PHConfigs.SANDBOX_URL)
            } else {
                PHConfigs.setBaseUrl(PHConfigs.LIVE_URL)
            }

            val initRequest = InitRequest().apply {
                merchantId = req.merchantId
                currency = req.currency
                amount = req.amount.toDoubleOrNull() ?: 0.0
                orderId = req.payhereOrderId
                itemsDescription = req.itemDescription
                notifyUrl = req.notifyUrl
                customer.firstName = req.firstName.ifBlank { req.buyerFirstName }
                customer.lastName = req.lastName.ifBlank { "Customer" }
                customer.email = req.email.ifBlank { "noreply@agromarket.lk" }
                customer.phone = req.phone.ifBlank { "0771234567" }
                customer.address.address = req.address.ifBlank { "Sri Lanka" }
                customer.address.city = req.city.ifBlank { "Colombo" }
                customer.address.country = req.country.ifBlank { "Sri Lanka" }
                items.add(Item(null, req.itemDescription, 1, req.amount.toDoubleOrNull() ?: 0.0))
            }

            val intent = Intent(this, PHMainActivity::class.java).apply {
                putExtra(PHConstants.INTENT_EXTRA_DATA, initRequest)
            }
            payHereLauncher.launch(intent)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to launch PayHere SDK", e)
            Toast.makeText(this, "Could not start PayHere checkout: ${e.message}", Toast.LENGTH_LONG).show()
            val orderId = pendingPayingOrderId ?: ""
            if (orderId.isNotBlank()) {
                activeOrderViewModel?.onPaymentSdkResult(Activity.RESULT_CANCELED, orderId)
            }
        }
    }
}
