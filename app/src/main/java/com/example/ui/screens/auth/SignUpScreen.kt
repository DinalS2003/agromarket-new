package com.example.ui.screens.auth

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.viewmodel.AuthViewModel

import androidx.compose.foundation.layout.ExperimentalLayoutApi

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SignUpScreen(
    viewModel: AuthViewModel,
    onBackToWelcome: () -> Unit,
    onSignUpSuccess: () -> Unit,
    onFarmerSignUpSuccess: () -> Unit = onSignUpSuccess
) {
    val authState by viewModel.authState.collectAsState()
    val onboardingState by viewModel.onboardingState.collectAsState()

    var step by remember { mutableIntStateOf(1) } // 1 = Details, 2 = OTP, 3 = Farm Details
    var localError by remember { mutableStateOf<String?>(null) }
    var districtMenuExpanded by remember { mutableStateOf(false) }
    var cityMenuExpanded by remember { mutableStateOf(false) }
    var cultDistrictMenuExpanded by remember { mutableStateOf(false) }
    var cultCityMenuExpanded by remember { mutableStateOf(false) }

    // If OTP was sent successfully and we're at step 1, advance to step 2
    LaunchedEffect(authState.isOtpSent) {
        if (authState.isOtpSent && step == 1) {
            step = 2
        }
    }

    // When OTP verification finishes:
    // If farmer -> advance to step 3 (cultivation details). Farm details MUST be entered after verification!
    // If buyer -> submit onboarding profile directly.
    LaunchedEffect(authState.isSessionReady, authState.needsOnboarding) {
        if (step == 2 && (authState.isSessionReady || authState.needsOnboarding) && !onboardingState.isComplete && !onboardingState.isSubmitting) {
            if (onboardingState.isFarmerMode) {
                step = 3
                if (onboardingState.cultivationCities.isEmpty()) {
                    viewModel.onCultivationDistrictSelected(onboardingState.selectedDistrictId)
                }
            } else {
                viewModel.submitOnboarding()
            }
        }
    }

    // When registration completes:
    // Farmers navigate directly to the listings page; buyers navigate to marketplace.
    LaunchedEffect(onboardingState.isComplete) {
        if (onboardingState.isComplete) {
            if (onboardingState.isFarmerMode) {
                onFarmerSignUpSuccess()
            } else {
                onSignUpSuccess()
            }
        }
    }

    BackHandler {
        when (step) {
            3 -> {
                step = 2
            }
            2 -> {
                step = 1
                viewModel.resetToPhoneInput()
            }
            else -> {
                onBackToWelcome()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppLogo(size = 28.dp, shape = RoundedCornerShape(8.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = when (step) {
                                1 -> "Create Account"
                                2 -> "Verify Mobile"
                                else -> "Cultivation Details"
                            },
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            when (step) {
                                3 -> step = 2
                                2 -> {
                                    step = 1
                                    viewModel.resetToPhoneInput()
                                }
                                else -> onBackToWelcome()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        bottomBar = {
            StickyBottomCTA {
                when (step) {
                    1 -> {
                        PrimaryButton(
                            text = "Continue to Verification",
                            onClick = {
                                localError = null
                                val name = onboardingState.fullName.trim()
                                val nic = onboardingState.nic.trim().uppercase()
                                val phone = authState.phoneInput.trim()

                                if (name.length < 2) {
                                    localError = "Please enter your full official name."
                                    return@PrimaryButton
                                }

                                val isValidNic = nic.matches(Regex("^[0-9]{9}[VX]$")) || nic.matches(Regex("^[0-9]{12}$"))
                                if (!isValidNic) {
                                    localError = "Invalid NIC format (e.g. 199012345678 or 901234567V)."
                                    return@PrimaryButton
                                }

                                if (phone.isBlank()) {
                                    localError = "Please enter your Sri Lankan mobile number."
                                    return@PrimaryButton
                                }

                                // Trigger OTP send
                                viewModel.requestOtp()
                            },
                            isLoading = authState.isLoading,
                            modifier = Modifier.fillMaxWidth().testTag("signup_continue_button")
                        )
                    }
                    2 -> {
                        PrimaryButton(
                            text = if (onboardingState.isFarmerMode) "Verify & Enter Cultivation Details" else "Verify & Complete Registration",
                            onClick = {
                                viewModel.verifyOtp()
                            },
                            isLoading = authState.isLoading || onboardingState.isSubmitting,
                            enabled = authState.otpCode.length == 6 && !authState.isLoading && !onboardingState.isSubmitting,
                            modifier = Modifier.fillMaxWidth().testTag("signup_verify_button")
                        )
                    }
                    3 -> {
                        PrimaryButton(
                            text = "Complete & View Produce Listings",
                            onClick = {
                                localError = null
                                if (onboardingState.cultivationAddress.trim().isBlank()) {
                                    localError = "Please enter your farm / cultivation address."
                                    return@PrimaryButton
                                }
                                if (onboardingState.mainCrops.isEmpty()) {
                                    localError = "Please select or add at least 1 crop that you cultivate."
                                    return@PrimaryButton
                                }
                                viewModel.submitOnboarding()
                            },
                            isLoading = onboardingState.isSubmitting,
                            enabled = !onboardingState.isSubmitting,
                            leadingIcon = Icons.Filled.Inventory2,
                            modifier = Modifier.fillMaxWidth().testTag("signup_save_farm_details_button")
                        )
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = AgroSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AgroSpacing.md)
        ) {
            item {
                // Step Progress Indicator
                if (onboardingState.isFarmerMode) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = AgroSpacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(AgroSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StepIndicator(stepNumber = 1, title = "Account", isActive = step == 1, isCompleted = step > 1, modifier = Modifier.weight(1f))
                        StepIndicator(stepNumber = 2, title = "SMS OTP", isActive = step == 2, isCompleted = step > 2, modifier = Modifier.weight(1f))
                        StepIndicator(stepNumber = 3, title = "Farm Details", isActive = step == 3, isCompleted = onboardingState.isComplete, modifier = Modifier.weight(1f))
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = AgroSpacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(AgroSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StepIndicator(stepNumber = 1, title = "Profile Details", isActive = step == 1, isCompleted = step > 1, modifier = Modifier.weight(1f))
                        StepIndicator(stepNumber = 2, title = "SMS OTP", isActive = step == 2, isCompleted = onboardingState.isComplete, modifier = Modifier.weight(1f))
                    }
                }
            }

            // Error banner if any
            item {
                val activeError = localError ?: authState.phoneError ?: authState.otpError ?: onboardingState.errorMessage
                if (activeError != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = AgroShapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = activeError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            // STEP 1: Basic Profile Details (Role, Name, NIC, Mobile, Location) - NO Farm Details here!
            if (step == 1) {
                item {
                    SectionHeader(title = "Account Role")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AgroSpacing.md)
                    ) {
                        RoleSelectionCard(
                            title = "Buyer",
                            subtitle = "Purchase fresh crops",
                            icon = Icons.Filled.ShoppingBag,
                            isSelected = !onboardingState.isFarmerMode,
                            onClick = { viewModel.setAccountType(isFarmer = false) },
                            modifier = Modifier.weight(1f)
                        )
                        RoleSelectionCard(
                            title = "Farmer",
                            subtitle = "Sell direct harvest",
                            icon = Icons.Filled.Agriculture,
                            isSelected = onboardingState.isFarmerMode,
                            onClick = { viewModel.setAccountType(isFarmer = true) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                item {
                    SectionHeader(title = "Personal Information")
                    AgroCard {
                        OutlinedTextField(
                            value = onboardingState.fullName,
                            onValueChange = viewModel::onFullNameChange,
                            label = { Text("Full Name") },
                            placeholder = { Text("e.g. Kasun Perera") },
                            leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                            modifier = Modifier.fillMaxWidth().testTag("signup_fullname_input")
                        )

                        Spacer(modifier = Modifier.height(AgroSpacing.sm))

                        OutlinedTextField(
                            value = onboardingState.nic,
                            onValueChange = viewModel::onNicChange,
                            label = { Text("National Identity Card (NIC)") },
                            placeholder = { Text("199012345678 or 901234567V") },
                            leadingIcon = { Icon(Icons.Filled.Badge, contentDescription = null) },
                            singleLine = true,
                            isError = onboardingState.nicError != null,
                            supportingText = {
                                Text(onboardingState.nicError ?: "Required for Sri Lankan trading security", style = MaterialTheme.typography.bodySmall)
                            },
                            modifier = Modifier.fillMaxWidth().testTag("signup_nic_input")
                        )

                        Spacer(modifier = Modifier.height(AgroSpacing.sm))

                        OutlinedTextField(
                            value = authState.phoneInput,
                            onValueChange = viewModel::onPhoneChange,
                            label = { Text("Mobile Number (SMS verification)") },
                            placeholder = { Text("0771234567") },
                            leadingIcon = { Icon(Icons.Filled.Phone, contentDescription = null) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            singleLine = true,
                            supportingText = { Text("Sri Lankan number format (07XXXXXXXX)") },
                            modifier = Modifier.fillMaxWidth().testTag("signup_phone_input")
                        )
                    }
                }

                item {
                    SectionHeader(title = "Home Location")
                    AgroCard {
                        // District selector
                        val selectedDistrict = onboardingState.districts.find { it.id == onboardingState.selectedDistrictId }
                        ExposedDropdownMenuBox(
                            expanded = districtMenuExpanded,
                            onExpandedChange = { districtMenuExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = selectedDistrict?.name ?: "Select District",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Home District") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = districtMenuExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = districtMenuExpanded,
                                onDismissRequest = { districtMenuExpanded = false }
                            ) {
                                onboardingState.districts.forEach { dist ->
                                    DropdownMenuItem(
                                        text = { Text(dist.name) },
                                        onClick = {
                                            viewModel.onDistrictSelected(dist.id)
                                            districtMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(AgroSpacing.sm))

                        // City selector
                        val selectedCity = onboardingState.cities.find { it.id == onboardingState.selectedCityId }
                        ExposedDropdownMenuBox(
                            expanded = cityMenuExpanded,
                            onExpandedChange = { cityMenuExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = if (onboardingState.isLoadingCities) "Loading cities..." else (selectedCity?.name ?: "Select City / DS Division"),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Home Town / DS Division") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = cityMenuExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = cityMenuExpanded,
                                onDismissRequest = { cityMenuExpanded = false }
                            ) {
                                onboardingState.cities.forEach { c ->
                                    DropdownMenuItem(
                                        text = { Text(c.name) },
                                        onClick = {
                                            viewModel.onCitySelected(c.id)
                                            cityMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // STEP 2: Mobile SMS OTP Verification
            else if (step == 2) {
                item {
                    AgroCard {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(AgroSpacing.md),
                            modifier = Modifier.fillMaxWidth().padding(vertical = AgroSpacing.md)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = AgroGreenContainer,
                                modifier = Modifier.size(64.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Filled.Lock,
                                        contentDescription = null,
                                        tint = AgroGreenPrimary,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }

                            Text(
                                text = "Verification Code Sent",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )

                            Text(
                                text = "Enter the 6-digit SMS code sent to ${authState.phoneInput}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )

                            OutlinedTextField(
                                value = authState.otpCode,
                                onValueChange = viewModel::onOtpChange,
                                label = { Text("6-digit Code") },
                                placeholder = { Text("123456") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                textStyle = MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 8.sp,
                                    textAlign = TextAlign.Center
                                ),
                                isError = authState.otpError != null,
                                supportingText = {
                                    if (authState.otpError != null) {
                                        Text(authState.otpError!!, color = MaterialTheme.colorScheme.error)
                                    } else if (!authState.otpStatusMessage.isNullOrBlank()) {
                                        Text(authState.otpStatusMessage!!, color = AgroGreenPrimary)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().testTag("signup_otp_input")
                            )

                            // Resend button
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                if (authState.resendCountdown > 0) {
                                    Text(
                                        text = "Resend OTP in ${authState.resendCountdown}s",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    TextButton(
                                        onClick = { viewModel.requestOtp() },
                                        enabled = !authState.isLoading
                                    ) {
                                        Text("Resend OTP", fontWeight = FontWeight.Bold, color = AgroGreenPrimary)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // STEP 3: Cultivation Details (Farmer only, entered after verification!)
            else if (step == 3) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Agriculture,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Your mobile is verified! Now enter your cultivation details to start listing and selling your harvest.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }

                item {
                    SectionHeader(title = "Cultivation Location")
                    AgroCard {
                        // Cultivation District
                        val cultDistrict = onboardingState.districts.find { it.id == onboardingState.cultivationDistrictId }
                        ExposedDropdownMenuBox(
                            expanded = cultDistrictMenuExpanded,
                            onExpandedChange = { cultDistrictMenuExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = cultDistrict?.name ?: "Select Cultivation District",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Cultivation District") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = cultDistrictMenuExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth().testTag("cultivation_district_select")
                            )
                            ExposedDropdownMenu(
                                expanded = cultDistrictMenuExpanded,
                                onDismissRequest = { cultDistrictMenuExpanded = false }
                            ) {
                                onboardingState.districts.forEach { dist ->
                                    DropdownMenuItem(
                                        text = { Text(dist.name) },
                                        onClick = {
                                            viewModel.onCultivationDistrictSelected(dist.id)
                                            cultDistrictMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(AgroSpacing.sm))

                        // Cultivation City
                        val cultCity = onboardingState.cultivationCities.find { it.id == onboardingState.cultivationCityId }
                        ExposedDropdownMenuBox(
                            expanded = cultCityMenuExpanded,
                            onExpandedChange = { cultCityMenuExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = cultCity?.name ?: "Select Cultivation Town",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Cultivation Town / DS Division") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = cultCityMenuExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth().testTag("cultivation_city_select")
                            )
                            ExposedDropdownMenu(
                                expanded = cultCityMenuExpanded,
                                onDismissRequest = { cultCityMenuExpanded = false }
                            ) {
                                onboardingState.cultivationCities.forEach { city ->
                                    DropdownMenuItem(
                                        text = { Text(city.name) },
                                        onClick = {
                                            viewModel.onCultivationCitySelected(city.id)
                                            cultCityMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(AgroSpacing.sm))

                        OutlinedTextField(
                            value = onboardingState.cultivationAddress,
                            onValueChange = viewModel::onCultivationAddressChange,
                            label = { Text("Cultivation / Farm Address") },
                            placeholder = { Text("e.g. Gam Udawa Road, Dambulla") },
                            leadingIcon = { Icon(Icons.Filled.HomeWork, contentDescription = null) },
                            singleLine = false,
                            maxLines = 2,
                            modifier = Modifier.fillMaxWidth().testTag("signup_farm_address_input")
                        )
                    }
                }

                item {
                    SectionHeader(title = "Crops Cultivated")
                    AgroCard {
                        Text(
                            text = "Select all crops you grow or sell:",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(AgroSpacing.xs))

                        // Common suggestions
                        val suggestions = listOf(
                            "Tomato", "Carrot", "Beans", "Cabbage", "Pumpkin",
                            "Green Chilli", "Big Onion", "Red Onion", "Potato", "Beetroot",
                            "Banana", "Papaya", "Eggplant", "Leeks", "Spices"
                        )

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            suggestions.forEach { crop ->
                                val isSelected = onboardingState.mainCrops.contains(crop)
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        if (isSelected) viewModel.removeCropChip(crop) else viewModel.addCropChip(crop)
                                    },
                                    label = { Text(crop) },
                                    leadingIcon = if (isSelected) {
                                        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    } else null
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(AgroSpacing.sm))

                        // Custom crop input
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = onboardingState.customCropInput,
                                onValueChange = viewModel::onCustomCropInputChange,
                                label = { Text("Add Another Crop") },
                                placeholder = { Text("e.g. Dragon Fruit") },
                                singleLine = true,
                                modifier = Modifier.weight(1f).testTag("custom_crop_input")
                            )
                            Button(
                                onClick = {
                                    if (onboardingState.customCropInput.isNotBlank()) {
                                        viewModel.addCropChip(onboardingState.customCropInput)
                                    }
                                },
                                enabled = onboardingState.customCropInput.isNotBlank(),
                                modifier = Modifier.testTag("add_custom_crop_button")
                            ) {
                                Text("Add")
                            }
                        }

                        if (onboardingState.mainCrops.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(AgroSpacing.xs))
                            Text(
                                text = "Selected (${onboardingState.mainCrops.size}): ${onboardingState.mainCrops.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = AgroGreenPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                item {
                    SectionHeader(title = "Farm Size & Pickup Details")
                    AgroCard {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = onboardingState.landSize,
                                onValueChange = viewModel::onLandSizeChange,
                                label = { Text("Land Size") },
                                placeholder = { Text("e.g. 2.5") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.weight(1f).testTag("signup_land_size_input")
                            )

                            var unitMenuExpanded by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(
                                expanded = unitMenuExpanded,
                                onExpandedChange = { unitMenuExpanded = it },
                                modifier = Modifier.width(130.dp)
                            ) {
                                OutlinedTextField(
                                    value = onboardingState.landUnit,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Unit") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = unitMenuExpanded) },
                                    modifier = Modifier.menuAnchor()
                                )
                                ExposedDropdownMenu(
                                    expanded = unitMenuExpanded,
                                    onDismissRequest = { unitMenuExpanded = false }
                                ) {
                                    listOf("acres", "perches", "hectares").forEach { u ->
                                        DropdownMenuItem(
                                            text = { Text(u) },
                                            onClick = {
                                                viewModel.onLandUnitChange(u)
                                                unitMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(AgroSpacing.sm))

                        OutlinedTextField(
                            value = onboardingState.defaultPickupLandmark,
                            onValueChange = viewModel::onDefaultPickupLandmarkChange,
                            label = { Text("Pickup Landmark (Optional)") },
                            placeholder = { Text("e.g. Near Agrarian Service Centre") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("signup_pickup_landmark_input")
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(AgroSpacing.xxl))
            }
        }
    }
}

@Composable
private fun StepIndicator(
    stepNumber: Int,
    title: String,
    isActive: Boolean,
    isCompleted: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        color = if (isActive) AgroGreenContainer else if (isCompleted) AgroGreenContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = AgroShapes.medium,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = AgroShapes.pill,
                color = if (isActive || isCompleted) AgroGreenPrimary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isCompleted) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    } else {
                        Text(
                            text = stepNumber.toString(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 10.sp
                            )
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    color = if (isActive || isCompleted) AgroGreenPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                ),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun RoleSelectionCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = AgroShapes.card,
        color = if (isSelected) AgroGreenContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) AgroGreenPrimary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        ),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) AgroGreenPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) AgroGreenPrimary else MaterialTheme.colorScheme.onSurface
                )
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
