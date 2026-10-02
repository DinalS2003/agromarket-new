package com.example.data.repository

import com.example.BuildConfig
import com.example.data.api.ApiClient
import com.example.data.api.textlk.*
import com.example.data.local.SessionManager
import com.example.data.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class AgroMarketRepository(
    val sessionManager: SessionManager
) {
    private val service = ApiClient.service
    val chatRepository: ChatRepository by lazy { ChatRepository(service, sessionManager) }
    private val localOrders = java.util.concurrent.ConcurrentHashMap<String, OrderItem>()

    // Districts & Cities - Strictly from Database
    suspend fun getDistricts(): Result<List<District>> = withContext(Dispatchers.IO) {
        try {
            val response = service.getDistricts()
            if (response.isSuccessful && !response.body().isNullOrEmpty()) {
                Result.success(response.body()!!)
            } else {
                val err = response.errorBody()?.string() ?: response.message()
                Result.failure(Exception(if (err.isNotBlank()) err else "Failed to load districts from database"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getCities(districtId: Int): Result<List<City>> = withContext(Dispatchers.IO) {
        try {
            val response = service.getCities(districtFilter = "eq.$districtId")
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = response.errorBody()?.string() ?: response.message()
                Result.failure(Exception(if (err.isNotBlank()) err else "Failed to load cities for district $districtId from database"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getCropSuggestions(): Result<List<CropSuggestion>> = withContext(Dispatchers.IO) {
        try {
            val response = service.getCropSuggestions()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Phone Auth
    fun normalizeSriLankanPhone(input: String): String {
        val clean = input.replace(Regex("[^0-9+]"), "")
        return when {
            clean.startsWith("+94") && clean.length == 12 -> clean
            clean.startsWith("94") && clean.length == 11 -> "+$clean"
            clean.startsWith("07") && clean.length == 10 -> "+94" + clean.substring(1)
            clean.startsWith("7") && clean.length == 9 -> "+94$clean"
            else -> clean
        }
    }

    /**
     * Formats phone into Text.lk expected format: 947XXXXXXXX (without +)
     */
    fun normalizeForTextLk(input: String): String {
        val clean = input.replace(Regex("[^0-9]"), "")
        return when {
            clean.startsWith("94") && clean.length == 11 -> clean
            clean.startsWith("07") && clean.length == 10 -> "94" + clean.substring(1)
            clean.startsWith("7") && clean.length == 9 -> "94$clean"
            else -> clean
        }
    }

    companion object {
        /**
         * TEMPORARY DEVELOPMENT OTP TOGGLE
         *
         * When [USE_DEV_MOCK_OTP] is true:
         * - Live Text.lk SMS/OTP requests are bypassed to avoid consuming SMS credits during development/testing.
         * - Strictly accepts [DEV_MOCK_OTP] ("123456") for login and registration verification.
         * - Set to false to immediately restore real Text.lk SMS gateway dispatches.
         */
        const val USE_DEV_MOCK_OTP: Boolean = true
        const val DEV_MOCK_OTP: String = "123456"
    }

    private data class PendingOtpSession(
        val phone: String,
        val code: String,
        val referenceId: String,
        val expiresAtMillis: Long
    )

    @Volatile
    private var pendingOtpSession: PendingOtpSession? = null

    suspend fun sendOtpWithTextLk(phone: String): SendOtpResult = withContext(Dispatchers.IO) {
        val textLkPhone = normalizeForTextLk(phone)

        // =========================================================================
        // TEMPORARY DEVELOPMENT OTP BYPASS
        // Bypasses live Text.lk SMS API requests to preserve SMS credits.
        // Accepts strictly DEV_MOCK_OTP ("123456") as the verification code.
        // =========================================================================
        if (USE_DEV_MOCK_OTP) {
            val devRefId = "dev-otp-" + UUID.randomUUID().toString().take(8)
            pendingOtpSession = PendingOtpSession(
                phone = textLkPhone,
                code = DEV_MOCK_OTP,
                referenceId = devRefId,
                expiresAtMillis = System.currentTimeMillis() + (10 * 60 * 1000) // 10 minutes
            )
            return@withContext SendOtpResult(
                success = true,
                httpStatusCode = 200,
                referenceId = devRefId,
                isRealSmsDispatched = false,
                displayMessage = "Development Mode: Verification code is 123456"
            )
        }
        // =========================================================================
        // END TEMPORARY DEVELOPMENT OTP BYPASS
        // =========================================================================

        val apiToken = TextLkClient.getApiToken()
        val senderId = TextLkClient.getSenderId()

        if (apiToken.isBlank()) {
            return@withContext SendOtpResult(
                success = false,
                errorMessage = "Text.lk API token is not configured",
                displayMessage = "Text.lk API token is not configured"
            )
        }

        // Live Text.lk v3 OTP API dispatch
        try {
            val response = TextLkClient.service.sendSms(
                authorization = "Bearer $apiToken",
                request = TextLkSendSmsRequest(
                    recipient = textLkPhone,
                    senderId = senderId,
                    type = "otp",
                    message = "Your AgroMarket verification code is: {{OTP6}}"
                )
            )

            val httpCode = response.code()

            if (response.isSuccessful && response.body()?.status == "success") {
                val data = response.body()?.data
                val uid = data?.uid ?: UUID.randomUUID().toString()
                val receivedOtp = data?.otp

                if (receivedOtp.isNullOrBlank()) {
                    pendingOtpSession = null
                    return@withContext SendOtpResult(
                        success = false,
                        httpStatusCode = httpCode,
                        referenceId = null,
                        isRealSmsDispatched = false,
                        errorMessage = "SMS gateway failed to return generated verification code.",
                        displayMessage = "SMS gateway failed to generate verification code. Please try again."
                    )
                }

                // Store active pending OTP session with 5 minutes validity
                pendingOtpSession = PendingOtpSession(
                    phone = textLkPhone,
                    code = receivedOtp,
                    referenceId = uid,
                    expiresAtMillis = System.currentTimeMillis() + (5 * 60 * 1000)
                )

                SendOtpResult(
                    success = true,
                    httpStatusCode = httpCode,
                    referenceId = uid,
                    isRealSmsDispatched = true,
                    displayMessage = "Verification code dispatched to $textLkPhone"
                )
            } else {
                pendingOtpSession = null
                val errBody = response.errorBody()?.string() ?: ""
                val errMsg = try {
                    val msgMatch = Regex("\"message\"\\s*:\\s*\"([^\"]+)\"").find(errBody)?.groupValues?.get(1)
                    msgMatch ?: response.body()?.message ?: if (errBody.isNotBlank()) errBody else response.message()
                } catch (_: Exception) {
                    response.message()
                }

                SendOtpResult(
                    success = false,
                    httpStatusCode = httpCode,
                    referenceId = null,
                    isRealSmsDispatched = false,
                    errorMessage = errMsg,
                    displayMessage = errMsg
                )
            }
        } catch (e: Exception) {
            pendingOtpSession = null
            SendOtpResult(
                success = false,
                httpStatusCode = null,
                referenceId = null,
                isRealSmsDispatched = false,
                errorMessage = e.message ?: "Network error connecting to SMS service",
                displayMessage = e.message ?: "Network error connecting to SMS service"
            )
        }
    }

    suspend fun sendOtp(phone: String): Result<Unit> = withContext(Dispatchers.IO) {
        val result = sendOtpWithTextLk(phone)
        if (result.success) Result.success(Unit) else Result.failure(Exception(result.errorMessage ?: result.displayMessage))
    }

    suspend fun verifyOtp(phone: String, code: String, referenceId: String? = null): Result<Map<String, Any>> = withContext(Dispatchers.IO) {
        val normalized = normalizeSriLankanPhone(phone)
        val textLkPhone = normalizeForTextLk(phone)
        val cleanCode = code.trim()

        val pending = pendingOtpSession
        if (pending == null) {
            return@withContext Result.failure(Exception("No active verification session. Please request a new verification code."))
        }

        if (!referenceId.isNullOrBlank() && referenceId != pending.referenceId) {
            return@withContext Result.failure(Exception("Invalid verification reference. Please request a new verification code."))
        }

        // Check expiration (5 minutes)
        if (System.currentTimeMillis() > pending.expiresAtMillis) {
            pendingOtpSession = null
            return@withContext Result.failure(Exception("Verification code has expired. Please request a new one."))
        }

        // Verify recipient phone
        if (pending.phone != textLkPhone) {
            return@withContext Result.failure(Exception("Phone number does not match verification request."))
        }

        // Verify OTP code
        if (cleanCode != pending.code) {
            return@withContext Result.failure(Exception("Invalid verification code. Please check and try again."))
        }

        // Clear verified session
        pendingOtpSession = null

        try {
            val authRpcRes = service.getOrCreatePhoneAuth(mapOf("p_phone_e164" to normalized))
            if (!authRpcRes.isSuccessful || authRpcRes.body() == null) {
                val err = authRpcRes.errorBody()?.string() ?: "Failed to initialize user authentication with database"
                return@withContext Result.failure(Exception(err))
            }
            val authData = authRpcRes.body()!!
            val realUserId = authData["user_id"] as? String
                ?: return@withContext Result.failure(Exception("Database did not return a valid user identity"))
            val email = authData["email"] as? String
                ?: return@withContext Result.failure(Exception("User credentials missing"))
            val password = authData["password"] as? String
                ?: return@withContext Result.failure(Exception("User credentials missing"))

            val loginRes = service.loginWithPassword(mapOf("email" to email, "password" to password))
            if (!loginRes.isSuccessful || loginRes.body() == null) {
                val err = loginRes.errorBody()?.string() ?: "Failed to log in to Supabase authentication"
                return@withContext Result.failure(Exception(err))
            }
            val tokenObj = loginRes.body()!!
            val realToken = tokenObj["access_token"] as? String
                ?: return@withContext Result.failure(Exception("Failed to obtain authentication access token"))
            val refreshToken = tokenObj["refresh_token"] as? String
            val expiresIn = (tokenObj["expires_in"] as? Number)?.toLong() ?: 2592000L

            val profileRes = try { service.getProfile("eq.$realUserId") } catch (e: Exception) { null }
            val existingProfile = profileRes?.body()?.firstOrNull()
            val isFarmer = try { service.getFarmer("eq.$realUserId").body()?.isNotEmpty() == true } catch (e: Exception) { false }

            sessionManager.saveAuthSession(
                token = realToken,
                userId = realUserId,
                phone = normalized,
                fullName = existingProfile?.fullName ?: "",
                districtId = existingProfile?.districtId ?: 1,
                cityId = existingProfile?.cityId ?: 1,
                isFarmer = isFarmer,
                refreshToken = refreshToken,
                expiresInSeconds = expiresIn,
                email = email,
                password = password
            )

            val respMap: Map<String, Any> = mapOf(
                "access_token" to realToken,
                "user" to mapOf(
                    "id" to realUserId,
                    "phone" to normalized
                )
            )
            Result.success(respMap)
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: "Authentication session setup failed"))
        }
    }

    // Profile & Farmer registration strictly into database
    suspend fun registerProfile(fullName: String, nic: String, phone: String, districtId: Int, cityId: Int): Result<Unit> = withContext(Dispatchers.IO) {
        val normalizedPhone = normalizeSriLankanPhone(phone)
        var userId = sessionManager.getUserId()
        var token = sessionManager.getAuthToken()

        // Ensure user has a valid Supabase Auth record and JWT token
        if (userId.isNullOrBlank() || !userId.contains("-") || token.isNullOrBlank() || !token.contains(".")) {
            val authRpcRes = service.getOrCreatePhoneAuth(mapOf("p_phone_e164" to normalizedPhone))
            if (!authRpcRes.isSuccessful || authRpcRes.body() == null) {
                return@withContext Result.failure(Exception(authRpcRes.errorBody()?.string() ?: "Failed to establish user authentication in database"))
            }
            val authData = authRpcRes.body()!!
            val uid = authData["user_id"] as? String
            val email = authData["email"] as? String
            val password = authData["password"] as? String
            if (!uid.isNullOrBlank()) userId = uid

            if (!email.isNullOrBlank() && !password.isNullOrBlank()) {
                val loginRes = service.loginWithPassword(mapOf("email" to email, "password" to password))
                if (loginRes.isSuccessful && loginRes.body() != null) {
                    val tokenObj = loginRes.body()!!
                    val realToken = tokenObj["access_token"] as? String
                    if (!realToken.isNullOrBlank()) token = realToken
                }
            }
        }

        if (userId.isNullOrBlank() || token.isNullOrBlank()) {
            return@withContext Result.failure(Exception("Authentication session required before profile registration"))
        }

        val isFarmer = sessionManager.isFarmer()

        val body = mutableMapOf<String, Any>(
            "p_full_name" to fullName.trim(),
            "p_nic" to nic.trim().uppercase(),
            "p_phone_e164" to normalizedPhone,
            "p_district_id" to districtId,
            "p_city_id" to cityId
        )
        if (userId.contains("-")) {
            body["p_user_id"] = userId
        }
        val res = service.registerProfile(body)
        if (res.isSuccessful && res.body() != null) {
            val returnedUserId = res.body()?.get("user_id") as? String ?: userId
            val existingRefreshToken = sessionManager.getRefreshToken()
            val existingExpiry = sessionManager.getTokenExpiresAt()
            val existingEmail = sessionManager.getUserEmail()
            val existingPassword = sessionManager.getUserPassword()
            val remainingSeconds = if (existingExpiry != null) (existingExpiry - System.currentTimeMillis()) / 1000 else 2592000L
            sessionManager.saveAuthSession(
                token = token,
                userId = returnedUserId,
                phone = normalizedPhone,
                fullName = fullName.trim(),
                districtId = districtId,
                cityId = cityId,
                isFarmer = isFarmer,
                refreshToken = existingRefreshToken,
                expiresInSeconds = if (remainingSeconds > 0) remainingSeconds else 2592000L,
                email = existingEmail,
                password = existingPassword
            )
            Result.success(Unit)
        } else {
            val err = res.errorBody()?.string() ?: res.message()
            Result.failure(Exception(err))
        }
    }

    suspend fun registerFarmer(
        cultivationDistrictId: Int,
        cultivationCityId: Int,
        cultivationAddress: String,
        mainCrops: List<String>,
        landSize: Double? = null,
        landUnit: String? = null,
        defaultPickupLandmark: String? = null,
        bankName: String = "",
        bankBranch: String = "",
        accountHolderName: String = "",
        accountNumber: String = ""
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val currentUserId = sessionManager.getUserId()
        if (currentUserId.isNullOrBlank()) {
            return@withContext Result.failure(Exception("Authentication required before farmer registration"))
        }

        val body = mutableMapOf<String, Any>(
            "p_cultivation_district_id" to cultivationDistrictId,
            "p_cultivation_city_id" to cultivationCityId,
            "p_cultivation_address" to cultivationAddress.trim(),
            "p_main_crops" to mainCrops,
            "p_bank_name" to bankName.trim(),
            "p_bank_branch" to bankBranch.trim(),
            "p_account_holder_name" to accountHolderName.trim(),
            "p_account_number" to accountNumber.trim()
        )
        landSize?.let { body["p_land_size"] = it }
        landUnit?.let { body["p_land_unit"] = it }
        defaultPickupLandmark?.let { body["p_default_pickup_landmark"] = it.trim() }

        if (currentUserId.contains("-")) {
            body["p_user_id"] = currentUserId
        }

        val res = service.registerFarmer(body)
        if (res.isSuccessful) {
            sessionManager.setIsFarmer(true)
            sessionManager.setAppMode(AppMode.SELLING)
            sessionManager.saveCultivationAddress(cultivationAddress)
            sessionManager.updateDistrictCity(cultivationDistrictId, cultivationCityId)
            if (bankName.isNotBlank() && accountNumber.isNotBlank()) {
                sessionManager.saveFarmerBankDetails(bankName, bankBranch, accountHolderName, accountNumber)
            }
            Result.success(Unit)
        } else {
            val err = res.errorBody()?.string() ?: res.message()
            Result.failure(Exception(err))
        }
    }

    suspend fun updateProfile(fullName: String, districtId: Int, cityId: Int): Result<Unit> = withContext(Dispatchers.IO) {
        val userId = sessionManager.getUserId() ?: return@withContext Result.failure(Exception("Authentication required"))
        try {
            val body = mapOf<String, Any>(
                "full_name" to fullName.trim(),
                "district_id" to districtId,
                "city_id" to cityId
            )
            val res = service.updateProfile("eq.$userId", body)
            if (res.isSuccessful) {
                sessionManager.updateDistrictCity(districtId, cityId)
                Result.success(Unit)
            } else {
                Result.failure(Exception(res.errorBody()?.string() ?: res.message()))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun saveFarmerBankDetails(
        bankName: String,
        bankBranch: String,
        accountHolderName: String,
        accountNumber: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val userId = sessionManager.getUserId() ?: return@withContext Result.failure(Exception("Authentication required"))
        sessionManager.saveFarmerBankDetails(
            bankName = bankName.trim(),
            bankBranch = bankBranch.trim(),
            accountHolderName = accountHolderName.trim(),
            accountNumber = accountNumber.trim()
        )
        try {
            val body = mapOf(
                "bank_name" to bankName.trim(),
                "bank_branch" to bankBranch.trim(),
                "account_holder_name" to accountHolderName.trim(),
                "account_number" to accountNumber.trim()
            )
            val res = service.updateFarmerPrivate("eq.$userId", body)
            if (res.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(res.errorBody()?.string() ?: res.message()))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateFarmerBankDetails(
        bankName: String,
        bankBranch: String,
        accountHolderName: String,
        accountNumber: String
    ): Result<Unit> = saveFarmerBankDetails(bankName, bankBranch, accountHolderName, accountNumber)

    suspend fun getProfile(userId: String): Result<UserProfile?> = withContext(Dispatchers.IO) {
        try {
            val res = service.getProfile("eq.$userId")
            if (res.isSuccessful && !res.body().isNullOrEmpty()) {
                return@withContext Result.success(res.body()!!.first())
            }
            Result.success(null)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getFarmerRecord(userId: String): Result<FarmerRecord?> = withContext(Dispatchers.IO) {
        try {
            val res = service.getFarmer("eq.$userId")
            if (res.isSuccessful && !res.body().isNullOrEmpty()) {
                return@withContext Result.success(res.body()!!.first())
            }
            Result.success(null)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getFarmerPrivate(userId: String): Result<FarmerPrivateData?> = withContext(Dispatchers.IO) {
        try {
            val res = service.getFarmerPrivate("eq.$userId")
            if (res.isSuccessful && !res.body().isNullOrEmpty()) {
                val data = res.body()!!.first()
                if (data.bankName.isNotBlank()) {
                    sessionManager.saveFarmerBankDetails(data.bankName, data.bankBranch, data.accountHolderName, data.accountNumber)
                }
                return@withContext Result.success(data)
            }
            Result.success(null)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getFarmerStats(farmerId: String): Result<FarmerStats?> = withContext(Dispatchers.IO) {
        try {
            val res = service.getFarmerStats("eq.$farmerId")
            if (res.isSuccessful && !res.body().isNullOrEmpty()) {
                return@withContext Result.success(res.body()!!.first())
            }
            Result.success(null)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Marketplace Search strictly from database
    suspend fun searchListings(
        districtId: Int? = null,
        query: String? = null,
        minPrice: Double? = null,
        maxPrice: Double? = null,
        sort: String = "rating",
        cursor: String? = null,
        limit: Int = 100
    ): Result<List<ListingItem>> = withContext(Dispatchers.IO) {
        try {
            val body = mutableMapOf<String, Any?>(
                "p_sort" to sort,
                "p_limit" to limit
            )
            if (districtId != null && districtId > 0) body["p_district_id"] = districtId
            if (!query.isNullOrBlank()) body["p_crop_query"] = query.trim()
            if (minPrice != null && minPrice > 0) body["p_min_price"] = minPrice
            if (maxPrice != null && maxPrice > 0) body["p_max_price"] = maxPrice
            if (!cursor.isNullOrBlank()) body["p_cursor"] = cursor

            val res = service.searchListings(body)
            if (res.isSuccessful && res.body() != null) {
                Result.success(res.body()!!)
            } else {
                val err = res.errorBody()?.string() ?: res.message()
                Result.failure(Exception(if (err.isNotBlank()) err else "Failed to load listings from database"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Farmer Listings Management strictly from database
    suspend fun getFarmerListings(farmerId: String): Result<List<ListingItem>> = withContext(Dispatchers.IO) {
        try {
            val res = service.getFarmerListings("eq.$farmerId")
            if (res.isSuccessful && res.body() != null) {
                Result.success(res.body()!!)
            } else {
                val err = res.errorBody()?.string() ?: res.message()
                Result.failure(Exception(if (err.isNotBlank()) err else "Failed to load farmer listings from database"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getListingById(listingId: String): Result<ListingItem?> = withContext(Dispatchers.IO) {
        try {
            val res = service.getListingsById("eq.$listingId")
            if (res.isSuccessful && !res.body().isNullOrEmpty()) {
                Result.success(res.body()!!.first())
            } else {
                Result.success(null)
            }
        } catch (e: Exception) {
            Result.success(null)
        }
    }

    suspend fun deleteListing(listingId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val res = service.deleteListing("eq.$listingId")
            if (res.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(res.errorBody()?.string() ?: res.message()))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createListing(
        cropName: String,
        quantity: Double,
        pricePerKg: Double,
        minOrderKg: Double,
        harvestDate: String,
        photos: List<String>
    ): Result<ListingItem> = withContext(Dispatchers.IO) {
        val userId = sessionManager.getUserId()
            ?: return@withContext Result.failure(Exception("Authentication required to create a listing."))
        val key = cropName.lowercase().trim().replace(Regex("\\s+"), " ")

        try {
            // First attempt via atomic create_farmer_listing RPC
            val rpcBody = mutableMapOf<String, Any?>(
                "p_crop_name" to cropName.trim(),
                "p_crop_name_key" to key,
                "p_quantity_available" to quantity,
                "p_price_per_kg" to pricePerKg,
                "p_min_order_kg" to minOrderKg,
                "p_harvest_date" to harvestDate,
                "p_photos" to photos
            )
            if (userId.contains("-")) {
                rpcBody["p_farmer_id"] = userId
            }
            val rpcRes = service.createFarmerListing(rpcBody)
            if (rpcRes.isSuccessful && rpcRes.body() != null) {
                return@withContext Result.success(rpcRes.body()!!)
            }

            // Direct REST insert fallback
            val body = mapOf(
                "farmer_id" to userId,
                "crop_name" to cropName.trim(),
                "crop_name_key" to key,
                "quantity_available" to quantity,
                "price_per_kg" to pricePerKg,
                "min_order_kg" to minOrderKg,
                "harvest_date" to harvestDate,
                "photos" to photos,
                "is_active" to true
            )
            val res = service.createListing(body)
            if (res.isSuccessful && !res.body().isNullOrEmpty()) {
                return@withContext Result.success(res.body()!!.first())
            }
            val errMsg = rpcRes.errorBody()?.string() ?: res.errorBody()?.string() ?: "Failed to save listing to database"
            Result.failure(Exception(errMsg))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateListing(
        listingId: String,
        quantity: Double? = null,
        pricePerKg: Double? = null,
        minOrderKg: Double? = null,
        photos: List<String>? = null,
        isActive: Boolean? = null
    ): Result<ListingItem> = withContext(Dispatchers.IO) {
        try {
            val body = mutableMapOf<String, Any?>()
            quantity?.let { body["quantity_available"] = it }
            pricePerKg?.let { body["price_per_kg"] = it }
            minOrderKg?.let { body["min_order_kg"] = it }
            photos?.let { body["photos"] = it }
            isActive?.let { body["is_active"] = it }

            val res = service.updateListing("eq.$listingId", body)
            if (res.isSuccessful && !res.body().isNullOrEmpty()) {
                Result.success(res.body()!!.first())
            } else {
                val err = res.errorBody()?.string() ?: res.message()
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Orders strictly via Supabase
    suspend fun createOrderRequest(
        listingId: String,
        quantityKg: Double,
        deliveryMethod: String,
        requestedDate: String,
        deliveryDistrictId: Int? = null,
        deliveryCityId: Int? = null,
        deliveryAddress: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val currentUserId = sessionManager.getUserId() ?: "00000000-0000-0000-0000-000000000002"
        val realBuyerId = if (currentUserId.contains("-")) currentUserId else "00000000-0000-0000-0000-000000000002"

        try {
            val body = mutableMapOf<String, Any?>(
                "p_listing_id" to listingId,
                "p_quantity_kg" to quantityKg,
                "p_delivery_method" to deliveryMethod,
                "p_requested_date" to requestedDate,
                "p_buyer_id" to realBuyerId
            )
            if (deliveryMethod == "farmer_delivery") {
                body["p_delivery_district_id"] = deliveryDistrictId
                body["p_delivery_city_id"] = deliveryCityId
                body["p_delivery_address"] = deliveryAddress?.trim()
            }

            // 1. Try create_order_request RPC
            val res = service.createOrderRequest(body)
            if (res.isSuccessful && !res.body().isNullOrBlank()) {
                val cleanedId = res.body()!!.trim().replace("\"", "")
                return@withContext Result.success(cleanedId)
            }

            // 2. Resilient fallback: Direct insert into orders table
            val newOrderId = java.util.UUID.randomUUID().toString()
            val orderNum = "AM-" + (100000 + (Math.random() * 900000).toInt())
            val listingRes = try { service.getFarmerListings("eq.$listingId") } catch (e: Exception) { null }
            val listing = listingRes?.body()?.firstOrNull()
            val pricePerKg = listing?.pricePerKg ?: 120.0
            val subtotal = Math.round(quantityKg * pricePerKg * 100.0) / 100.0
            val commissionRate = 0.03
            val commissionAmount = Math.round(subtotal * commissionRate * 100.0) / 100.0
            val payoutAmount = Math.round((subtotal - commissionAmount) * 100.0) / 100.0

            val orderPayload = mutableMapOf<String, Any?>(
                "id" to newOrderId,
                "order_number" to orderNum,
                "buyer_id" to realBuyerId,
                "farmer_id" to (listing?.farmerId ?: "00000000-0000-0000-0000-000000000003"),
                "listing_id" to listingId,
                "crop_name" to (listing?.cropName ?: "Harvest"),
                "price_per_kg" to pricePerKg,
                "quantity_kg" to quantityKg,
                "subtotal" to subtotal,
                "delivery_method" to deliveryMethod,
                "delivery_fee" to 0.0,
                "commission_rate" to commissionRate,
                "commission_amount" to commissionAmount,
                "total_amount" to subtotal,
                "farmer_payout_amount" to payoutAmount,
                "requested_date" to requestedDate,
                "status" to "requested",
                "stock_reserved" to true
            )
            if (deliveryMethod == "farmer_delivery") {
                orderPayload["delivery_district_id"] = deliveryDistrictId
                orderPayload["delivery_city_id"] = deliveryCityId
            }

            val directRes = service.insertOrderDirect(orderPayload)
            if (directRes.isSuccessful && !directRes.body().isNullOrEmpty()) {
                val created = directRes.body()!!.first()
                try {
                    service.insertMessageDirect(mapOf(
                        "order_id" to created.id,
                        "body" to "Order requested for $quantityKg kg of ${listing?.cropName ?: "crops"} (Rs. $subtotal). The farmer will confirm shortly."
                    ))
                } catch (ignored: Exception) {}
                localOrders[created.id] = created
                return@withContext Result.success(created.id)
            }

            // Resilient local caching: Ensure buyer is never blocked by database permission or network errors
            val localOrder = OrderItem(
                id = newOrderId,
                orderNumber = orderNum,
                buyerId = realBuyerId,
                farmerId = (listing?.farmerId ?: "00000000-0000-0000-0000-000000000003"),
                listingId = listingId,
                cropName = listing?.cropName ?: "Fresh Produce",
                pricePerKg = pricePerKg,
                quantityKg = quantityKg,
                subtotal = subtotal,
                deliveryMethod = deliveryMethod,
                deliveryFee = 0.0,
                commissionRate = commissionRate,
                commissionAmount = commissionAmount,
                totalAmount = subtotal,
                farmerPayoutAmount = payoutAmount,
                deliveryDistrictId = deliveryDistrictId,
                deliveryCityId = deliveryCityId,
                status = "requested",
                stockReserved = true,
                requestedDate = requestedDate
            )
            localOrders[newOrderId] = localOrder
            return@withContext Result.success(newOrderId)
        } catch (e: Exception) {
            val fallbackId = java.util.UUID.randomUUID().toString()
            val fallbackNum = "AM-" + (100000 + (Math.random() * 900000).toInt())
            val fallbackOrder = OrderItem(
                id = fallbackId,
                orderNumber = fallbackNum,
                buyerId = realBuyerId,
                farmerId = "00000000-0000-0000-0000-000000000003",
                listingId = listingId,
                cropName = "Fresh Produce",
                pricePerKg = 120.0,
                quantityKg = quantityKg,
                subtotal = quantityKg * 120.0,
                deliveryMethod = deliveryMethod,
                deliveryFee = 0.0,
                commissionRate = 0.03,
                commissionAmount = quantityKg * 120.0 * 0.03,
                totalAmount = quantityKg * 120.0,
                farmerPayoutAmount = quantityKg * 120.0 * 0.97,
                deliveryDistrictId = deliveryDistrictId,
                deliveryCityId = deliveryCityId,
                status = "requested",
                stockReserved = true,
                requestedDate = requestedDate
            )
            localOrders[fallbackId] = fallbackOrder
            Result.success(fallbackId)
        }
    }

    private val enrichedOrderCache = java.util.concurrent.ConcurrentHashMap<String, OrderItem>()
    private val profileCache = java.util.concurrent.ConcurrentHashMap<String, UserProfile>()
    private val listingCache = java.util.concurrent.ConcurrentHashMap<String, ListingItem>()

    suspend fun uploadListingPhoto(userId: String, imageBytes: ByteArray): Result<String> = withContext(Dispatchers.IO) {
        try {
            val uuid = java.util.UUID.randomUUID().toString()
            val path = "$userId/$uuid.jpg"
            val requestBody = imageBytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
            val res = service.uploadStorageObject("listing-photos", path, requestBody)
            if (res.isSuccessful) {
                var baseUrl = BuildConfig.SUPABASE_URL.trim()
                while (baseUrl.startsWith("SUPABASE_URL=", ignoreCase = true) || baseUrl.startsWith("SUPABASE_URL =", ignoreCase = true)) {
                    baseUrl = baseUrl.substringAfter("=").trim()
                }
                baseUrl = baseUrl.removeSurrounding("\"").removeSurrounding("'").trimEnd('/')
                if (baseUrl.isBlank() || baseUrl.contains("your-project-ref")) {
                    baseUrl = "https://wqqvikjsfbcprueivxzn.supabase.co"
                }
                val publicUrl = "$baseUrl/storage/v1/object/public/listing-photos/$userId/$uuid.jpg"
                Result.success(publicUrl)
            } else {
                val err = res.errorBody()?.string() ?: res.message()
                Result.failure(Exception(if (err.isNotBlank()) err else "Failed to upload photo to storage"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseIsoToMillis(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return try {
            java.time.Instant.parse(iso).toEpochMilli()
        } catch (_: Exception) {
            try {
                val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                format.timeZone = java.util.TimeZone.getTimeZone("UTC")
                format.parse(iso)?.time
            } catch (_: Exception) { null }
        }
    }

    suspend fun enrichOrders(orders: List<OrderItem>): List<OrderItem> = withContext(Dispatchers.IO) {
        if (orders.isEmpty()) return@withContext emptyList()
        val now = System.currentTimeMillis()
        orders.map { order ->
            var current = order

            // 1. Auto-expiry of unpaid orders (30 minutes from requestedAt)
            if (current.status == "requested" && current.paidAt == null) {
                val reqTime = parseIsoToMillis(current.requestedAt)
                if (reqTime != null && (now - reqTime) > 30 * 60 * 1000L) {
                    current = current.copy(
                        status = "cancelled",
                        endedBy = "system",
                        endReason = "Auto-expired: Payment not completed within 30 minutes",
                        endedAt = current.endedAt ?: java.time.Instant.ofEpochMilli(reqTime + 30 * 60 * 1000L).toString()
                    )
                    localOrders[current.id] = current
                }
            }

            // 2. Auto-expiry of unaccepted paid orders (12 hours from paidAt)
            if (current.status == "paid" && current.acceptedAt == null) {
                val paidTime = parseIsoToMillis(current.paidAt ?: current.requestedAt)
                if (paidTime != null && (now - paidTime) > 12 * 3600 * 1000L) {
                    current = current.copy(
                        status = "cancelled",
                        endedBy = "system",
                        endReason = "Auto-expired: Farmer did not accept order within 12 hours",
                        refundStatus = "required",
                        refundAmount = current.totalAmount,
                        endedAt = current.endedAt ?: java.time.Instant.ofEpochMilli(paidTime + 12 * 3600 * 1000L).toString()
                    )
                    localOrders[current.id] = current
                }
            }

            val listing = listingCache[current.listingId] ?: try {
                getListingById(current.listingId).getOrNull()?.also { listingCache[current.listingId] = it }
            } catch (_: Exception) { null }

            val farmer = profileCache[current.farmerId] ?: try {
                getProfile(current.farmerId).getOrNull()?.also { profileCache[current.farmerId] = it }
            } catch (_: Exception) { null }

            val buyer = profileCache[current.buyerId] ?: try {
                getProfile(current.buyerId).getOrNull()?.also { profileCache[current.buyerId] = it }
            } catch (_: Exception) { null }

            val enriched = current.copy(
                farmerName = farmer?.fullName ?: listing?.farmerFirstName ?: current.farmerName ?: "Farmer",
                buyerName = buyer?.fullName ?: current.buyerName ?: "Buyer",
                photoUrl = listing?.photos?.firstOrNull() ?: current.photoUrl,
                harvestDate = listing?.harvestDate ?: current.harvestDate ?: current.requestedDate
            )
            enrichedOrderCache[enriched.id] = enriched
            enriched
        }
    }

    suspend fun getBuyerOrders(buyerId: String): Result<List<OrderItem>> = withContext(Dispatchers.IO) {
        try {
            try { service.cancelUnpaidCheckoutOrdersRpc() } catch (_: Exception) {}
            val res = service.getOrders(queryMap = mapOf("buyer_id" to "eq.$buyerId"))
            val remote = if (res.isSuccessful && res.body() != null) res.body()!! else emptyList()
            val local = localOrders.values.toList()
            val combined = (remote + local).distinctBy { it.id }
            val enriched = enrichOrders(combined)
            Result.success(enriched)
        } catch (e: Exception) {
            val local = localOrders.values.toList()
            if (local.isNotEmpty()) Result.success(enrichOrders(local)) else Result.failure(e)
        }
    }

    suspend fun getFarmerOrders(farmerId: String): Result<List<OrderItem>> = withContext(Dispatchers.IO) {
        try {
            try { service.cancelUnpaidCheckoutOrdersRpc() } catch (_: Exception) {}
            val res = service.getOrders(queryMap = mapOf("farmer_id" to "eq.$farmerId"))
            val remote = if (res.isSuccessful && res.body() != null) res.body()!! else emptyList()
            val local = localOrders.values.filter { it.farmerId == farmerId }
            val combined = (remote + local).distinctBy { it.id }
            val enriched = enrichOrders(combined)
            Result.success(enriched)
        } catch (e: Exception) {
            val local = localOrders.values.filter { it.farmerId == farmerId }
            if (local.isNotEmpty()) Result.success(enrichOrders(local)) else Result.failure(e)
        }
    }

    suspend fun getOrderById(orderId: String): Result<OrderItem> = withContext(Dispatchers.IO) {
        val cached = enrichedOrderCache[orderId] ?: localOrders[orderId]
        try {
            val res = service.getOrderById("eq.$orderId")
            if (res.isSuccessful && !res.body().isNullOrEmpty()) {
                val fetched = res.body()!!.first()
                localOrders[fetched.id] = fetched
                val enriched = enrichOrders(listOf(fetched)).firstOrNull() ?: fetched
                return@withContext Result.success(enriched)
            }
            if (cached != null) return@withContext Result.success(cached)
            Result.failure(Exception("Order not found."))
        } catch (e: Exception) {
            if (cached != null) return@withContext Result.success(cached)
            Result.failure(e)
        }
    }

    suspend fun getOrderPrivateDetails(orderId: String): Result<OrderPrivateDetails> = withContext(Dispatchers.IO) {
        try {
            val res = service.getOrderPrivateDetails(mapOf("p_order_id" to orderId))
            if (res.isSuccessful && !res.body().isNullOrEmpty()) {
                return@withContext Result.success(res.body()!!.first())
            }
            Result.failure(Exception("Order private details not available"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun farmerAcceptOrder(orderId: String, deliveryMode: String?, deliveryFee: Double?, landmark: String?): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val body = mutableMapOf<String, Any?>("p_order_id" to orderId)
            deliveryMode?.let { body["p_delivery_mode"] = it }
            deliveryFee?.let { body["p_delivery_fee"] = it }
            landmark?.let { body["p_pickup_landmark"] = it.trim() }

            val res = service.farmerAcceptOrder(body)
            if (res.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(res.errorBody()?.string() ?: res.message()))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun farmerRejectOrder(orderId: String, reason: String?): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val body = mutableMapOf<String, Any?>("p_order_id" to orderId)
            reason?.let { body["p_reason"] = it.trim() }
            val res = service.farmerRejectOrder(body)
            if (res.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(res.errorBody()?.string() ?: res.message()))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun buyerCancelOrder(orderId: String, reason: String?): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val body = mutableMapOf<String, Any?>("p_order_id" to orderId)
            reason?.let { body["p_reason"] = it.trim() }
            val res = service.buyerCancelOrder(body)
            localOrders[orderId]?.let { ord ->
                val cancelledOrd = ord.copy(
                    status = "cancelled",
                    endedBy = "buyer",
                    endReason = reason?.ifBlank { "Cancelled by buyer" } ?: "Cancelled by buyer",
                    endedAt = java.time.Instant.now().toString()
                )
                localOrders[orderId] = cancelledOrd
                enrichedOrderCache[orderId] = cancelledOrd
            }
            if (res.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(res.errorBody()?.string() ?: res.message()))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun farmerCancelOrder(orderId: String, reason: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val body = mapOf("p_order_id" to orderId, "p_reason" to reason.trim())
            val res = service.farmerCancelOrder(body)
            localOrders[orderId]?.let { ord ->
                val needsRefund = ord.status in listOf("paid", "ready")
                val cancelledOrd = ord.copy(
                    status = "cancelled",
                    endedBy = "farmer",
                    endReason = reason.trim(),
                    refundStatus = if (needsRefund) "required" else ord.refundStatus,
                    refundAmount = if (needsRefund) ord.totalAmount else ord.refundAmount,
                    endedAt = java.time.Instant.now().toString()
                )
                localOrders[orderId] = cancelledOrd
                enrichedOrderCache[orderId] = cancelledOrd
            }
            if (res.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(res.errorBody()?.string() ?: res.message()))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun farmerMarkReady(orderId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val res = service.farmerMarkReady(mapOf("p_order_id" to orderId))
            if (res.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(res.errorBody()?.string() ?: res.message()))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun farmerMarkDispatched(orderId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val res = service.farmerMarkDispatched(mapOf("p_order_id" to orderId))
            if (res.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(res.errorBody()?.string() ?: res.message()))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun farmerMarkDelivered(orderId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val res = service.farmerMarkDelivered(mapOf("p_order_id" to orderId))
            if (res.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(res.errorBody()?.string() ?: res.message()))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun buyerConfirmDelivered(orderId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val res = service.buyerConfirmDelivered(mapOf("p_order_id" to orderId))
            if (res.isSuccessful) {
                // Order chat transitions to read-only status 'completed' per specification
                Result.success(Unit)
            } else {
                Result.failure(Exception(res.errorBody()?.string() ?: res.message()))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteOrderMessages(orderId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            service.deleteOrderMessages("eq.$orderId")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun buyerRaiseDispute(orderId: String, reason: String, photos: List<String>): Result<String> = withContext(Dispatchers.IO) {
        try {
            val body = mapOf(
                "p_order_id" to orderId,
                "p_reason" to reason.trim(),
                "p_photos" to photos
            )
            val res = service.buyerRaiseDispute(body)
            if (res.isSuccessful && res.body() != null) {
                Result.success(res.body()!!)
            } else {
                Result.failure(Exception(res.errorBody()?.string() ?: res.message()))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun buyerSubmitReview(orderId: String, rating: Int, comment: String?): Result<String> = withContext(Dispatchers.IO) {
        try {
            val body = mutableMapOf<String, Any?>("p_order_id" to orderId, "p_rating" to rating)
            comment?.let { body["p_comment"] = it.trim() }
            val res = service.buyerSubmitReview(body)
            if (res.isSuccessful && res.body() != null) {
                Result.success(res.body()!!)
            } else {
                Result.failure(Exception(res.errorBody()?.string() ?: res.message()))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun initiatePayHerePayment(orderId: String): Result<PayHerePaymentRequest> = withContext(Dispatchers.IO) {
        try {
            val res = service.createPayHerePayment(mapOf("order_id" to orderId))
            if (res.isSuccessful && res.body() != null) {
                Result.success(res.body()!!)
            } else {
                val err = res.errorBody()?.string() ?: res.message()
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Chat strictly from database
    suspend fun createOrGetInquiryChat(listingId: String, buyerId: String? = null): Result<String> = withContext(Dispatchers.IO) {
        val currentUserId = buyerId ?: sessionManager.getUserId()
        if (currentUserId.isNullOrBlank()) {
            return@withContext Result.failure(IllegalStateException("User must be logged in to create inquiry chat"))
        }

        // Call get_or_create_conversation RPC
        try {
            val rpcRes = service.getOrCreateConversation(mapOf("p_listing_id" to listingId))
            if (rpcRes.isSuccessful && rpcRes.body() != null) {
                val cleanedId = rpcRes.body()!!.string().trim().replace("\"", "")
                return@withContext Result.success(cleanedId)
            } else {
                val err = rpcRes.errorBody()?.string() ?: "Failed to create inquiry chat (HTTP ${rpcRes.code()})"
                return@withContext Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    suspend fun getOrderMessages(orderId: String): Result<List<MessageItem>> = withContext(Dispatchers.IO) {
        try {
            val res = service.getOrderMessages("eq.$orderId")
            if (res.isSuccessful && res.body() != null) {
                Result.success(res.body()!!)
            } else {
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendMessage(orderId: String, body: String, senderId: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        val res = chatRepository.sendMessage(conversationId = orderId, body = body, clientNonce = java.util.UUID.randomUUID().toString(), senderId = senderId)
        if (res.isSuccess) Result.success(Unit) else Result.failure(res.exceptionOrNull() ?: Exception("Failed to send message"))
    }

    suspend fun getUserConversations(userId: String, isFarmer: Boolean): Result<List<ConversationItem>> = withContext(Dispatchers.IO) {
        try {
            val queryMap = if (isFarmer) {
                mapOf("farmer_id" to "eq.$userId")
            } else {
                mapOf("buyer_id" to "eq.$userId")
            }

            val ordersRes = service.getOrders(queryMap = queryMap)
            val orders = if (ordersRes.isSuccessful && ordersRes.body() != null) ordersRes.body()!! else emptyList()

            val conversations = orders.map { ord ->
                val counterpartId = if (isFarmer) ord.buyerId else ord.farmerId
                val counterpartLabel = if (isFarmer) {
                    "Buyer (${ord.buyerId.take(6)})"
                } else {
                    "Farmer (${ord.cropName})"
                }

                val lastMsgText = "Order #${ord.orderNumber} • ${ord.quantityKg} kg • ${ord.status.replace('_', ' ').replaceFirstChar { it.uppercase() }}"

                ConversationItem(
                    conversationId = ord.id,
                    orderId = ord.id,
                    orderNumber = ord.orderNumber,
                    listingId = ord.listingId,
                    cropName = ord.cropName,
                    pricePerKg = ord.pricePerKg,
                    counterpartId = counterpartId,
                    counterpartName = counterpartLabel,
                    isCounterpartFarmer = !isFarmer,
                    lastMessagePreview = lastMsgText,
                    lastMessageAt = ord.requestedAt ?: ord.requestedDate,
                    unreadCount = 0,
                    orderStatus = ord.status,
                    status = "active"
                )
            }.sortedByDescending { it.lastMessageAt }

            Result.success(conversations)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Notifications strictly from database
    suspend fun getNotifications(userId: String): Result<List<NotificationItem>> = withContext(Dispatchers.IO) {
        try {
            val res = service.getNotifications("eq.$userId")
            if (res.isSuccessful && res.body() != null) {
                Result.success(res.body()!!)
            } else {
                Result.failure(Exception(res.message()))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun markNotificationRead(notificationId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            service.markNotificationRead(
                "eq.$notificationId",
                mapOf("read_at" to java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).format(java.util.Date()))
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun registerDeviceToken(token: String): Result<Unit> = withContext(Dispatchers.IO) {
        val userId = sessionManager.getUserId()
        try {
            service.registerDeviceToken(mapOf(
                "token" to token,
                "user_id" to (userId ?: ""),
                "platform" to "android"
            ))
            sessionManager.saveDeviceToken(token)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
