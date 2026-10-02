package com.example.ui.screens.payment

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.util.Log
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.payment.PayHerePaymentService
import com.example.data.repository.AgroMarketRepository
import com.example.ui.components.PrimaryButton
import com.example.ui.components.formatLkr
import com.example.ui.theme.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class PaymentStage {
    LOADING_FORM,
    WEBVIEW_CHECKOUT,
    CARD_PROCESSING,
    CONFIRMING,
    CONFIRMED,
    FAILED,
    CANCELLED
}

private const val TAG = "PayHereCheckout"
private const val CHROME_MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PayHereCheckoutScreen(
    orderId: String,
    totalAmount: Double,
    cropSummary: String,
    paymentService: PayHerePaymentService,
    repository: AgroMarketRepository,
    onBackClick: () -> Unit,
    onPaymentSuccess: (orderId: String, amount: Double) -> Unit,
    onPaymentFailed: (orderId: String, reason: String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf(PaymentStage.LOADING_FORM) }
    var htmlContent by remember { mutableStateOf<String?>(null) }
    var webViewLoading by remember { mutableStateOf(true) }
    var loadProgress by remember { mutableIntStateOf(0) }
    var showCancelDialog by remember { mutableStateOf(false) }
    var pollAttempts by remember { mutableIntStateOf(0) }
    var failureReason by remember { mutableStateOf("Payment could not be loaded") }
    var isTimedOut by remember { mutableStateOf(false) }
    var sessionKey by remember { mutableIntStateOf(0) }

    // Prepare HTML form
    fun loadPaymentSession() {
        stage = PaymentStage.LOADING_FORM
        isTimedOut = false
        failureReason = "Payment could not be loaded"
        scope.launch {
            try {
                val formData = paymentService.preparePaymentForm(
                    orderId = orderId,
                    fallbackAmount = totalAmount,
                    fallbackCropName = cropSummary
                )
                htmlContent = paymentService.generateHtmlPostForm(formData)
                stage = PaymentStage.WEBVIEW_CHECKOUT
                sessionKey++
            } catch (e: Exception) {
                stage = PaymentStage.FAILED
                val rawMsg = e.message ?: "Failed to initialize payment form"
                failureReason = com.example.data.payment.parsePaymentErrorMessage(rawMsg)
            }
        }
    }

    LaunchedEffect(orderId) {
        loadPaymentSession()
    }

    // Timeout watchdog for initial page load
    LaunchedEffect(stage, sessionKey) {
        if (stage == PaymentStage.WEBVIEW_CHECKOUT && webViewLoading) {
            delay(35000)
            if (webViewLoading && stage == PaymentStage.WEBVIEW_CHECKOUT) {
                isTimedOut = true
                stage = PaymentStage.FAILED
                failureReason = "Connection timed out while loading PayHere payment gateway. Please check your internet or retry."
            }
        }
    }

    // Polling when stage == CONFIRMING
    LaunchedEffect(stage) {
        if (stage == PaymentStage.CONFIRMING) {
            var confirmed = false
            for (i in 1..30) {
                pollAttempts = i
                delay(2000)
                val isPaid = paymentService.pollPaymentStatus(orderId, maxRetries = 1, delayMillis = 100)
                if (isPaid) {
                    confirmed = true
                    stage = PaymentStage.CONFIRMED
                    delay(800)
                    onPaymentSuccess(orderId, totalAmount)
                    break
                }
            }
            if (!confirmed && stage == PaymentStage.CONFIRMING) {
                // Check once more directly from repo
                val finalOrder = repository.getOrderById(orderId).getOrNull()
                if (finalOrder != null && (finalOrder.status == "paid" || finalOrder.status == "ready" || finalOrder.status == "completed")) {
                    stage = PaymentStage.CONFIRMED
                    delay(800)
                    onPaymentSuccess(orderId, totalAmount)
                } else {
                    stage = PaymentStage.FAILED
                    failureReason = "Payment was submitted, but verification is still pending. Your order remains awaiting payment in My Orders."
                }
            }
        }
    }

    BackHandler {
        if (stage == PaymentStage.WEBVIEW_CHECKOUT || stage == PaymentStage.CARD_PROCESSING) {
            showCancelDialog = true
        } else {
            onBackClick()
        }
    }

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            icon = { Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Cancel Payment?", fontWeight = FontWeight.Bold) },
            text = { Text("Your order request is saved, but payment will not be completed. You can pay later anytime from My Orders within 30 minutes.") },
            confirmButton = {
                Button(
                    onClick = {
                        showCancelDialog = false
                        stage = PaymentStage.CANCELLED
                        onPaymentFailed(orderId, "Payment cancelled by user")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Yes, Leave Payment")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) {
                    Text("Continue Payment")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("PayHere Checkout", fontWeight = FontWeight.Bold)
                        Text(
                            text = "Amount: ${formatLkr(totalAmount)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = AgroGreenPrimary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (stage == PaymentStage.WEBVIEW_CHECKOUT || stage == PaymentStage.CARD_PROCESSING) {
                                showCancelDialog = true
                            } else {
                                onBackClick()
                            }
                        },
                        modifier = Modifier.testTag("payhere_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = "SSL Secure",
                            tint = AgroGreenPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "Sandbox",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = AgroGreenPrimary
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (stage) {
                PaymentStage.LOADING_FORM -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(AgroSpacing.xl)
                        ) {
                            CircularProgressIndicator(color = AgroGreenPrimary)
                            Spacer(modifier = Modifier.height(AgroSpacing.md))
                            Text("Securing PayHere payment session...", fontWeight = FontWeight.Medium)
                            Spacer(modifier = Modifier.height(AgroSpacing.xs))
                            Text(
                                "Generating 256-bit signed payment token...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                PaymentStage.WEBVIEW_CHECKOUT, PaymentStage.CARD_PROCESSING -> {
                    if (htmlContent != null) {
                        key(sessionKey) {
                            AndroidView(
                                factory = { ctx ->
                                    WebView(ctx).apply {
                                        // Standard WebView Settings
                                        settings.apply {
                                            javaScriptEnabled = true
                                            domStorageEnabled = true
                                            databaseEnabled = true
                                            loadWithOverviewMode = true
                                            useWideViewPort = true
                                            setSupportZoom(true)
                                            builtInZoomControls = false
                                            displayZoomControls = false
                                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                            userAgentString = CHROME_MOBILE_UA
                                            cacheMode = WebSettings.LOAD_DEFAULT
                                        }

                                        // Crucial for 3D Secure verification & PayHere iframe sessions
                                        val cookieManager = CookieManager.getInstance()
                                        cookieManager.setAcceptCookie(true)
                                        cookieManager.setAcceptThirdPartyCookies(this, true)

                                        webChromeClient = object : WebChromeClient() {
                                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                                super.onProgressChanged(view, newProgress)
                                                loadProgress = newProgress
                                                if (newProgress >= 90) {
                                                    webViewLoading = false
                                                }
                                            }
                                        }

                                        webViewClient = object : WebViewClient() {
                                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                                super.onPageStarted(view, url, favicon)
                                                webViewLoading = true
                                                Log.i(TAG, "[WebView onPageStarted] url: $url")
                                                url?.let { checkUrlNavigation(it) }
                                            }

                                            override fun onPageFinished(view: WebView?, url: String?) {
                                                super.onPageFinished(view, url)
                                                webViewLoading = false
                                                Log.i(TAG, "[WebView onPageFinished] url: $url")
                                                url?.let { checkUrlNavigation(it) }
                                            }

                                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                                val url = request?.url?.toString() ?: ""
                                                Log.i(TAG, "[WebView shouldOverrideUrlLoading] url: $url")
                                                return checkUrlNavigation(url)
                                            }

                                            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                                super.onReceivedError(view, request, error)
                                                val isForMainFrame = request?.isForMainFrame ?: true
                                                val desc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) error?.description?.toString() else "Network error"
                                                val errorCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) error?.errorCode ?: -1 else -1
                                                Log.e(TAG, "[WebView onReceivedError] (mainFrame=$isForMainFrame, code=$errorCode): $desc url=${request?.url}")
                                                if (isForMainFrame) {
                                                    stage = PaymentStage.FAILED
                                                    failureReason = "Couldn't load payment: $desc ($errorCode)"
                                                }
                                            }

                                            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                                                super.onReceivedHttpError(view, request, errorResponse)
                                                val isForMainFrame = request?.isForMainFrame ?: false
                                                val statusCode = errorResponse?.statusCode ?: 0
                                                val reason = errorResponse?.reasonPhrase ?: "HTTP Error"
                                                Log.w(TAG, "[WebView onReceivedHttpError] (mainFrame=$isForMainFrame, status=$statusCode): $reason url=${request?.url}")
                                                if (isForMainFrame && statusCode >= 400) {
                                                    stage = PaymentStage.FAILED
                                                    failureReason = "Couldn't load payment: HTTP $statusCode ($reason)"
                                                }
                                            }

                                            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                                                Log.e(TAG, "[WebView onReceivedSslError] error: $error url=${error?.url}")
                                                handler?.cancel()
                                                stage = PaymentStage.FAILED
                                                failureReason = "Couldn't load payment: SSL verification failed (${error?.primaryError})"
                                            }

                                            private fun checkUrlNavigation(url: String): Boolean {
                                                val returnUrl = PayHerePaymentService.RETURN_URL
                                                val cancelUrl = PayHerePaymentService.CANCEL_URL

                                                // 1. Exact return URL match -> Confirmation
                                                if (url.startsWith(returnUrl) || url.contains("checkout.agromarket.lk/payhere/return")) {
                                                    Log.i(TAG, "[Payment Checkout] Exact return URL detected -> Transitioning to CONFIRMING")
                                                    stage = PaymentStage.CONFIRMING
                                                    return true
                                                }

                                                // 2. Exact cancel URL match ONLY -> Cancelled
                                                if (url.startsWith(cancelUrl) || url.contains("checkout.agromarket.lk/payhere/cancel")) {
                                                    Log.i(TAG, "[Payment Checkout] Exact cancel URL detected -> Transitioning to CANCELLED")
                                                    stage = PaymentStage.CANCELLED
                                                    onPaymentFailed(orderId, "Payment was cancelled")
                                                    return true
                                                }

                                                // 3. Detect submission to payment processor / 3DS
                                                if (url.contains("/pay/submit") || url.contains("/pay/process") || url.contains("/card") || url.contains("3dsecure")) {
                                                    Log.i(TAG, "[Payment Checkout] Payment processor / 3DS detected -> CARD_PROCESSING")
                                                    stage = PaymentStage.CARD_PROCESSING
                                                }

                                                // 4. PayHere unauthorized domain or hash mismatch detection
                                                if (url.contains("unauthorized_domain") || url.contains("domain_not_allowed")) {
                                                    Log.e(TAG, "[Payment Checkout] Domain not allowed by PayHere Sandbox")
                                                    stage = PaymentStage.FAILED
                                                    failureReason = "Domain not authorized in PayHere Sandbox. Please add 'checkout.agromarket.lk' in PayHere Dashboard -> Integrations -> Domains."
                                                    return true
                                                }
                                                if (url.contains("hash_mismatch") || url.contains("invalid_hash")) {
                                                    Log.e(TAG, "[Payment Checkout] Hash mismatch error from PayHere")
                                                    stage = PaymentStage.FAILED
                                                    failureReason = "PayHere hash validation mismatch. Check merchant secret and credentials."
                                                    return true
                                                }

                                                return false
                                            }
                                        }

                                        // Load HTML POST form with fixed HTTPS base URL
                                        loadDataWithBaseURL(
                                            PayHerePaymentService.BASE_DOMAIN,
                                            htmlContent!!,
                                            "text/html",
                                            "UTF-8",
                                            null
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("payhere_webview")
                            )
                        }
                    }

                    // Progress indicator for page load
                    if (webViewLoading) {
                        LinearProgressIndicator(
                            progress = { (loadProgress / 100f).coerceIn(0.05f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .align(Alignment.TopCenter),
                            color = AgroGreenPrimary,
                            trackColor = AgroGreenContainer
                        )
                    }

                    // Card submission overlay
                    if (stage == PaymentStage.CARD_PROCESSING && webViewLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surface,
                                shadowElevation = 8.dp,
                                modifier = Modifier.padding(32.dp)
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(24.dp)
                                ) {
                                    CircularProgressIndicator(color = AgroGreenPrimary)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        "Secure payment with PayHere",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        "Processing transaction with card network...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }

                PaymentStage.CONFIRMING -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(AgroSpacing.xl),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                color = AgroGreenPrimary,
                                strokeWidth = 4.dp,
                                modifier = Modifier.size(56.dp)
                            )

                            Spacer(modifier = Modifier.height(AgroSpacing.lg))

                            Text(
                                text = "Confirming payment...",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Spacer(modifier = Modifier.height(AgroSpacing.xs))

                            Text(
                                text = "Awaiting webhook confirmation from PayHere (Attempt $pollAttempts/30)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(AgroSpacing.xs))

                            Text(
                                text = "Funds are securely placed in escrow.",
                                style = MaterialTheme.typography.bodySmall,
                                color = AgroGreenPrimary,
                                fontWeight = FontWeight.SemiBold
                            )

                            Spacer(modifier = Modifier.height(AgroSpacing.xl))

                            OutlinedButton(
                                onClick = {
                                    stage = PaymentStage.WEBVIEW_CHECKOUT
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Return to Payment Page")
                            }
                        }
                    }
                }

                PaymentStage.CONFIRMED -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Surface(shape = CircleShape, color = AgroGreenContainer, modifier = Modifier.size(76.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.Check, contentDescription = null, tint = AgroGreenPrimary, modifier = Modifier.size(44.dp))
                                }
                            }
                            Spacer(modifier = Modifier.height(AgroSpacing.md))
                            Text("Payment Confirmed!", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                            Spacer(modifier = Modifier.height(AgroSpacing.xs))
                            Text(
                                "${formatLkr(totalAmount)} verified successfully.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                PaymentStage.FAILED -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(AgroSpacing.xl),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val isAwaitingFarmer = failureReason.contains("awaiting farmer", ignoreCase = true)

                            Surface(
                                shape = CircleShape,
                                color = if (isAwaitingFarmer) AgroGreenContainer else MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier.size(68.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        if (isAwaitingFarmer) Icons.Filled.Schedule else Icons.Filled.ErrorOutline,
                                        contentDescription = null,
                                        tint = if (isAwaitingFarmer) AgroGreenPrimary else MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(40.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(AgroSpacing.md))
                            Text(
                                if (isAwaitingFarmer) "Awaiting Farmer Acceptance" else "Couldn't load payment",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(AgroSpacing.sm))
                            Text(
                                text = failureReason,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                            Spacer(modifier = Modifier.height(AgroSpacing.xl))
                            if (isAwaitingFarmer) {
                                PrimaryButton(
                                    text = "View in My Orders",
                                    onClick = onBackClick,
                                    leadingIcon = Icons.Filled.LocalShipping,
                                    minHeight = 48.dp
                                )
                            } else {
                                PrimaryButton(
                                    text = "Retry Payment",
                                    onClick = { loadPaymentSession() },
                                    leadingIcon = Icons.Filled.Refresh,
                                    minHeight = 48.dp
                                )
                                Spacer(modifier = Modifier.height(AgroSpacing.sm))
                                OutlinedButton(
                                    onClick = {
                                        onPaymentFailed(orderId, failureReason)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Back to Orders")
                                }
                            }
                        }
                    }
                }

                PaymentStage.CANCELLED -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(AgroSpacing.xl),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.size(68.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Filled.Cancel,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(40.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(AgroSpacing.md))
                            Text(
                                "Payment Cancelled",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(AgroSpacing.xs))
                            Text(
                                "You cancelled the payment transaction. The order remains saved in 'My Orders' awaiting payment.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(AgroSpacing.xl))
                            PrimaryButton(
                                text = "Try Again",
                                onClick = { loadPaymentSession() },
                                leadingIcon = Icons.Filled.Refresh,
                                minHeight = 48.dp
                            )
                            Spacer(modifier = Modifier.height(AgroSpacing.sm))
                            OutlinedButton(
                                onClick = onBackClick,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Return to My Orders")
                            }
                        }
                    }
                }
            }
        }
    }
}
