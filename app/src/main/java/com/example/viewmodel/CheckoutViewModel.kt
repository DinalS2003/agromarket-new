package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.SessionManager
import com.example.data.models.CartItem
import com.example.data.models.City
import com.example.data.models.District
import com.example.data.repository.AgroMarketRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

data class FarmerCheckoutConfig(
    val farmerId: String,
    val farmerName: String,
    val farmerDistrict: String?,
    val items: List<CartItem>,
    val subtotal: Double,
    val deliveryMethod: String = "buyer_arranged",
    val requestedDate: String,
    val earliestDate: String,
    val deliveryDistrictId: Int = 1,
    val deliveryCityId: Int = 1,
    val deliveryAddress: String = "",
    val availableCities: List<City> = emptyList()
)

data class PlacedOrderResult(
    val orderId: String,
    val orderNumber: String,
    val farmerName: String,
    val cropSummary: String,
    val amount: Double = 0.0
)

data class CheckoutUiState(
    val configs: List<FarmerCheckoutConfig> = emptyList(),
    val districts: List<District> = emptyList(),
    val totalAmount: Double = 0.0,
    val totalItems: Int = 0,
    val isSubmitting: Boolean = false,
    val placedOrders: List<PlacedOrderResult> = emptyList(),
    val failedFarmerNames: List<String> = emptyList(),
    val errorMessage: String? = null,
    val isCompleted: Boolean = false,
    val navigateToPaymentOrderId: String? = null,
    val paymentAmount: Double = 0.0,
    val paymentCropSummary: String = ""
)

class CheckoutViewModel(
    private val repository: AgroMarketRepository,
    private val sessionManager: SessionManager,
    private val cartViewModel: CartViewModel
) : ViewModel() {

    private val _uiState = MutableStateFlow(CheckoutUiState())
    val uiState: StateFlow<CheckoutUiState> = _uiState.asStateFlow()

    fun initCheckout() {
        viewModelScope.launch {
            val districts = repository.getDistricts().getOrDefault(emptyList())
            val userDistrictId = sessionManager.getUserDistrictId()
            val userCityId = sessionManager.getUserCityId()
            val defaultCities = repository.getCities(userDistrictId).getOrDefault(emptyList())

            val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val cartGroups = cartViewModel.uiState.value.farmerGroups
            val allItems = cartViewModel.uiState.value.items

            val configs = cartGroups.map { group ->
                // Earliest date must be on or after harvest dates of all items in group
                val maxHarvestDate = group.items.mapNotNull { it.harvestDate }.maxOrNull() ?: todayStr
                val earliest = if (maxHarvestDate > todayStr) maxHarvestDate else todayStr

                FarmerCheckoutConfig(
                    farmerId = group.farmerId,
                    farmerName = group.farmerName,
                    farmerDistrict = group.farmerDistrict,
                    items = group.items,
                    subtotal = group.subtotal,
                    deliveryMethod = "buyer_arranged",
                    requestedDate = earliest,
                    earliestDate = earliest,
                    deliveryDistrictId = userDistrictId,
                    deliveryCityId = userCityId,
                    deliveryAddress = "",
                    availableCities = defaultCities
                )
            }

            _uiState.value = CheckoutUiState(
                configs = configs,
                districts = districts,
                totalAmount = allItems.sumOf { it.subtotal },
                totalItems = allItems.size
            )
        }
    }

    fun onDeliveryMethodChanged(farmerId: String, method: String) {
        _uiState.update { state ->
            state.copy(
                configs = state.configs.map { config ->
                    if (config.farmerId == farmerId) {
                        config.copy(deliveryMethod = method)
                    } else config
                }
            )
        }
    }

    fun onRequestedDateChanged(farmerId: String, newDate: String) {
        _uiState.update { state ->
            state.copy(
                configs = state.configs.map { config ->
                    if (config.farmerId == farmerId) {
                        config.copy(requestedDate = newDate)
                    } else config
                }
            )
        }
    }

    fun onDistrictChanged(farmerId: String, districtId: Int) {
        viewModelScope.launch {
            val cities = repository.getCities(districtId).getOrDefault(emptyList())
            _uiState.update { state ->
                state.copy(
                    configs = state.configs.map { config ->
                        if (config.farmerId == farmerId) {
                            config.copy(
                                deliveryDistrictId = districtId,
                                availableCities = cities,
                                deliveryCityId = cities.firstOrNull()?.id ?: 1
                            )
                        } else config
                    }
                )
            }
        }
    }

    fun onCityChanged(farmerId: String, cityId: Int) {
        _uiState.update { state ->
            state.copy(
                configs = state.configs.map { config ->
                    if (config.farmerId == farmerId) {
                        config.copy(deliveryCityId = cityId)
                    } else config
                }
            )
        }
    }

    fun onAddressChanged(farmerId: String, address: String) {
        _uiState.update { state ->
            state.copy(
                configs = state.configs.map { config ->
                    if (config.farmerId == farmerId) {
                        config.copy(deliveryAddress = address)
                    } else config
                }
            )
        }
    }

    fun submitCheckout() {
        val state = _uiState.value
        if (state.configs.isEmpty() || state.isSubmitting) return

        // Validation: Address required if farmer_delivery
        for (cfg in state.configs) {
            if (cfg.deliveryMethod == "farmer_delivery" && cfg.deliveryAddress.trim().isBlank()) {
                _uiState.update {
                    it.copy(errorMessage = "Please enter your delivery street address for ${cfg.farmerName}.")
                }
                return
            }
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }

            val successfulOrders = mutableListOf<PlacedOrderResult>()
            val failedFarmers = mutableListOf<String>()

            for (cfg in state.configs) {
                var groupSuccess = true
                for (item in cfg.items) {
                    val res = repository.createOrderRequest(
                        listingId = item.listingId,
                        quantityKg = item.quantityKg,
                        deliveryMethod = cfg.deliveryMethod,
                        requestedDate = cfg.requestedDate,
                        deliveryDistrictId = if (cfg.deliveryMethod == "farmer_delivery") cfg.deliveryDistrictId else null,
                        deliveryCityId = if (cfg.deliveryMethod == "farmer_delivery") cfg.deliveryCityId else null,
                        deliveryAddress = if (cfg.deliveryMethod == "farmer_delivery") cfg.deliveryAddress else null
                    )

                    if (res.isSuccess) {
                        val orderId = res.getOrNull() ?: ""
                        val itemAmount = item.pricePerKg * item.quantityKg
                        successfulOrders.add(
                            PlacedOrderResult(
                                orderId = orderId,
                                orderNumber = "AM-${orderId.take(8).uppercase()}",
                                farmerName = cfg.farmerName,
                                cropSummary = "${item.quantityKg.toInt()} kg ${item.cropName}",
                                amount = itemAmount
                            )
                        )
                    } else {
                        groupSuccess = false
                    }
                }

                if (groupSuccess) {
                    // Remove successfully ordered items from cart
                    cartViewModel.clearFarmerGroup(cfg.farmerId)
                } else {
                    failedFarmers.add(cfg.farmerName)
                }
            }

            if (successfulOrders.isNotEmpty()) {
                val primaryOrder = successfulOrders.first()
                val totalAmount = state.totalAmount
                val cropSummary = successfulOrders.joinToString(", ") { it.cropSummary }

                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        placedOrders = successfulOrders,
                        failedFarmerNames = failedFarmers,
                        isCompleted = true,
                        navigateToPaymentOrderId = null,
                        paymentAmount = totalAmount,
                        paymentCropSummary = cropSummary,
                        errorMessage = if (failedFarmers.isNotEmpty()) {
                            "Orders for ${failedFarmers.joinToString(", ")} could not be placed and remain in your cart."
                        } else null
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        errorMessage = "Unable to place orders. Please verify your internet connection and try again."
                    )
                }
            }
        }
    }

    fun onPaymentNavigationHandled() {
        _uiState.update { it.copy(navigateToPaymentOrderId = null) }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
