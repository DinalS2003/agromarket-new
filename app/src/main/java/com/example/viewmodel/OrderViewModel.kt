package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.models.*
import com.example.data.repository.AgroMarketRepository
import com.example.ui.components.toUserFriendlyMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

data class PlaceOrderUiState(
    val listing: ListingItem? = null,
    val quantityKg: String = "",
    val quantityError: String? = null,
    val deliveryMethod: String = "buyer_arranged", // 'buyer_arranged' | 'farmer_delivery'
    val requestedDate: String = "",
    val deliveryDistrictId: Int = 1,
    val deliveryCityId: Int = 1,
    val deliveryAddress: String = "",
    val deliveryCities: List<City> = emptyList(),
    val subtotal: Double = 0.0,
    val isSubmitting: Boolean = false,
    val orderCreatedId: String? = null,
    val errorMessage: String? = null
)

data class OrderDetailUiState(
    val order: OrderItem? = null,
    val privateDetails: OrderPrivateDetails? = null,
    val deliveryDistrictName: String? = null,
    val deliveryCityName: String? = null,
    val farmerName: String? = null,
    val buyerName: String? = null,
    val listingItem: ListingItem? = null,
    val remainingSeconds: Long = 0,
    val isLoading: Boolean = false,
    val actionLoading: Boolean = false,
    val actionError: String? = null,
    val actionSuccessMessage: String? = null,
    // Accept Sheet
    val showAcceptSheet: Boolean = false,
    val deliveryMode: String = "own_transport",
    val deliveryFeeInput: String = "0.00",
    val pickupLandmarkInput: String = "",
    // Cancel Dialog
    val showCancelDialog: Boolean = false,
    val cancelReasonInput: String = "",
    // Reject Dialog
    val showRejectDialog: Boolean = false,
    val rejectReasonInput: String = "",
    // Dispute Dialog
    val showDisputeDialog: Boolean = false,
    val disputeReasonInput: String = "",
    // Review Dialog
    val showReviewDialog: Boolean = false,
    val ratingInput: Int = 5,
    val reviewCommentInput: String = "",
    // Payment
    val payHereRequest: PayHerePaymentRequest? = null
)

class OrderViewModel(
    private val repository: AgroMarketRepository
) : ViewModel() {

    private val _placeOrderState = MutableStateFlow(PlaceOrderUiState())
    val placeOrderState: StateFlow<PlaceOrderUiState> = _placeOrderState.asStateFlow()

    private val _buyerOrders = MutableStateFlow<List<OrderItem>>(emptyList())
    val buyerOrders: StateFlow<List<OrderItem>> = _buyerOrders.asStateFlow()

    private val _farmerOrders = MutableStateFlow<List<OrderItem>>(emptyList())
    val farmerOrders: StateFlow<List<OrderItem>> = _farmerOrders.asStateFlow()

    private val _detailState = MutableStateFlow(OrderDetailUiState())
    val detailState: StateFlow<OrderDetailUiState> = _detailState.asStateFlow()

    private val _isLoadingOrders = MutableStateFlow(false)
    val isLoadingOrders: StateFlow<Boolean> = _isLoadingOrders.asStateFlow()

    private var countdownJob: Job? = null

    fun initPlaceOrder(listing: ListingItem, defaultDistrictId: Int, defaultCityId: Int, initialQty: Double? = null) {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val initialDate = if (listing.harvestDate > todayStr) listing.harvestDate else todayStr
        val defaultQty = (initialQty ?: listing.minOrderKg).coerceIn(listing.minOrderKg, maxOf(listing.minOrderKg, listing.quantityAvailable))

        _placeOrderState.value = PlaceOrderUiState(
            listing = listing,
            quantityKg = defaultQty.toString(),
            requestedDate = initialDate,
            deliveryDistrictId = defaultDistrictId,
            deliveryCityId = defaultCityId,
            subtotal = defaultQty * listing.pricePerKg
        )
        loadDeliveryCities(defaultDistrictId)
    }

    fun onQuantityChange(qtyStr: String) {
        val listing = _placeOrderState.value.listing ?: return
        val qty = qtyStr.toDoubleOrNull()
        val minOrder = maxOf(1.0, minOf(listing.minOrderKg, listing.quantityAvailable))
        val minStr = if (minOrder % 1.0 == 0.0) minOrder.toInt().toString() else minOrder.toString()
        val maxStr = if (listing.quantityAvailable % 1.0 == 0.0) listing.quantityAvailable.toInt().toString() else listing.quantityAvailable.toString()

        val error = when {
            qty == null -> "Please enter valid quantity"
            qty < 1.0 -> "Minimum order is 1 kg"
            qty < minOrder -> "Minimum order is $minStr kg"
            qty > listing.quantityAvailable -> "Only $maxStr kg available"
            else -> null
        }

        val subtotal = if (qty != null && qty > 0) qty * listing.pricePerKg else 0.0

        _placeOrderState.update {
            it.copy(
                quantityKg = qtyStr,
                quantityError = error,
                subtotal = subtotal
            )
        }
    }

    fun onDeliveryMethodChange(method: String) {
        _placeOrderState.update { it.copy(deliveryMethod = method) }
    }

    fun onRequestedDateChange(date: String) {
        _placeOrderState.update { it.copy(requestedDate = date) }
    }

    fun onDeliveryDistrictChange(districtId: Int) {
        _placeOrderState.update { it.copy(deliveryDistrictId = districtId) }
        loadDeliveryCities(districtId)
    }

    fun onDeliveryCityChange(cityId: Int) {
        _placeOrderState.update { it.copy(deliveryCityId = cityId) }
    }

    fun onDeliveryAddressChange(addr: String) {
        _placeOrderState.update { it.copy(deliveryAddress = addr) }
    }

    private fun loadDeliveryCities(districtId: Int) {
        viewModelScope.launch {
            val res = repository.getCities(districtId)
            res.onSuccess { cities ->
                _placeOrderState.update {
                    it.copy(
                        deliveryCities = cities,
                        deliveryCityId = cities.firstOrNull()?.id ?: 1
                    )
                }
            }
        }
    }

    fun clearErrorMessage() {
        _placeOrderState.update { it.copy(errorMessage = null) }
    }

    fun submitOrderRequest() {
        val state = _placeOrderState.value
        val listing = state.listing ?: return
        val qty = state.quantityKg.toDoubleOrNull()
        if (qty == null || qty <= 0) {
            _placeOrderState.update { it.copy(errorMessage = "Please enter a valid order quantity in kilograms (e.g. ${listing.minOrderKg} kg).") }
            return
        }

        if (state.quantityError != null) {
            _placeOrderState.update { it.copy(errorMessage = state.quantityError) }
            return
        }

        if (state.deliveryMethod == "farmer_delivery" && state.deliveryAddress.trim().isBlank()) {
            _placeOrderState.update { it.copy(errorMessage = "Please enter your street delivery address so the farmer can dispatch your harvest.") }
            return
        }

        if (state.requestedDate.isBlank()) {
            _placeOrderState.update { it.copy(errorMessage = "Please select your required delivery/pickup date.") }
            return
        }

        if (state.requestedDate < listing.harvestDate) {
            _placeOrderState.update { it.copy(errorMessage = "Required date cannot be earlier than harvest date (${listing.harvestDate}).") }
            return
        }

        viewModelScope.launch {
            val myId = repository.sessionManager.getUserId() ?: ""
            if (myId.isNotBlank() && listing.farmerId == myId) {
                _placeOrderState.update {
                    it.copy(
                        isSubmitting = false,
                        errorMessage = "You cannot place an order for your own produce."
                    )
                }
                return@launch
            }
            _placeOrderState.update { it.copy(isSubmitting = true, errorMessage = null) }
            val res = repository.createOrderRequest(
                listingId = listing.id,
                quantityKg = qty,
                deliveryMethod = state.deliveryMethod,
                requestedDate = state.requestedDate,
                deliveryDistrictId = if (state.deliveryMethod == "farmer_delivery") state.deliveryDistrictId else null,
                deliveryCityId = if (state.deliveryMethod == "farmer_delivery") state.deliveryCityId else null,
                deliveryAddress = if (state.deliveryMethod == "farmer_delivery") state.deliveryAddress else null
            )

            res.onSuccess { orderId ->
                _placeOrderState.update {
                    it.copy(isSubmitting = false, orderCreatedId = orderId)
                }
            }.onFailure { err ->
                _placeOrderState.update {
                    it.copy(
                        isSubmitting = false,
                        errorMessage = err.toUserFriendlyMessage("Unable to send order request. Please check your network and try again.")
                    )
                }
            }
        }
    }

    fun loadBuyerOrders(userId: String) {
        viewModelScope.launch {
            _isLoadingOrders.value = true
            val res = repository.getBuyerOrders(userId)
            res.onSuccess { list ->
                _buyerOrders.value = list
            }
            _isLoadingOrders.value = false
        }
    }

    fun loadFarmerOrders(farmerId: String) {
        viewModelScope.launch {
            _isLoadingOrders.value = true
            val res = repository.getFarmerOrders(farmerId)
            res.onSuccess { list ->
                _farmerOrders.value = list
            }
            _isLoadingOrders.value = false
        }
    }

    fun loadOrderDetail(orderId: String) {
        viewModelScope.launch {
            if (_detailState.value.order?.id != orderId) {
                _detailState.update { it.copy(order = null, isLoading = true, actionError = null, actionSuccessMessage = null) }
            } else {
                _detailState.update { it.copy(isLoading = true, actionError = null, actionSuccessMessage = null) }
            }

            val orderRes = repository.getOrderById(orderId)
            orderRes.onSuccess { order ->
                var distName: String? = null
                var cityName: String? = null
                if (order.deliveryDistrictId != null) {
                    val districts = repository.getDistricts().getOrDefault(emptyList())
                    distName = districts.find { it.id == order.deliveryDistrictId }?.name
                    val cities = repository.getCities(order.deliveryDistrictId).getOrDefault(emptyList())
                    cityName = cities.find { it.id == order.deliveryCityId }?.name
                }

                val farmerProfile = try { repository.getProfile(order.farmerId).getOrNull() } catch (_: Exception) { null }
                val buyerProfile = try { repository.getProfile(order.buyerId).getOrNull() } catch (_: Exception) { null }
                val listing = try { repository.getListingById(order.listingId).getOrNull() } catch (_: Exception) { null }

                _detailState.update {
                    it.copy(
                        order = order,
                        deliveryDistrictName = distName,
                        deliveryCityName = cityName,
                        farmerName = farmerProfile?.fullName,
                        buyerName = buyerProfile?.fullName,
                        listingItem = listing,
                        isLoading = false
                    )
                }
                startCountdownTimer(order.expiresAt)

                // Fetch private details
                val privRes = repository.getOrderPrivateDetails(orderId)
                privRes.onSuccess { priv ->
                    _detailState.update { it.copy(privateDetails = priv) }
                }
            }.onFailure { err ->
                _detailState.update { it.copy(isLoading = false, actionError = err.toUserFriendlyMessage("Unable to load order details.")) }
            }
        }
    }

    private fun startCountdownTimer(expiresAt: String?) {
        countdownJob?.cancel()
        if (expiresAt.isNullOrEmpty()) return

        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        try {
            val date = sdf.parse(expiresAt) ?: return
            val targetTime = date.time

            countdownJob = viewModelScope.launch {
                while (true) {
                    val remaining = (targetTime - System.currentTimeMillis()) / 1000
                    if (remaining <= 0) {
                        _detailState.update { it.copy(remainingSeconds = 0) }
                        break
                    }
                    _detailState.update { it.copy(remainingSeconds = remaining) }
                    delay(1000)
                }
            }
        } catch (e: Exception) {
            // Ignore parse exception
        }
    }

    // Farmer Action Handlers
    fun openAcceptSheet(defaultLandmark: String?) {
        _detailState.update {
            it.copy(
                showAcceptSheet = true,
                pickupLandmarkInput = defaultLandmark ?: "",
                deliveryFeeInput = "0.00",
                actionError = null
            )
        }
    }

    fun closeAcceptSheet() {
        _detailState.update { it.copy(showAcceptSheet = false) }
    }

    fun onDeliveryModeChange(mode: String) {
        _detailState.update { it.copy(deliveryMode = mode) }
    }

    fun onDeliveryFeeChange(fee: String) {
        _detailState.update { it.copy(deliveryFeeInput = fee) }
    }

    fun onPickupLandmarkChange(landmark: String) {
        _detailState.update { it.copy(pickupLandmarkInput = landmark) }
    }

    fun confirmAcceptOrder(orderId: String) {
        val state = _detailState.value
        val order = state.order ?: return
        val fee = state.deliveryFeeInput.toDoubleOrNull() ?: 0.0

        if (order.deliveryMethod == "buyer_arranged" && state.pickupLandmarkInput.trim().isBlank()) {
            _detailState.update {
                it.copy(
                    actionError = "Pickup landmark is required so the buyer knows where to collect the order. Please enter a landmark location."
                )
            }
            return
        }

        if (order.deliveryMethod == "farmer_delivery" && state.deliveryMode !in listOf("own_transport", "pickme")) {
            _detailState.update {
                it.copy(
                    actionError = "Please select a delivery method (My own transport or PickMe)."
                )
            }
            return
        }

        if (order.deliveryMethod == "farmer_delivery" && fee < 0) {
            _detailState.update {
                it.copy(
                    actionError = "Delivery fee cannot be negative."
                )
            }
            return
        }

        viewModelScope.launch {
            _detailState.update { it.copy(actionLoading = true, actionError = null) }
            val res = repository.farmerAcceptOrder(
                orderId = orderId,
                deliveryMode = if (order.deliveryMethod == "farmer_delivery") state.deliveryMode else null,
                deliveryFee = if (order.deliveryMethod == "farmer_delivery") fee else 0.0,
                landmark = if (order.deliveryMethod == "buyer_arranged") state.pickupLandmarkInput.trim() else null
            )

            res.onSuccess {
                _detailState.update {
                    it.copy(
                        actionLoading = false,
                        showAcceptSheet = false,
                        actionSuccessMessage = "Order accepted! Awaiting buyer payment."
                    )
                }
                loadOrderDetail(orderId)
            }.onFailure { err ->
                _detailState.update { it.copy(actionLoading = false, actionError = err.toUserFriendlyMessage("Unable to accept order.")) }
            }
        }
    }

    fun openRejectDialog() {
        _detailState.update { it.copy(showRejectDialog = true, rejectReasonInput = "") }
    }

    fun closeRejectDialog() {
        _detailState.update { it.copy(showRejectDialog = false) }
    }

    fun onRejectReasonChange(reason: String) {
        _detailState.update { it.copy(rejectReasonInput = reason) }
    }

    fun confirmRejectOrder(orderId: String) {
        viewModelScope.launch {
            _detailState.update { it.copy(actionLoading = true, actionError = null) }
            val res = repository.farmerRejectOrder(orderId, _detailState.value.rejectReasonInput)
            res.onSuccess {
                _detailState.update {
                    it.copy(actionLoading = false, showRejectDialog = false, actionSuccessMessage = "Order rejected.")
                }
                loadOrderDetail(orderId)
            }.onFailure { err ->
                _detailState.update { it.copy(actionLoading = false, actionError = err.toUserFriendlyMessage("Unable to reject order.")) }
            }
        }
    }

    fun openCancelDialog() {
        _detailState.update { it.copy(showCancelDialog = true, cancelReasonInput = "") }
    }

    fun closeCancelDialog() {
        _detailState.update { it.copy(showCancelDialog = false) }
    }

    fun onCancelReasonChange(reason: String) {
        _detailState.update { it.copy(cancelReasonInput = reason) }
    }

    fun confirmCancelOrder(orderId: String, isFarmer: Boolean) {
        viewModelScope.launch {
            _detailState.update { it.copy(actionLoading = true, actionError = null) }
            val inputReason = _detailState.value.cancelReasonInput.trim()
            val reason = if (isFarmer) {
                inputReason.ifBlank { "Cancelled by farmer" }
            } else {
                inputReason.ifBlank { "Cancelled by buyer" }
            }
            val res = if (isFarmer) {
                repository.farmerCancelOrder(orderId, reason)
            } else {
                repository.buyerCancelOrder(orderId, reason)
            }

            res.onSuccess {
                _detailState.update {
                    it.copy(actionLoading = false, showCancelDialog = false, actionSuccessMessage = "Order cancelled.")
                }
                loadOrderDetail(orderId)
            }.onFailure { err ->
                _detailState.update { it.copy(actionLoading = false, actionError = err.toUserFriendlyMessage("Unable to cancel order.")) }
            }
        }
    }

    fun cancelOrderFromList(orderId: String, isFarmer: Boolean, currentUserId: String, reason: String? = null) {
        viewModelScope.launch {
            if (isFarmer) {
                repository.farmerCancelOrder(orderId, reason?.ifBlank { "Cancelled by farmer" } ?: "Cancelled by farmer")
                loadFarmerOrders(currentUserId)
            } else {
                repository.buyerCancelOrder(orderId, reason?.ifBlank { "Cancelled by buyer" } ?: "Cancelled by buyer")
                loadBuyerOrders(currentUserId)
            }
        }
    }

    fun markReady(orderId: String) {
        viewModelScope.launch {
            _detailState.update { it.copy(actionLoading = true) }
            val res = repository.farmerMarkReady(orderId)
            res.onSuccess {
                _detailState.update { it.copy(actionLoading = false, actionSuccessMessage = "Marked packed & ready.") }
                loadOrderDetail(orderId)
            }.onFailure { err ->
                _detailState.update { it.copy(actionLoading = false, actionError = err.toUserFriendlyMessage("Unable to update order status.")) }
            }
        }
    }

    fun markDispatched(orderId: String) {
        viewModelScope.launch {
            _detailState.update { it.copy(actionLoading = true) }
            val res = repository.farmerMarkDispatched(orderId)
            res.onSuccess {
                _detailState.update { it.copy(actionLoading = false, actionSuccessMessage = "Order dispatched.") }
                loadOrderDetail(orderId)
            }.onFailure { err ->
                _detailState.update { it.copy(actionLoading = false, actionError = err.toUserFriendlyMessage("Unable to mark order dispatched.")) }
            }
        }
    }

    fun markDelivered(orderId: String) {
        viewModelScope.launch {
            _detailState.update { it.copy(actionLoading = true) }
            val res = repository.farmerMarkDelivered(orderId)
            res.onSuccess {
                _detailState.update { it.copy(actionLoading = false, actionSuccessMessage = "Marked delivered / handed over.") }
                loadOrderDetail(orderId)
            }.onFailure { err ->
                _detailState.update { it.copy(actionLoading = false, actionError = err.toUserFriendlyMessage("Unable to mark order delivered.")) }
            }
        }
    }

    // Buyer Action Handlers
    fun confirmReceipt(orderId: String) {
        viewModelScope.launch {
            _detailState.update { it.copy(actionLoading = true) }
            val res = repository.buyerConfirmDelivered(orderId)
            res.onSuccess {
                _detailState.update {
                    it.copy(
                        actionLoading = false,
                        showReviewDialog = true,
                        actionSuccessMessage = "Receipt confirmed! Please leave a review."
                    )
                }
                loadOrderDetail(orderId)
            }.onFailure { err ->
                _detailState.update { it.copy(actionLoading = false, actionError = err.toUserFriendlyMessage("Unable to confirm receipt.")) }
            }
        }
    }

    fun initiatePayment(orderId: String) {
        viewModelScope.launch {
            _detailState.update { it.copy(actionLoading = true, actionError = null) }
            val res = repository.initiatePayHerePayment(orderId)
            res.onSuccess { payReq ->
                _detailState.update {
                    it.copy(actionLoading = false, payHereRequest = payReq)
                }
            }.onFailure { err ->
                val errorMsg = com.example.data.payment.parsePaymentErrorMessage(err.message ?: "Unable to initiate payment gateway.")
                _detailState.update { it.copy(actionLoading = false, actionError = errorMsg) }
            }
        }
    }

    fun onPayHereProcessed() {
        _detailState.update { it.copy(payHereRequest = null) }
    }

    // Dispute
    fun openDisputeDialog() {
        _detailState.update { it.copy(showDisputeDialog = true, disputeReasonInput = "") }
    }

    fun closeDisputeDialog() {
        _detailState.update { it.copy(showDisputeDialog = false) }
    }

    fun onDisputeReasonChange(reason: String) {
        _detailState.update { it.copy(disputeReasonInput = reason) }
    }

    fun submitDispute(orderId: String) {
        val reason = _detailState.value.disputeReasonInput.trim()
        if (reason.length < 10) {
            _detailState.update { it.copy(actionError = "Dispute reason must be at least 10 characters") }
            return
        }

        viewModelScope.launch {
            _detailState.update { it.copy(actionLoading = true, actionError = null) }
            val res = repository.buyerRaiseDispute(orderId, reason, emptyList())
            res.onSuccess {
                _detailState.update {
                    it.copy(
                        actionLoading = false,
                        showDisputeDialog = false,
                        actionSuccessMessage = "Dispute submitted. Admin review pending."
                    )
                }
                loadOrderDetail(orderId)
            }.onFailure { err ->
                _detailState.update { it.copy(actionLoading = false, actionError = err.message) }
            }
        }
    }

    // Review
    fun openReviewDialog() {
        _detailState.update { it.copy(showReviewDialog = true, ratingInput = 5, reviewCommentInput = "") }
    }

    fun closeReviewDialog() {
        _detailState.update { it.copy(showReviewDialog = false) }
    }

    fun onRatingChange(rating: Int) {
        _detailState.update { it.copy(ratingInput = rating) }
    }

    fun onReviewCommentChange(comment: String) {
        _detailState.update { it.copy(reviewCommentInput = comment) }
    }

    fun submitReview(orderId: String) {
        val state = _detailState.value
        viewModelScope.launch {
            _detailState.update { it.copy(actionLoading = true, actionError = null) }
            val res = repository.buyerSubmitReview(orderId, state.ratingInput, state.reviewCommentInput)
            res.onSuccess {
                _detailState.update {
                    it.copy(
                        actionLoading = false,
                        showReviewDialog = false,
                        actionSuccessMessage = "Review submitted! Thank you."
                    )
                }
                loadOrderDetail(orderId)
            }.onFailure { err ->
                _detailState.update { it.copy(actionLoading = false, actionError = err.message) }
            }
        }
    }

    fun onPaymentSdkResult(resultCode: Int, orderId: String) {
        if (resultCode == android.app.Activity.RESULT_OK) {
            pollPaymentStatus(orderId)
        } else if (resultCode == android.app.Activity.RESULT_CANCELED) {
            _detailState.update {
                it.copy(
                    actionLoading = false,
                    actionError = "Payment was cancelled. You can try again whenever ready."
                )
            }
        } else {
            _detailState.update {
                it.copy(
                    actionLoading = false,
                    actionError = "Payment failed or was incomplete. Please try again."
                )
            }
        }
    }

    fun pollPaymentStatus(orderId: String) {
        viewModelScope.launch {
            _detailState.update {
                it.copy(
                    actionLoading = true,
                    actionError = null,
                    actionSuccessMessage = "Confirming payment..."
                )
            }
            val maxAttempts = 20 // 20 * 2000ms = 40 seconds
            var isPaid = false
            for (i in 0 until maxAttempts) {
                delay(2000)
                val orderRes = repository.getOrderById(orderId)
                val order = orderRes.getOrNull()
                if (order != null && order.status == "paid") {
                    isPaid = true
                    loadOrderDetail(orderId)
                    if (order.buyerId.isNotBlank()) loadBuyerOrders(order.buyerId)
                    if (order.farmerId.isNotBlank()) loadFarmerOrders(order.farmerId)
                    _detailState.update {
                        it.copy(
                            actionLoading = false,
                            actionSuccessMessage = "Payment confirmed! Order is now Paid."
                        )
                    }
                    break
                }
            }
            if (!isPaid) {
                loadOrderDetail(orderId)
                _detailState.update {
                    it.copy(
                        actionLoading = false,
                        actionSuccessMessage = "Payment received, your order will update shortly"
                    )
                }
            }
        }
    }
}
