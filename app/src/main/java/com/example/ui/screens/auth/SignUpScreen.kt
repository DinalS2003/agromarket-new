package com.example.ui.screens.auth

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.viewmodel.AuthViewModel
import com.example.viewmodel.UsernameCheckStatus

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SignUpScreen(
    viewModel: AuthViewModel,
    onBackToWelcome: () -> Unit,
    onSignUpSuccess: () -> Unit,
    onFarmerSignUpSuccess: () -> Unit = onSignUpSuccess
) {
    val state by viewModel.signUpState.collectAsState()
    var districtMenuExpanded by remember { mutableStateOf(false) }
    var cityMenuExpanded by remember { mutableStateOf(false) }
    var cultDistrictMenuExpanded by remember { mutableStateOf(false) }
    var cultCityMenuExpanded by remember { mutableStateOf(false) }

    BackHandler {
        if (state.currentStep > 1) {
            viewModel.stepBackInSignUp()
        } else {
            onBackToWelcome()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (state.currentStep) {
                            1 -> "Choose Username"
                            2 -> "Profile Details"
                            3 -> "Mobile & Password"
                            else -> "Verify Phone"
                        },
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (state.currentStep > 1) {
                                viewModel.stepBackInSignUp()
                            } else {
                                onBackToWelcome()
                            }
                        },
                        modifier = Modifier.testTag("signup_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .imePadding()
        ) {
            // Step Progress Bar (1 to 4)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (s in 1..4) {
                    val isActive = s <= state.currentStep
                    val isCurrent = s == state.currentStep
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .background(
                                color = if (isActive) AgroGreenPrimary else MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(2.dp)
                            )
                    )
                }
            }

            Text(
                text = "Step ${state.currentStep} of 4",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = AgroGreenPrimary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
            )

            // Global Error Banner
            if (!state.errorMessage.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = state.errorMessage ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                when (state.currentStep) {
                    // STEP 1: USERNAME SELECTION WITH DEBOUNCED AVAILABILITY
                    1 -> {
                        item {
                            AgroCard(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Choose your AgroMarket handle",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "This will be your unique identifier for direct transactions and login.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                OutlinedTextField(
                                    value = state.username,
                                    onValueChange = { viewModel.onSignUpUsernameChange(it) },
                                    label = { Text("Username") },
                                    placeholder = { Text("e.g. green_valley_farm") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Filled.AlternateEmail,
                                            contentDescription = null,
                                            tint = AgroGreenPrimary
                                        )
                                    },
                                    trailingIcon = {
                                        when (state.usernameStatus) {
                                            UsernameCheckStatus.CHECKING -> {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(20.dp),
                                                    strokeWidth = 2.dp,
                                                    color = AgroGreenPrimary
                                                )
                                            }
                                            UsernameCheckStatus.AVAILABLE -> {
                                                Icon(
                                                    imageVector = Icons.Filled.CheckCircle,
                                                    contentDescription = "Available",
                                                    tint = AgroSuccess
                                                )
                                            }
                                            UsernameCheckStatus.TAKEN, UsernameCheckStatus.INVALID -> {
                                                Icon(
                                                    imageVector = Icons.Filled.Cancel,
                                                    contentDescription = "Unavailable",
                                                    tint = MaterialTheme.colorScheme.error
                                                )
                                            }
                                            else -> {}
                                        }
                                    },
                                    supportingText = {
                                        if (!state.usernameError.isNullOrBlank()) {
                                            Text(
                                                text = state.usernameError ?: "",
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        } else if (state.usernameStatus == UsernameCheckStatus.AVAILABLE) {
                                            Text(
                                                text = "✓ @${state.username} is available!",
                                                color = AgroSuccess,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        } else {
                                            Text("3–20 characters. Letters, numbers, and underscores.")
                                        }
                                    },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("signup_username_input"),
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }
                        }
                    }

                    // STEP 2: PERSONAL DETAILS & ROLE
                    2 -> {
                        item {
                            AgroCard(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Your Personal Details",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Spacer(modifier = Modifier.height(16.dp))

                                OutlinedTextField(
                                    value = state.fullName,
                                    onValueChange = { viewModel.onSignUpFullNameChange(it) },
                                    label = { Text("Full Legal Name") },
                                    placeholder = { Text("e.g. Bandara Jayasundara") },
                                    leadingIcon = { Icon(Icons.Filled.Badge, contentDescription = null, tint = AgroGreenPrimary) },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("signup_fullname_input"),
                                    shape = RoundedCornerShape(12.dp)
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                OutlinedTextField(
                                    value = state.nic,
                                    onValueChange = { viewModel.onSignUpNicChange(it) },
                                    label = { Text("National Identity Card (NIC)") },
                                    placeholder = { Text("123456789V or 200012345678") },
                                    leadingIcon = { Icon(Icons.Filled.CreditCard, contentDescription = null, tint = AgroGreenPrimary) },
                                    supportingText = {
                                        if (!state.nicError.isNullOrBlank()) {
                                            Text(state.nicError ?: "", color = MaterialTheme.colorScheme.error)
                                        }
                                    },
                                    isError = !state.nicError.isNullOrBlank(),
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("signup_nic_input"),
                                    shape = RoundedCornerShape(12.dp)
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                // District Dropdown
                                ExposedDropdownMenuBox(
                                    expanded = districtMenuExpanded,
                                    onExpandedChange = { districtMenuExpanded = it }
                                ) {
                                    val selectedDistrict = state.districts.find { it.id == state.selectedDistrictId }?.name ?: "Select District"
                                    OutlinedTextField(
                                        value = selectedDistrict,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("Home District") },
                                        leadingIcon = { Icon(Icons.Filled.LocationOn, contentDescription = null, tint = AgroGreenPrimary) },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = districtMenuExpanded) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                                            .testTag("signup_district_picker"),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    ExposedDropdownMenu(
                                        expanded = districtMenuExpanded,
                                        onDismissRequest = { districtMenuExpanded = false }
                                    ) {
                                        state.districts.forEach { dist ->
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

                                Spacer(modifier = Modifier.height(12.dp))

                                // City Dropdown
                                ExposedDropdownMenuBox(
                                    expanded = cityMenuExpanded,
                                    onExpandedChange = { cityMenuExpanded = it }
                                ) {
                                    val selectedCity = state.cities.find { it.id == state.selectedCityId }?.name ?: "Select City"
                                    OutlinedTextField(
                                        value = selectedCity,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("Nearest Town / City") },
                                        leadingIcon = { Icon(Icons.Filled.NearMe, contentDescription = null, tint = AgroGreenPrimary) },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = cityMenuExpanded) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                                            .testTag("signup_city_picker"),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    ExposedDropdownMenu(
                                        expanded = cityMenuExpanded,
                                        onDismissRequest = { cityMenuExpanded = false }
                                    ) {
                                        state.cities.forEach { c ->
                                            DropdownMenuItem(
                                                text = { Text(c.name) },
                                                onClick = {
                                                    viewModel.onSignUpCitySelected(c.id)
                                                    cityMenuExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                // Role Selector: Buyer vs Farmer
                                Text(
                                    text = "Account Role",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    FilterChip(
                                        selected = !state.isFarmer,
                                        onClick = { viewModel.setSignUpAccountType(false) },
                                        label = { Text("Buyer / Consumer") },
                                        leadingIcon = { Icon(Icons.Filled.ShoppingCart, contentDescription = null) },
                                        modifier = Modifier.weight(1f).testTag("signup_role_buyer")
                                    )
                                    FilterChip(
                                        selected = state.isFarmer,
                                        onClick = { viewModel.setSignUpAccountType(true) },
                                        label = { Text("Farmer / Producer") },
                                        leadingIcon = { Icon(Icons.Filled.Agriculture, contentDescription = null) },
                                        modifier = Modifier.weight(1f).testTag("signup_role_farmer")
                                    )
                                }

                                // Farmer cultivation details if farmer selected
                                if (state.isFarmer) {
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "Farm & Cultivation Details",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = AgroGreenPrimary)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))

                                    OutlinedTextField(
                                        value = state.cultivationAddress,
                                        onValueChange = { viewModel.onSignUpCultivationAddressChange(it) },
                                        label = { Text("Farm / Cultivation Address") },
                                        placeholder = { Text("e.g. 42 Farm Road, Dambulla") },
                                        modifier = Modifier.fillMaxWidth().testTag("signup_farm_address"),
                                        shape = RoundedCornerShape(12.dp)
                                    )

                                    Spacer(modifier = Modifier.height(12.dp))

                                    Text(
                                        text = "Main Crops Grown",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                                    )
                                    FlowRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        viewModel.commonCropSuggestions.take(8).forEach { crop ->
                                            val isSelected = state.mainCrops.contains(crop)
                                            FilterChip(
                                                selected = isSelected,
                                                onClick = {
                                                    if (isSelected) viewModel.removeSignUpCropChip(crop)
                                                    else viewModel.addSignUpCropChip(crop)
                                                },
                                                label = { Text(crop) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // STEP 3: MOBILE NUMBER & PASSWORD + CONFIRM
                    3 -> {
                        item {
                            AgroCard(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Security & Verification Mobile",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Spacer(modifier = Modifier.height(16.dp))

                                OutlinedTextField(
                                    value = state.phone,
                                    onValueChange = { viewModel.onSignUpPhoneChange(it) },
                                    label = { Text("Mobile Number (SMS verification)") },
                                    placeholder = { Text("0771234567") },
                                    leadingIcon = { Icon(Icons.Filled.Phone, contentDescription = null, tint = AgroGreenPrimary) },
                                    supportingText = {
                                        if (!state.phoneError.isNullOrBlank()) {
                                            Text(state.phoneError ?: "", color = MaterialTheme.colorScheme.error)
                                        } else {
                                            Text("A 6-digit OTP will be sent to this number.")
                                        }
                                    },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("signup_phone_input"),
                                    shape = RoundedCornerShape(12.dp)
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                OutlinedTextField(
                                    value = state.password,
                                    onValueChange = { viewModel.onSignUpPasswordChange(it) },
                                    label = { Text("Password (min 8 chars)") },
                                    leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null, tint = AgroGreenPrimary) },
                                    trailingIcon = {
                                        IconButton(onClick = { viewModel.toggleSignUpPasswordVisibility() }) {
                                            Icon(
                                                imageVector = if (state.isPasswordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                                contentDescription = null
                                            )
                                        }
                                    },
                                    visualTransformation = if (state.isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("signup_password_input"),
                                    shape = RoundedCornerShape(12.dp)
                                )

                                if (state.password.isNotEmpty()) {
                                    PasswordStrengthIndicator(strength = state.passwordStrength)
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                OutlinedTextField(
                                    value = state.confirmPassword,
                                    onValueChange = { viewModel.onSignUpConfirmPasswordChange(it) },
                                    label = { Text("Confirm Password") },
                                    leadingIcon = { Icon(Icons.Filled.LockReset, contentDescription = null, tint = AgroGreenPrimary) },
                                    trailingIcon = {
                                        IconButton(onClick = { viewModel.toggleSignUpConfirmPasswordVisibility() }) {
                                            Icon(
                                                imageVector = if (state.isConfirmPasswordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                                contentDescription = null
                                            )
                                        }
                                    },
                                    visualTransformation = if (state.isConfirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                    supportingText = {
                                        if (!state.passwordError.isNullOrBlank()) {
                                            Text(state.passwordError ?: "", color = MaterialTheme.colorScheme.error)
                                        }
                                    },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("signup_confirm_password_input"),
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }
                        }
                    }

                    // STEP 4: OTP VERIFICATION
                    4 -> {
                        item {
                            AgroCard(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Enter 6-digit Code",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "We sent an SMS code to ${state.phone}.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                OutlinedTextField(
                                    value = state.otpCode,
                                    onValueChange = { viewModel.onSignUpOtpChange(it) },
                                    label = { Text("Verification Code") },
                                    placeholder = { Text("123456") },
                                    leadingIcon = { Icon(Icons.Filled.Security, contentDescription = null, tint = AgroGreenPrimary) },
                                    supportingText = {
                                        if (!state.otpError.isNullOrBlank()) {
                                            Text(state.otpError ?: "", color = MaterialTheme.colorScheme.error)
                                        }
                                    },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("signup_otp_input"),
                                    shape = RoundedCornerShape(12.dp)
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (state.resendCountdown > 0) "Resend in ${state.resendCountdown}s" else "Didn't receive code?",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (state.resendCountdown == 0) {
                                        TextButton(onClick = { viewModel.requestSignUpOtp() }) {
                                            Text("Resend Code", color = AgroGreenPrimary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Bottom Sticky Next / Submit Action Bar
            StickyBottomCTA {
                PrimaryButton(
                    text = when (state.currentStep) {
                        1 -> "Next: Profile Details"
                        2 -> "Next: Security"
                        3 -> "Send Verification Code"
                        else -> "Create Account & Start"
                    },
                    onClick = {
                        when (state.currentStep) {
                            1 -> viewModel.proceedFromUsernameStep()
                            2 -> viewModel.proceedFromDetailsStep()
                            3 -> viewModel.requestSignUpOtp()
                            4 -> viewModel.verifySignUpOtp { isFarmer ->
                                if (isFarmer) onFarmerSignUpSuccess() else onSignUpSuccess()
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("signup_action_button"),
                    isLoading = state.isLoading,
                    enabled = when (state.currentStep) {
                        1 -> state.usernameStatus == UsernameCheckStatus.AVAILABLE
                        2 -> state.fullName.isNotBlank() && state.nic.isNotBlank()
                        3 -> state.phone.isNotBlank() && state.password.length >= 8 && state.password == state.confirmPassword
                        4 -> state.otpCode.length == 6
                        else -> true
                    }
                )
            }
        }
    }
}
