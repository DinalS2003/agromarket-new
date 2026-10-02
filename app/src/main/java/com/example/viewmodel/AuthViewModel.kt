package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.models.City
import com.example.data.models.District
import com.example.data.repository.AgroMarketRepository
import com.example.data.repository.NeedsUpgradeException
import com.example.ui.components.toUserFriendlyMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class UsernameCheckStatus {
    IDLE,
    CHECKING,
    AVAILABLE,
    TAKEN,
    INVALID
}

enum class PasswordStrength(val label: String) {
    NONE(""),
    WEAK("Weak"),
    FAIR("Fair"),
    GOOD("Good"),
    STRONG("Strong")
}

fun evaluatePasswordStrength(pass: String): PasswordStrength {
    if (pass.isEmpty()) return PasswordStrength.NONE
    if (pass.length < 8) return PasswordStrength.WEAK
    var score = 0
    if (pass.length >= 8) score++
    if (pass.length >= 10) score++
    if (pass.any { it.isUpperCase() } && pass.any { it.isLowerCase() }) score++
    if (pass.any { it.isDigit() }) score++
    if (pass.any { !it.isLetterOrDigit() }) score++
    return when {
        score <= 2 -> PasswordStrength.WEAK
        score == 3 -> PasswordStrength.FAIR
        score == 4 -> PasswordStrength.GOOD
        else -> PasswordStrength.STRONG
    }
}

// Legacy AuthUiState retained for backwards-compatibility with tests & views
data class AuthUiState(
    val phoneInput: String = "",
    val phoneError: String? = null,
    val isOtpSent: Boolean = false,
    val otpCode: String = "",
    val otpError: String? = null,
    val resendCountdown: Int = 0,
    val isLoading: Boolean = false,
    val isSessionReady: Boolean = false,
    val needsOnboarding: Boolean = false,
    val isSuspended: Boolean = false,
    val suspendedReason: String? = null,
    val otpReferenceId: String? = null,
    val otpStatusMessage: String? = null
)

data class OnboardingUiState(
    val currentStep: Int = 1,
    val fullName: String = "",
    val nic: String = "",
    val nicError: String? = null,
    val selectedDistrictId: Int = 1,
    val selectedCityId: Int = 1,
    val districts: List<District> = emptyList(),
    val cities: List<City> = emptyList(),
    val isLoadingCities: Boolean = false,
    val isFarmerMode: Boolean = false,
    val cultivationDistrictId: Int = 1,
    val cultivationCityId: Int = 1,
    val cultivationCities: List<City> = emptyList(),
    val cultivationAddress: String = "",
    val mainCrops: List<String> = emptyList(),
    val customCropInput: String = "",
    val landSize: String = "",
    val landUnit: String = "acres",
    val defaultPickupLandmark: String = "",
    val errorMessage: String? = null,
    val isSubmitting: Boolean = false,
    val isComplete: Boolean = false
)

// Part 1b & 1c: Login & One-time Upgrade State
data class LoginUiState(
    val identifier: String = "",
    val password: String = "",
    val isPasswordVisible: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val failedAttempts: Int = 0,
    val lockoutSecondsRemaining: Int = 0,
    val isSessionReady: Boolean = false,
    val needsOnboarding: Boolean = false,
    val isSuspended: Boolean = false,
    val suspendedReason: String? = null,
    // One-time upgrade flow for existing users
    val isUpgradeFlowActive: Boolean = false,
    val upgradeUserId: String = "",
    val upgradePhone: String = "",
    val upgradeMaskedPhone: String = "",
    val upgradeOtpCode: String = "",
    val upgradeOtpSent: Boolean = false,
    val upgradeOtpCountdown: Int = 0,
    val upgradeOtpError: String? = null,
    val upgradeOtpVerified: Boolean = false,
    val upgradeUsername: String = "",
    val upgradeUsernameStatus: UsernameCheckStatus = UsernameCheckStatus.IDLE,
    val upgradeUsernameError: String? = null,
    val upgradePassword: String = "",
    val upgradeConfirmPassword: String = "",
    val upgradePasswordVisible: Boolean = false,
    val upgradePasswordStrength: PasswordStrength = PasswordStrength.NONE,
    val upgradeError: String? = null,
    val isUpgrading: Boolean = false,
    val isUpgradeComplete: Boolean = false
)

// Part 1a: Multi-step Sign Up State (Username -> Details -> Mobile/Pass -> OTP -> Complete)
data class SignUpUiState(
    val currentStep: Int = 1, // 1: Username, 2: Personal Details & Role, 3: Mobile & Password, 4: OTP Verification
    // Step 1: Username
    val username: String = "",
    val usernameStatus: UsernameCheckStatus = UsernameCheckStatus.IDLE,
    val usernameError: String? = null,
    // Step 2: Personal details & role
    val fullName: String = "",
    val nic: String = "",
    val nicError: String? = null,
    val selectedDistrictId: Int = 1,
    val selectedCityId: Int = 1,
    val districts: List<District> = emptyList(),
    val cities: List<City> = emptyList(),
    val isLoadingCities: Boolean = false,
    val isFarmer: Boolean = false,
    // Farmer cultivation fields
    val cultivationDistrictId: Int = 1,
    val cultivationCityId: Int = 1,
    val cultivationCities: List<City> = emptyList(),
    val cultivationAddress: String = "",
    val mainCrops: List<String> = emptyList(),
    val customCropInput: String = "",
    val landSize: String = "",
    val landUnit: String = "acres",
    val defaultPickupLandmark: String = "",
    // Step 3: Mobile & Password
    val phone: String = "",
    val phoneError: String? = null,
    val password: String = "",
    val confirmPassword: String = "",
    val isPasswordVisible: Boolean = false,
    val isConfirmPasswordVisible: Boolean = false,
    val passwordStrength: PasswordStrength = PasswordStrength.NONE,
    val passwordError: String? = null,
    // Step 4: OTP Verification
    val isOtpSent: Boolean = false,
    val otpCode: String = "",
    val otpError: String? = null,
    val resendCountdown: Int = 0,
    val otpReferenceId: String? = null,
    val otpStatusMessage: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isComplete: Boolean = false
)

// Part 1d: Forgot Password State
data class ForgotPasswordUiState(
    val isOpen: Boolean = false,
    val identifier: String = "",
    val step: Int = 1, // 1: Identifier lookup, 2: OTP, 3: New Password
    val userId: String = "",
    val phone: String = "",
    val maskedPhone: String = "",
    val otpCode: String = "",
    val otpSent: Boolean = false,
    val otpCountdown: Int = 0,
    val otpError: String? = null,
    val newPassword: String = "",
    val confirmNewPassword: String = "",
    val isPasswordVisible: Boolean = false,
    val passwordStrength: PasswordStrength = PasswordStrength.NONE,
    val passwordError: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isSuccess: Boolean = false
)

class AuthViewModel(
    private val repository: AgroMarketRepository
) : ViewModel() {

    // States
    private val _authState = MutableStateFlow(AuthUiState())
    val authState: StateFlow<AuthUiState> = _authState.asStateFlow()

    private val _onboardingState = MutableStateFlow(OnboardingUiState())
    val onboardingState: StateFlow<OnboardingUiState> = _onboardingState.asStateFlow()

    private val _loginState = MutableStateFlow(LoginUiState())
    val loginState: StateFlow<LoginUiState> = _loginState.asStateFlow()

    private val _signUpState = MutableStateFlow(SignUpUiState())
    val signUpState: StateFlow<SignUpUiState> = _signUpState.asStateFlow()

    private val _forgotPasswordState = MutableStateFlow(ForgotPasswordUiState())
    val forgotPasswordState: StateFlow<ForgotPasswordUiState> = _forgotPasswordState.asStateFlow()

    private var countdownJob: Job? = null
    private var lockoutJob: Job? = null
    private var usernameCheckJob: Job? = null
    private var upgradeUsernameCheckJob: Job? = null
    private var forgotCountdownJob: Job? = null
    private var upgradeCountdownJob: Job? = null

    val commonCropSuggestions = listOf(
        "Tomato", "Carrot", "Beans", "Cabbage", "Leeks", "Brinjal", "Pumpkin", "Onion",
        "Red Onion", "Big Onion", "Green Chilli", "Potato", "Beetroot", "Banana", "Papaya"
    )

    init {
        loadDistrictsAndCities()
    }

    fun resetAuth() {
        countdownJob?.cancel()
        lockoutJob?.cancel()
        usernameCheckJob?.cancel()
        upgradeUsernameCheckJob?.cancel()
        forgotCountdownJob?.cancel()
        upgradeCountdownJob?.cancel()

        _authState.value = AuthUiState()
        _onboardingState.value = OnboardingUiState()
        _loginState.value = LoginUiState()
        _signUpState.value = SignUpUiState(districts = _onboardingState.value.districts)
        _forgotPasswordState.value = ForgotPasswordUiState()
        loadDistrictsAndCities()
    }

    private fun loadDistrictsAndCities() {
        viewModelScope.launch {
            val distRes = repository.getDistricts()
            distRes.onSuccess { list ->
                _onboardingState.update { it.copy(districts = list) }
                _signUpState.update { it.copy(districts = list) }
                if (list.isNotEmpty()) {
                    loadCitiesForDistrict(list.first().id)
                }
            }
        }
    }

    fun onDistrictSelected(districtId: Int) {
        _onboardingState.update { it.copy(selectedDistrictId = districtId) }
        _signUpState.update { it.copy(selectedDistrictId = districtId) }
        loadCitiesForDistrict(districtId)
    }

    fun onCultivationDistrictSelected(districtId: Int) {
        _onboardingState.update { it.copy(cultivationDistrictId = districtId) }
        _signUpState.update { it.copy(cultivationDistrictId = districtId) }
        viewModelScope.launch {
            val res = repository.getCities(districtId)
            res.onSuccess { cities ->
                _onboardingState.update {
                    it.copy(
                        cultivationCities = cities,
                        cultivationCityId = cities.firstOrNull()?.id ?: 1
                    )
                }
                _signUpState.update {
                    it.copy(
                        cultivationCities = cities,
                        cultivationCityId = cities.firstOrNull()?.id ?: 1
                    )
                }
            }
        }
    }

    private fun loadCitiesForDistrict(districtId: Int) {
        viewModelScope.launch {
            _onboardingState.update { it.copy(isLoadingCities = true) }
            _signUpState.update { it.copy(isLoadingCities = true) }
            val res = repository.getCities(districtId)
            res.onSuccess { cities ->
                _onboardingState.update {
                    it.copy(
                        cities = cities,
                        selectedCityId = cities.firstOrNull()?.id ?: 1,
                        isLoadingCities = false
                    )
                }
                _signUpState.update {
                    it.copy(
                        cities = cities,
                        selectedCityId = cities.firstOrNull()?.id ?: 1,
                        isLoadingCities = false
                    )
                }
            }.onFailure {
                _onboardingState.update { it.copy(isLoadingCities = false) }
                _signUpState.update { it.copy(isLoadingCities = false) }
            }
        }
    }

    // ==========================================
    // 1b. LOG IN (RETURNING USERS) & 1c. UPGRADE
    // ==========================================

    fun onLoginIdentifierChange(input: String) {
        _loginState.update { it.copy(identifier = input, errorMessage = null) }
    }

    fun onLoginPasswordChange(input: String) {
        _loginState.update { it.copy(password = input, errorMessage = null) }
    }

    fun toggleLoginPasswordVisibility() {
        _loginState.update { it.copy(isPasswordVisible = !it.isPasswordVisible) }
    }

    fun submitLogin(onSuccess: () -> Unit, onNeedsOnboarding: () -> Unit) {
        val state = _loginState.value
        if (state.lockoutSecondsRemaining > 0) {
            _loginState.update {
                it.copy(errorMessage = "Too many failed attempts. Please wait ${state.lockoutSecondsRemaining} seconds.")
            }
            return
        }

        val id = state.identifier.trim()
        val pass = state.password.trim()

        if (id.isBlank()) {
            _loginState.update { it.copy(errorMessage = "Please enter your username or mobile number.") }
            return
        }
        if (pass.isBlank()) {
            _loginState.update { it.copy(errorMessage = "Please enter your password.") }
            return
        }

        viewModelScope.launch {
            _loginState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.loginWithCredentials(id, pass)

            res.onSuccess {
                _loginState.update {
                    it.copy(
                        isLoading = false,
                        failedAttempts = 0,
                        isSessionReady = true,
                        errorMessage = null
                    )
                }
                _authState.update { it.copy(isSessionReady = true) }
                onSuccess()
            }.onFailure { err ->
                if (err is NeedsUpgradeException) {
                    // 1c. Existing user created under old OTP system without credentials
                    _loginState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = null,
                            isUpgradeFlowActive = true,
                            upgradeUserId = err.userId,
                            upgradePhone = err.phone,
                            upgradeMaskedPhone = err.maskedPhone,
                            upgradeOtpSent = false,
                            upgradeOtpVerified = false
                        )
                    }
                    startUpgradeOtp()
                } else {
                    val newFailCount = _loginState.value.failedAttempts + 1
                    val shouldLockout = newFailCount >= 5
                    _loginState.update {
                        it.copy(
                            isLoading = false,
                            failedAttempts = newFailCount,
                            errorMessage = err.message ?: "Incorrect username or password",
                            lockoutSecondsRemaining = if (shouldLockout) 30 else 0
                        )
                    }
                    if (shouldLockout) {
                        startLockoutCountdown()
                    }
                }
            }
        }
    }

    private fun startLockoutCountdown() {
        lockoutJob?.cancel()
        lockoutJob = viewModelScope.launch {
            while (_loginState.value.lockoutSecondsRemaining > 0) {
                delay(1000)
                _loginState.update { it.copy(lockoutSecondsRemaining = it.lockoutSecondsRemaining - 1) }
            }
        }
    }

    // One-time Upgrade Flow Handlers
    fun startUpgradeOtp() {
        val phone = _loginState.value.upgradePhone
        if (phone.isBlank()) return

        viewModelScope.launch {
            _loginState.update { it.copy(isUpgrading = true, upgradeOtpError = null) }
            val res = repository.sendOtpWithTextLk(phone)
            if (res.success) {
                _loginState.update {
                    it.copy(
                        isUpgrading = false,
                        upgradeOtpSent = true,
                        upgradeOtpCountdown = 60,
                        upgradeOtpError = null
                    )
                }
                startUpgradeCountdown()
            } else {
                _loginState.update {
                    it.copy(
                        isUpgrading = false,
                        upgradeOtpError = res.errorMessage ?: res.displayMessage
                    )
                }
            }
        }
    }

    private fun startUpgradeCountdown() {
        upgradeCountdownJob?.cancel()
        upgradeCountdownJob = viewModelScope.launch {
            while (_loginState.value.upgradeOtpCountdown > 0) {
                delay(1000)
                _loginState.update { it.copy(upgradeOtpCountdown = it.upgradeOtpCountdown - 1) }
            }
        }
    }

    fun onUpgradeOtpChange(code: String) {
        if (code.length <= 6) {
            _loginState.update { it.copy(upgradeOtpCode = code, upgradeOtpError = null) }
        }
    }

    fun verifyUpgradeOtp() {
        val code = _loginState.value.upgradeOtpCode.trim()
        val phone = _loginState.value.upgradePhone
        if (code.length < 6) {
            _loginState.update { it.copy(upgradeOtpError = "Please enter the 6-digit verification code.") }
            return
        }

        viewModelScope.launch {
            _loginState.update { it.copy(isUpgrading = true, upgradeOtpError = null) }
            val res = repository.verifyOtp(phone, code)
            res.onSuccess {
                _loginState.update {
                    it.copy(
                        isUpgrading = false,
                        upgradeOtpVerified = true,
                        upgradeOtpError = null
                    )
                }
            }.onFailure { err ->
                _loginState.update {
                    it.copy(
                        isUpgrading = false,
                        upgradeOtpError = err.message ?: "Invalid verification code."
                    )
                }
            }
        }
    }

    fun onUpgradeUsernameChange(input: String) {
        val clean = input.trim().lowercase()
        _loginState.update {
            it.copy(
                upgradeUsername = clean,
                upgradeUsernameError = null,
                upgradeUsernameStatus = if (clean.length < 3) UsernameCheckStatus.INVALID else UsernameCheckStatus.CHECKING
            )
        }

        usernameCheckJob?.cancel()
        if (clean.length in 3..20 && clean.matches(Regex("^[a-z0-9_]{3,20}$"))) {
            usernameCheckJob = viewModelScope.launch {
                delay(350)
                val check = repository.checkUsernameAvailable(clean)
                check.onSuccess { available ->
                    _loginState.update {
                        it.copy(
                            upgradeUsernameStatus = if (available) UsernameCheckStatus.AVAILABLE else UsernameCheckStatus.TAKEN,
                            upgradeUsernameError = if (!available) "Username is already taken" else null
                        )
                    }
                }.onFailure {
                    _loginState.update { it.copy(upgradeUsernameStatus = UsernameCheckStatus.IDLE) }
                }
            }
        } else if (clean.isNotEmpty()) {
            _loginState.update {
                it.copy(
                    upgradeUsernameStatus = UsernameCheckStatus.INVALID,
                    upgradeUsernameError = "Must be 3-20 characters: letters, numbers, or _"
                )
            }
        }
    }

    fun onUpgradePasswordChange(input: String) {
        val strength = evaluatePasswordStrength(input)
        _loginState.update {
            it.copy(
                upgradePassword = input,
                upgradePasswordStrength = strength,
                upgradeError = null
            )
        }
    }

    fun onUpgradeConfirmPasswordChange(input: String) {
        _loginState.update {
            it.copy(
                upgradeConfirmPassword = input,
                upgradeError = null
            )
        }
    }

    fun toggleUpgradePasswordVisibility() {
        _loginState.update { it.copy(upgradePasswordVisible = !it.upgradePasswordVisible) }
    }

    fun submitUpgradeCredentials(onSuccess: () -> Unit) {
        val state = _loginState.value
        val username = state.upgradeUsername.trim().lowercase()
        val pass = state.upgradePassword
        val confirm = state.upgradeConfirmPassword

        if (state.upgradeUsernameStatus != UsernameCheckStatus.AVAILABLE) {
            _loginState.update { it.copy(upgradeError = "Please choose an available username.") }
            return
        }
        if (pass.length < 8) {
            _loginState.update { it.copy(upgradeError = "Password must be at least 8 characters long.") }
            return
        }
        if (pass != confirm) {
            _loginState.update { it.copy(upgradeError = "Passwords do not match.") }
            return
        }

        viewModelScope.launch {
            _loginState.update { it.copy(isUpgrading = true, upgradeError = null) }
            val res = repository.upgradeUserCredentials(state.upgradeUserId, username, pass)
            res.onSuccess {
                _loginState.update {
                    it.copy(
                        isUpgrading = false,
                        isUpgradeComplete = true,
                        isUpgradeFlowActive = false,
                        isSessionReady = true
                    )
                }
                _authState.update { it.copy(isSessionReady = true) }
                onSuccess()
            }.onFailure { err ->
                _loginState.update {
                    it.copy(
                        isUpgrading = false,
                        upgradeError = err.message ?: "Failed to set up credentials. Please try again."
                    )
                }
            }
        }
    }

    fun cancelUpgradeFlow() {
        upgradeCountdownJob?.cancel()
        _loginState.update { it.copy(isUpgradeFlowActive = false) }
    }

    // ==========================================
    // 1a. SIGN UP (NEW USERS)
    // ==========================================

    fun onSignUpUsernameChange(input: String) {
        val clean = input.trim().lowercase()
        _signUpState.update {
            it.copy(
                username = clean,
                usernameError = null,
                usernameStatus = if (clean.length < 3) UsernameCheckStatus.INVALID else UsernameCheckStatus.CHECKING
            )
        }

        usernameCheckJob?.cancel()
        if (clean.length in 3..20 && clean.matches(Regex("^[a-z0-9_]{3,20}$"))) {
            usernameCheckJob = viewModelScope.launch {
                delay(350)
                val check = repository.checkUsernameAvailable(clean)
                check.onSuccess { available ->
                    _signUpState.update {
                        it.copy(
                            usernameStatus = if (available) UsernameCheckStatus.AVAILABLE else UsernameCheckStatus.TAKEN,
                            usernameError = if (!available) "Username is already taken" else null
                        )
                    }
                }.onFailure {
                    _signUpState.update { it.copy(usernameStatus = UsernameCheckStatus.IDLE) }
                }
            }
        } else if (clean.isNotEmpty()) {
            _signUpState.update {
                it.copy(
                    usernameStatus = UsernameCheckStatus.INVALID,
                    usernameError = "Must be 3-20 characters: letters, numbers, or _"
                )
            }
        }
    }

    fun proceedFromUsernameStep(): Boolean {
        val state = _signUpState.value
        if (state.usernameStatus != UsernameCheckStatus.AVAILABLE) {
            _signUpState.update {
                it.copy(usernameError = if (it.username.length < 3) "Username must be at least 3 characters" else "Please choose an available username")
            }
            return false
        }
        _signUpState.update { it.copy(currentStep = 2, errorMessage = null) }
        return true
    }

    fun onSignUpFullNameChange(name: String) {
        _signUpState.update { it.copy(fullName = name, errorMessage = null) }
    }

    fun onSignUpNicChange(nic: String) {
        _signUpState.update { it.copy(nic = nic.uppercase(), nicError = null, errorMessage = null) }
    }

    fun onSignUpCitySelected(cityId: Int) {
        _signUpState.update { it.copy(selectedCityId = cityId) }
    }

    fun setSignUpAccountType(isFarmer: Boolean) {
        _signUpState.update { it.copy(isFarmer = isFarmer, errorMessage = null) }
        if (isFarmer && _signUpState.value.cultivationCities.isEmpty()) {
            onCultivationDistrictSelected(_signUpState.value.selectedDistrictId)
        }
    }

    fun onSignUpCultivationAddressChange(addr: String) {
        _signUpState.update { it.copy(cultivationAddress = addr) }
    }

    fun addSignUpCropChip(crop: String) {
        val trimmed = crop.trim()
        if (trimmed.length in 2..30 && !_signUpState.value.mainCrops.contains(trimmed) && _signUpState.value.mainCrops.size < 15) {
            _signUpState.update {
                it.copy(
                    mainCrops = it.mainCrops + trimmed,
                    customCropInput = ""
                )
            }
        }
    }

    fun removeSignUpCropChip(crop: String) {
        _signUpState.update { it.copy(mainCrops = it.mainCrops - crop) }
    }

    fun onSignUpCustomCropInputChange(input: String) {
        _signUpState.update { it.copy(customCropInput = input) }
    }

    fun onSignUpLandSizeChange(size: String) {
        _signUpState.update { it.copy(landSize = size) }
    }

    fun onSignUpLandUnitChange(unit: String) {
        _signUpState.update { it.copy(landUnit = unit) }
    }

    fun onSignUpPickupLandmarkChange(landmark: String) {
        _signUpState.update { it.copy(defaultPickupLandmark = landmark) }
    }

    fun proceedFromDetailsStep(): Boolean {
        val state = _signUpState.value
        val name = state.fullName.trim()
        val nic = state.nic.trim().uppercase()

        if (name.length < 2) {
            _signUpState.update { it.copy(errorMessage = "Please enter your full name.") }
            return false
        }

        val isValidNic = nic.matches(Regex("^[0-9]{9}[VX]$")) || nic.matches(Regex("^[0-9]{12}$"))
        if (!isValidNic) {
            _signUpState.update {
                it.copy(
                    nicError = "Invalid NIC format. Must be 9 digits + V/X or 12 digits.",
                    errorMessage = "Please enter a valid National Identity Card (NIC) number."
                )
            }
            return false
        }

        if (state.isFarmer) {
            if (state.cultivationAddress.trim().isBlank()) {
                _signUpState.update { it.copy(errorMessage = "Please enter your cultivation / farm address.") }
                return false
            }
            if (state.mainCrops.isEmpty()) {
                _signUpState.update { it.copy(errorMessage = "Please select at least 1 crop that you grow.") }
                return false
            }
        }

        _signUpState.update { it.copy(currentStep = 3, errorMessage = null) }
        return true
    }

    fun onSignUpPhoneChange(phone: String) {
        _signUpState.update { it.copy(phone = phone, phoneError = null, errorMessage = null) }
    }

    fun onSignUpPasswordChange(pass: String) {
        val strength = evaluatePasswordStrength(pass)
        _signUpState.update {
            it.copy(
                password = pass,
                passwordStrength = strength,
                passwordError = null,
                errorMessage = null
            )
        }
    }

    fun onSignUpConfirmPasswordChange(confirm: String) {
        _signUpState.update {
            it.copy(confirmPassword = confirm, passwordError = null, errorMessage = null) }
    }

    fun toggleSignUpPasswordVisibility() {
        _signUpState.update { it.copy(isPasswordVisible = !it.isPasswordVisible) }
    }

    fun toggleSignUpConfirmPasswordVisibility() {
        _signUpState.update { it.copy(isConfirmPasswordVisible = !it.isConfirmPasswordVisible) }
    }

    fun requestSignUpOtp(): Boolean {
        val state = _signUpState.value
        val phone = state.phone.trim()
        val normalized = repository.normalizeSriLankanPhone(phone)

        if (!normalized.matches(Regex("^\\+947[0-9]{8}$"))) {
            _signUpState.update { it.copy(phoneError = "Please enter a valid Sri Lankan mobile (e.g. 0771234567)") }
            return false
        }

        if (state.password.length < 8) {
            _signUpState.update { it.copy(passwordError = "Password must be at least 8 characters long.") }
            return false
        }

        if (state.password != state.confirmPassword) {
            _signUpState.update { it.copy(passwordError = "Passwords do not match.") }
            return false
        }

        viewModelScope.launch {
            _signUpState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.sendOtpWithTextLk(normalized)
            if (res.success) {
                _signUpState.update {
                    it.copy(
                        isLoading = false,
                        currentStep = 4,
                        isOtpSent = true,
                        otpCode = "",
                        otpError = null,
                        resendCountdown = 60,
                        otpReferenceId = res.referenceId,
                        otpStatusMessage = res.displayMessage
                    )
                }
                startSignUpCountdown()
            } else {
                _signUpState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = res.errorMessage ?: res.displayMessage
                    )
                }
            }
        }
        return true
    }

    private fun startSignUpCountdown() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            while (_signUpState.value.resendCountdown > 0) {
                delay(1000)
                _signUpState.update { it.copy(resendCountdown = it.resendCountdown - 1) }
            }
        }
    }

    fun onSignUpOtpChange(code: String) {
        if (code.length <= 6) {
            _signUpState.update { it.copy(otpCode = code, otpError = null) }
        }
    }

    fun verifySignUpOtp(onSuccess: (isFarmer: Boolean) -> Unit) {
        val state = _signUpState.value
        val code = state.otpCode.trim()
        if (code.length < 6) {
            _signUpState.update { it.copy(otpError = "Please enter the 6-digit code.") }
            return
        }

        viewModelScope.launch {
            _signUpState.update { it.copy(isLoading = true, otpError = null) }
            val phone = repository.normalizeSriLankanPhone(state.phone)
            val refId = state.otpReferenceId

            val otpRes = repository.verifyOtp(phone, code, refId)
            if (otpRes.isFailure) {
                _signUpState.update {
                    it.copy(
                        isLoading = false,
                        otpError = otpRes.exceptionOrNull()?.message ?: "Invalid verification code. Please check and retry."
                    )
                }
                return@launch
            }

            // Create account with credentials in Supabase Auth & Profiles
            val regRes = repository.registerUserWithCredentials(
                username = state.username,
                password = state.password,
                fullName = state.fullName,
                nic = state.nic,
                phone = phone,
                districtId = state.selectedDistrictId,
                cityId = state.selectedCityId
            )

            if (regRes.isFailure) {
                _signUpState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = regRes.exceptionOrNull()?.message ?: "Account registration failed. Please try again."
                    )
                }
                return@launch
            }

            // Register Farmer profile if selected
            if (state.isFarmer) {
                val farmRes = repository.registerFarmer(
                    cultivationDistrictId = state.cultivationDistrictId,
                    cultivationCityId = state.cultivationCityId,
                    cultivationAddress = state.cultivationAddress,
                    mainCrops = state.mainCrops,
                    landSize = state.landSize.toDoubleOrNull(),
                    landUnit = state.landUnit,
                    defaultPickupLandmark = state.defaultPickupLandmark.ifBlank { null }
                )
                if (farmRes.isFailure) {
                    _signUpState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = farmRes.exceptionOrNull()?.message ?: "Farmer registration could not be saved."
                        )
                    }
                    return@launch
                }
            }

            _signUpState.update {
                it.copy(isLoading = false, isComplete = true)
            }
            _authState.update { it.copy(isSessionReady = true) }
            onSuccess(state.isFarmer)
        }
    }

    fun stepBackInSignUp() {
        _signUpState.update {
            val prev = (it.currentStep - 1).coerceAtLeast(1)
            it.copy(currentStep = prev, errorMessage = null)
        }
    }

    // ==========================================
    // 1d. FORGOT PASSWORD
    // ==========================================

    fun openForgotPassword() {
        _forgotPasswordState.value = ForgotPasswordUiState(isOpen = true)
    }

    fun closeForgotPassword() {
        forgotCountdownJob?.cancel()
        _forgotPasswordState.value = ForgotPasswordUiState(isOpen = false)
    }

    fun onForgotIdentifierChange(input: String) {
        _forgotPasswordState.update { it.copy(identifier = input, errorMessage = null) }
    }

    fun requestForgotOtp() {
        val id = _forgotPasswordState.value.identifier.trim()
        if (id.isBlank()) {
            _forgotPasswordState.update { it.copy(errorMessage = "Please enter your username or registered mobile number.") }
            return
        }

        viewModelScope.launch {
            _forgotPasswordState.update { it.copy(isLoading = true, errorMessage = null) }
            val lookup = repository.lookupLoginAccount(id)
            lookup.onSuccess { data ->
                val status = data["status"] as? String
                if (status == "NOT_FOUND") {
                    _forgotPasswordState.update {
                        it.copy(isLoading = false, errorMessage = "No account found matching this username or phone.")
                    }
                    return@launch
                }

                val phone = data["phone_e164"] as? String ?: ""
                val userId = data["user_id"] as? String ?: ""
                val masked = data["masked_phone"] as? String ?: "your phone"

                if (phone.isBlank()) {
                    _forgotPasswordState.update {
                        it.copy(isLoading = false, errorMessage = "No mobile number linked with this account.")
                    }
                    return@launch
                }

                val sendRes = repository.sendOtpWithTextLk(phone)
                if (sendRes.success) {
                    _forgotPasswordState.update {
                        it.copy(
                            isLoading = false,
                            step = 2,
                            userId = userId,
                            phone = phone,
                            maskedPhone = masked,
                            otpSent = true,
                            otpCountdown = 60
                        )
                    }
                    startForgotCountdown()
                } else {
                    _forgotPasswordState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = sendRes.errorMessage ?: sendRes.displayMessage
                        )
                    }
                }
            }.onFailure { err ->
                _forgotPasswordState.update {
                    it.copy(isLoading = false, errorMessage = err.message ?: "Account lookup failed.")
                }
            }
        }
    }

    private fun startForgotCountdown() {
        forgotCountdownJob?.cancel()
        forgotCountdownJob = viewModelScope.launch {
            while (_forgotPasswordState.value.otpCountdown > 0) {
                delay(1000)
                _forgotPasswordState.update { it.copy(otpCountdown = it.otpCountdown - 1) }
            }
        }
    }

    fun onForgotOtpChange(code: String) {
        if (code.length <= 6) {
            _forgotPasswordState.update { it.copy(otpCode = code, otpError = null) }
        }
    }

    fun verifyForgotOtp() {
        val state = _forgotPasswordState.value
        val code = state.otpCode.trim()
        if (code.length < 6) {
            _forgotPasswordState.update { it.copy(otpError = "Please enter the 6-digit verification code.") }
            return
        }

        viewModelScope.launch {
            _forgotPasswordState.update { it.copy(isLoading = true, otpError = null) }
            val res = repository.verifyOtp(state.phone, code)
            res.onSuccess {
                _forgotPasswordState.update {
                    it.copy(
                        isLoading = false,
                        step = 3,
                        otpError = null
                    )
                }
            }.onFailure { err ->
                _forgotPasswordState.update {
                    it.copy(
                        isLoading = false,
                        otpError = err.message ?: "Invalid verification code."
                    )
                }
            }
        }
    }

    fun onForgotNewPasswordChange(input: String) {
        val strength = evaluatePasswordStrength(input)
        _forgotPasswordState.update {
            it.copy(
                newPassword = input,
                passwordStrength = strength,
                passwordError = null,
                errorMessage = null
            )
        }
    }

    fun onForgotConfirmNewPasswordChange(input: String) {
        _forgotPasswordState.update {
            it.copy(confirmNewPassword = input, passwordError = null, errorMessage = null) }
    }

    fun toggleForgotNewPasswordVisibility() {
        _forgotPasswordState.update { it.copy(isPasswordVisible = !it.isPasswordVisible) }
    }

    fun submitResetPassword(onSuccess: () -> Unit) {
        val state = _forgotPasswordState.value
        val pass = state.newPassword
        val confirm = state.confirmNewPassword

        if (pass.length < 8) {
            _forgotPasswordState.update { it.copy(passwordError = "Password must be at least 8 characters.") }
            return
        }
        if (pass != confirm) {
            _forgotPasswordState.update { it.copy(passwordError = "Passwords do not match.") }
            return
        }

        viewModelScope.launch {
            _forgotPasswordState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.resetUserPassword(state.userId, pass)
            res.onSuccess {
                _forgotPasswordState.update { it.copy(isLoading = false, isSuccess = true) }
                delay(1200)
                closeForgotPassword()
                onSuccess()
            }.onFailure { err ->
                _forgotPasswordState.update {
                    it.copy(isLoading = false, errorMessage = err.message ?: "Failed to reset password.")
                }
            }
        }
    }

    // ==========================================
    // BACKWARD-COMPATIBILITY METHODS FOR TESTS
    // ==========================================

    fun onPhoneChange(phone: String) {
        _authState.update { it.copy(phoneInput = phone, phoneError = null) }
    }

    fun resetToPhoneInput() {
        countdownJob?.cancel()
        _authState.update {
            it.copy(
                isOtpSent = false,
                otpCode = "",
                otpError = null,
                phoneError = null,
                resendCountdown = 0,
                otpReferenceId = null,
                otpStatusMessage = null
            )
        }
    }

    fun onOtpChange(code: String) {
        if (code.length <= 6) {
            _authState.update { it.copy(otpCode = code, otpError = null) }
        }
    }

    fun requestOtp() {
        val phone = _authState.value.phoneInput.trim()
        val normalized = repository.normalizeSriLankanPhone(phone)
        if (!normalized.matches(Regex("^\\+947[0-9]{8}$"))) {
            _authState.update { it.copy(phoneError = "Please enter a valid Sri Lankan mobile (e.g. 0771234567)") }
            return
        }

        viewModelScope.launch {
            _authState.update { it.copy(isLoading = true, phoneError = null) }
            val result = repository.sendOtpWithTextLk(normalized)
            if (result.success) {
                _authState.update {
                    it.copy(
                        isLoading = false,
                        isOtpSent = true,
                        otpCode = "",
                        phoneError = null,
                        otpError = null,
                        resendCountdown = 60,
                        otpReferenceId = result.referenceId,
                        otpStatusMessage = result.displayMessage
                    )
                }
                startCountdown()
            } else {
                _authState.update {
                    it.copy(
                        isLoading = false,
                        isOtpSent = false,
                        otpCode = "",
                        phoneError = result.errorMessage ?: result.displayMessage,
                        otpError = null,
                        resendCountdown = 0,
                        otpReferenceId = null,
                        otpStatusMessage = null
                    )
                }
            }
        }
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            while (_authState.value.resendCountdown > 0) {
                delay(1000)
                _authState.update { it.copy(resendCountdown = it.resendCountdown - 1) }
            }
        }
    }

    fun verifyOtp() {
        val code = _authState.value.otpCode.trim()
        if (code.length < 6) {
            _authState.update { it.copy(otpError = "Please enter the 6-digit code") }
            return
        }

        viewModelScope.launch {
            _authState.update { it.copy(isLoading = true, otpError = null) }
            val phone = repository.normalizeSriLankanPhone(_authState.value.phoneInput)
            val refId = _authState.value.otpReferenceId
            val res = repository.verifyOtp(phone, code, refId)
            res.onSuccess { data ->
                val userObj = data["user"] as? Map<*, *>
                val userId = userObj?.get("id") as? String ?: ""

                val profileRes = repository.getProfile(userId)
                val profile = profileRes.getOrNull()

                if (profile != null && profile.isSuspended) {
                    _authState.update {
                        it.copy(
                            isLoading = false,
                            isSuspended = true,
                            suspendedReason = profile.suspendedReason
                        )
                    }
                } else if (profile == null || profile.fullName.isBlank()) {
                    _authState.update {
                        it.copy(isLoading = false, needsOnboarding = true)
                    }
                } else {
                    _authState.update {
                        it.copy(isLoading = false, isSessionReady = true)
                    }
                }
            }.onFailure { err ->
                _authState.update {
                    it.copy(isLoading = false, otpError = err.message ?: "Invalid code. Please check and retry.")
                }
            }
        }
    }

    fun onFullNameChange(name: String) = _onboardingState.update { it.copy(fullName = name, errorMessage = null) }
    fun onNicChange(nic: String) = _onboardingState.update { it.copy(nic = nic.uppercase(), nicError = null, errorMessage = null) }
    fun onCitySelected(cityId: Int) = _onboardingState.update { it.copy(selectedCityId = cityId) }
    fun onCultivationCitySelected(cityId: Int) = _onboardingState.update { it.copy(cultivationCityId = cityId) }
    fun onCultivationAddressChange(addr: String) = _onboardingState.update { it.copy(cultivationAddress = addr) }
    fun toggleFarmerMode(enabled: Boolean) {
        _onboardingState.update { it.copy(isFarmerMode = enabled, errorMessage = null) }
        if (enabled && _onboardingState.value.cultivationCities.isEmpty()) {
            onCultivationDistrictSelected(_onboardingState.value.selectedDistrictId)
        }
    }
    fun setAccountType(isFarmer: Boolean) = toggleFarmerMode(isFarmer)

    fun submitOnboarding() {
        val state = _onboardingState.value
        val name = state.fullName.trim()
        val nic = state.nic.trim().uppercase()

        if (name.length < 2) {
            _onboardingState.update { it.copy(errorMessage = "Please enter your full name.") }
            return
        }

        viewModelScope.launch {
            _onboardingState.update { it.copy(isSubmitting = true, errorMessage = null) }
            val phone = repository.normalizeSriLankanPhone(_authState.value.phoneInput)

            val profRes = repository.registerProfile(
                fullName = name,
                nic = nic,
                phone = phone,
                districtId = state.selectedDistrictId,
                cityId = state.selectedCityId
            )

            if (profRes.isFailure) {
                _onboardingState.update {
                    it.copy(
                        isSubmitting = false,
                        errorMessage = profRes.exceptionOrNull().toUserFriendlyMessage("Could not save your profile details. Please try again.")
                    )
                }
                return@launch
            }

            if (state.isFarmerMode) {
                val farmRes = repository.registerFarmer(
                    cultivationDistrictId = state.cultivationDistrictId,
                    cultivationCityId = state.cultivationCityId,
                    cultivationAddress = state.cultivationAddress,
                    mainCrops = state.mainCrops,
                    landSize = state.landSize.toDoubleOrNull(),
                    landUnit = state.landUnit,
                    defaultPickupLandmark = state.defaultPickupLandmark.ifBlank { null }
                )
                if (farmRes.isFailure) {
                    _onboardingState.update {
                        it.copy(
                            isSubmitting = false,
                            errorMessage = farmRes.exceptionOrNull().toUserFriendlyMessage("Could not complete farmer registration. Please try again.")
                        )
                    }
                    return@launch
                }
            }

            _onboardingState.update { it.copy(isSubmitting = false, isComplete = true) }
            _authState.update { it.copy(isSessionReady = true, needsOnboarding = false) }
        }
    }

    fun initFarmerOnboarding() {
        _onboardingState.update {
            it.copy(
                currentStep = 2,
                isFarmerMode = true,
                isComplete = false,
                errorMessage = null,
                cultivationDistrictId = if (it.cultivationDistrictId == 1 && it.selectedDistrictId != 1) it.selectedDistrictId else it.cultivationDistrictId
            )
        }
        if (_onboardingState.value.cultivationCities.isEmpty()) {
            onCultivationDistrictSelected(_onboardingState.value.cultivationDistrictId)
        }
    }

    fun goToFarmerStep(): Boolean {
        val state = _onboardingState.value
        val name = state.fullName.trim()
        val nic = state.nic.trim().uppercase()

        if (name.length < 2) {
            _onboardingState.update { it.copy(errorMessage = "Please enter your full official name.") }
            return false
        }

        val isValidNic = nic.matches(Regex("^[0-9]{9}[VX]$")) || nic.matches(Regex("^[0-9]{12}$"))
        if (!isValidNic) {
            _onboardingState.update {
                it.copy(
                    nicError = "Invalid NIC format. Must be 9 digits + V/X or 12 digits.",
                    errorMessage = "Please enter a valid National Identity Card (NIC) number."
                )
            }
            return false
        }

        _onboardingState.update {
            it.copy(
                isFarmerMode = true,
                currentStep = 2,
                errorMessage = null,
                nicError = null,
                cultivationDistrictId = if (it.cultivationDistrictId == 1 && it.selectedDistrictId != 1) it.selectedDistrictId else it.cultivationDistrictId
            )
        }
        if (_onboardingState.value.cultivationCities.isEmpty()) {
            onCultivationDistrictSelected(_onboardingState.value.cultivationDistrictId)
        }
        return true
    }

    fun goBackToStepOne() {
        _onboardingState.update { it.copy(currentStep = 1, errorMessage = null) }
    }

    fun addCropChip(crop: String) {
        val trimmed = crop.trim()
        if (trimmed.length in 2..30 && !_onboardingState.value.mainCrops.contains(trimmed) && _onboardingState.value.mainCrops.size < 15) {
            _onboardingState.update {
                it.copy(
                    mainCrops = it.mainCrops + trimmed,
                    customCropInput = ""
                )
            }
        }
    }

    fun removeCropChip(crop: String) {
        _onboardingState.update {
            it.copy(mainCrops = it.mainCrops - crop)
        }
    }

    fun onCustomCropInputChange(input: String) {
        _onboardingState.update { it.copy(customCropInput = input) }
    }

    fun onLandSizeChange(size: String) {
        _onboardingState.update { it.copy(landSize = size) }
    }

    fun onLandUnitChange(unit: String) {
        _onboardingState.update { it.copy(landUnit = unit) }
    }

    fun onDefaultPickupLandmarkChange(landmark: String) {
        _onboardingState.update { it.copy(defaultPickupLandmark = landmark) }
    }
}
