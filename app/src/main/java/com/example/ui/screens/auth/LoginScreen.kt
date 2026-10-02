package com.example.ui.screens.auth

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.viewmodel.AuthViewModel
import com.example.viewmodel.PasswordStrength
import com.example.viewmodel.UsernameCheckStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    viewModel: AuthViewModel,
    onSessionReady: () -> Unit,
    onNeedsOnboarding: () -> Unit,
    onNavigateToSignUp: (() -> Unit)? = null,
    onBackClick: (() -> Unit)? = null
) {
    val state by viewModel.loginState.collectAsState()
    val forgotState by viewModel.forgotPasswordState.collectAsState()
    val scrollState = rememberScrollState()
    val focusManager = LocalFocusManager.current

    if (state.isSessionReady) {
        LaunchedEffect(Unit) {
            onSessionReady()
        }
    }

    if (state.needsOnboarding) {
        LaunchedEffect(Unit) {
            onNeedsOnboarding()
        }
    }

    BackHandler {
        if (state.isUpgradeFlowActive) {
            viewModel.cancelUpgradeFlow()
        } else if (forgotState.isOpen) {
            viewModel.closeForgotPassword()
        } else if (onBackClick != null) {
            onBackClick()
        }
    }

    Scaffold(
        topBar = {
            if (onBackClick != null) {
                TopAppBar(
                    title = { },
                    navigationIcon = {
                        IconButton(
                            onClick = { onBackClick() },
                            modifier = Modifier.testTag("login_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .imePadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                // Header / Branding
                AppLogo(size = 80.dp)

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Welcome Back",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Log in with your AgroMarket username or mobile",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Main Login Card
                AgroCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(20.dp)
                ) {
                    // Lockout Warning if any
                    if (state.lockoutSecondsRemaining > 0) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.LockClock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Too many attempts. Locked for ${state.lockoutSecondsRemaining}s",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }

                    // Error Message Banner
                    if (!state.errorMessage.isNullOrBlank() && state.lockoutSecondsRemaining == 0) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp)
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

                    // 1. Username or Mobile Input (Auto-Detect)
                    val isMobilePattern = state.identifier.trim().matches(Regex("^(\\+?94|0)?[7][0-9]{0,8}$"))
                    OutlinedTextField(
                        value = state.identifier,
                        onValueChange = { viewModel.onLoginIdentifierChange(it) },
                        label = { Text("Username or Mobile Number") },
                        placeholder = { Text("e.g. saman_agro or 0771234567") },
                        leadingIcon = {
                            Icon(
                                imageVector = if (isMobilePattern && state.identifier.isNotEmpty()) Icons.Filled.Phone else Icons.Filled.Person,
                                contentDescription = null,
                                tint = AgroGreenPrimary
                            )
                        },
                        trailingIcon = {
                            if (state.identifier.isNotEmpty()) {
                                IconButton(onClick = { viewModel.onLoginIdentifierChange("") }) {
                                    Icon(Icons.Filled.Clear, contentDescription = "Clear")
                                }
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("login_identifier_input"),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // 2. Password Input with Show/Hide
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = { viewModel.onLoginPasswordChange(it) },
                        label = { Text("Password") },
                        placeholder = { Text("Enter your password") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = null,
                                tint = AgroGreenPrimary
                            )
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = { viewModel.toggleLoginPasswordVisibility() },
                                modifier = Modifier.testTag("login_password_toggle")
                            ) {
                                Icon(
                                    imageVector = if (state.isPasswordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                    contentDescription = if (state.isPasswordVisible) "Hide password" else "Show password"
                                )
                            }
                        },
                        visualTransformation = if (state.isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                focusManager.clearFocus()
                                viewModel.submitLogin(onSessionReady, onNeedsOnboarding)
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("login_password_input"),
                        shape = RoundedCornerShape(12.dp)
                    )

                    // Forgot Password Link
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Text(
                            text = "Forgot password?",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = AgroGreenPrimary,
                                fontWeight = FontWeight.SemiBold
                            ),
                            modifier = Modifier
                                .clickable { viewModel.openForgotPassword() }
                                .padding(vertical = 4.dp, horizontal = 4.dp)
                                .testTag("login_forgot_password_button")
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Submit Button
                    PrimaryButton(
                        text = "Log In",
                        onClick = {
                            focusManager.clearFocus()
                            viewModel.submitLogin(onSessionReady, onNeedsOnboarding)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("login_submit_button"),
                        isLoading = state.isLoading,
                        enabled = state.identifier.isNotBlank() && state.password.isNotBlank() && state.lockoutSecondsRemaining == 0
                    )
                }

                // Sign Up Alternative Footer
                Row(
                    modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Don't have an account? ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Create Account",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = AgroGreenPrimary,
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = Modifier
                            .clickable {
                                if (onNavigateToSignUp != null) {
                                    onNavigateToSignUp()
                                } else if (onBackClick != null) {
                                    onBackClick()
                                }
                            }
                            .padding(4.dp)
                            .testTag("login_go_to_signup")
                    )
                }
            }
        }
    }

    // ==========================================
    // 1c. ONE-TIME UPGRADE DIALOG FOR EXISTING USERS
    // ==========================================
    if (state.isUpgradeFlowActive) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelUpgradeFlow() },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.SecurityUpdateGood,
                        contentDescription = null,
                        tint = AgroGreenPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (!state.upgradeOtpVerified) "Verify Mobile" else "Set Up Your Login",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (!state.upgradeOtpVerified) {
                        Text(
                            text = "An existing AgroMarket account was found for ${state.upgradeMaskedPhone}. Please verify your mobile to set up your username and password.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (!state.upgradeOtpError.isNullOrBlank()) {
                            Text(
                                text = state.upgradeOtpError ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        OutlinedTextField(
                            value = state.upgradeOtpCode,
                            onValueChange = { viewModel.onUpgradeOtpChange(it) },
                            label = { Text("6-Digit Verification Code") },
                            placeholder = { Text("123456") },
                            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("upgrade_otp_input")
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (state.upgradeOtpCountdown > 0) "Resend in ${state.upgradeOtpCountdown}s" else "Didn't receive code?",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (state.upgradeOtpCountdown == 0) {
                                TextButton(onClick = { viewModel.startUpgradeOtp() }) {
                                    Text("Resend", color = AgroGreenPrimary)
                                }
                            }
                        }
                    } else {
                        Text(
                            text = "Choose a unique username and strong password for your returning account.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (!state.upgradeError.isNullOrBlank()) {
                            Text(
                                text = state.upgradeError ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        // Username with availability indicator
                        OutlinedTextField(
                            value = state.upgradeUsername,
                            onValueChange = { viewModel.onUpgradeUsernameChange(it) },
                            label = { Text("Choose Username") },
                            placeholder = { Text("e.g. saman_farms") },
                            leadingIcon = { Icon(Icons.Filled.AlternateEmail, contentDescription = null) },
                            trailingIcon = {
                                when (state.upgradeUsernameStatus) {
                                    UsernameCheckStatus.CHECKING -> {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    }
                                    UsernameCheckStatus.AVAILABLE -> {
                                        Icon(Icons.Filled.CheckCircle, contentDescription = "Available", tint = AgroSuccess)
                                    }
                                    UsernameCheckStatus.TAKEN, UsernameCheckStatus.INVALID -> {
                                        Icon(Icons.Filled.Cancel, contentDescription = "Unavailable", tint = MaterialTheme.colorScheme.error)
                                    }
                                    else -> {}
                                }
                            },
                            supportingText = {
                                if (!state.upgradeUsernameError.isNullOrBlank()) {
                                    Text(state.upgradeUsernameError ?: "", color = MaterialTheme.colorScheme.error)
                                } else if (state.upgradeUsernameStatus == UsernameCheckStatus.AVAILABLE) {
                                    Text("✓ Username is available", color = AgroSuccess)
                                }
                            },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("upgrade_username_input")
                        )

                        // Password with strength meter
                        OutlinedTextField(
                            value = state.upgradePassword,
                            onValueChange = { viewModel.onUpgradePasswordChange(it) },
                            label = { Text("New Password") },
                            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                            trailingIcon = {
                                IconButton(onClick = { viewModel.toggleUpgradePasswordVisibility() }) {
                                    Icon(
                                        imageVector = if (state.upgradePasswordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                        contentDescription = null
                                    )
                                }
                            },
                            visualTransformation = if (state.upgradePasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("upgrade_password_input")
                        )

                        // Password strength bar
                        if (state.upgradePassword.isNotEmpty()) {
                            PasswordStrengthIndicator(strength = state.upgradePasswordStrength)
                        }

                        // Confirm password
                        OutlinedTextField(
                            value = state.upgradeConfirmPassword,
                            onValueChange = { viewModel.onUpgradeConfirmPasswordChange(it) },
                            label = { Text("Confirm Password") },
                            leadingIcon = { Icon(Icons.Filled.LockReset, contentDescription = null) },
                            visualTransformation = if (state.upgradePasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("upgrade_confirm_password_input")
                        )
                    }
                }
            },
            confirmButton = {
                if (!state.upgradeOtpVerified) {
                    Button(
                        onClick = { viewModel.verifyUpgradeOtp() },
                        enabled = state.upgradeOtpCode.length == 6 && !state.isUpgrading,
                        colors = ButtonDefaults.buttonColors(containerColor = AgroGreenPrimary)
                    ) {
                        Text(if (state.isUpgrading) "Verifying..." else "Verify")
                    }
                } else {
                    Button(
                        onClick = { viewModel.submitUpgradeCredentials(onSessionReady) },
                        enabled = state.upgradeUsernameStatus == UsernameCheckStatus.AVAILABLE &&
                                state.upgradePassword.length >= 8 &&
                                state.upgradePassword == state.upgradeConfirmPassword &&
                                !state.isUpgrading,
                        colors = ButtonDefaults.buttonColors(containerColor = AgroGreenPrimary)
                    ) {
                        Text(if (state.isUpgrading) "Saving..." else "Save & Continue")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelUpgradeFlow() }) {
                    Text("Cancel")
                }
            }
        )
    }

    // ==========================================
    // 1d. FORGOT PASSWORD FLOW DIALOG
    // ==========================================
    if (forgotState.isOpen) {
        AlertDialog(
            onDismissRequest = { viewModel.closeForgotPassword() },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.LockReset,
                        contentDescription = null,
                        tint = AgroGreenPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (forgotState.step) {
                            1 -> "Forgot Password"
                            2 -> "Verify Mobile"
                            else -> "Reset Password"
                        },
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (!forgotState.errorMessage.isNullOrBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = forgotState.errorMessage ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }

                    when (forgotState.step) {
                        1 -> {
                            Text(
                                text = "Enter your username or registered Sri Lankan mobile number to receive a verification code.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = forgotState.identifier,
                                onValueChange = { viewModel.onForgotIdentifierChange(it) },
                                label = { Text("Username or Mobile") },
                                placeholder = { Text("e.g. 0771234567 or username") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("forgot_identifier_input")
                            )
                        }
                        2 -> {
                            Text(
                                text = "We sent a 6-digit code to ${forgotState.maskedPhone}.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = forgotState.otpCode,
                                onValueChange = { viewModel.onForgotOtpChange(it) },
                                label = { Text("6-Digit Code") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("forgot_otp_input")
                            )
                            if (forgotState.otpCountdown > 0) {
                                Text(
                                    text = "Resend in ${forgotState.otpCountdown}s",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                TextButton(onClick = { viewModel.requestForgotOtp() }) {
                                    Text("Resend Code", color = AgroGreenPrimary)
                                }
                            }
                        }
                        3 -> {
                            Text(
                                text = "Enter and confirm your new password (minimum 8 characters).",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = forgotState.newPassword,
                                onValueChange = { viewModel.onForgotNewPasswordChange(it) },
                                label = { Text("New Password") },
                                visualTransformation = if (forgotState.isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { viewModel.toggleForgotNewPasswordVisibility() }) {
                                        Icon(
                                            imageVector = if (forgotState.isPasswordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                            contentDescription = null
                                        )
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("forgot_new_password_input")
                            )
                            if (forgotState.newPassword.isNotEmpty()) {
                                PasswordStrengthIndicator(strength = forgotState.passwordStrength)
                            }
                            OutlinedTextField(
                                value = forgotState.confirmNewPassword,
                                onValueChange = { viewModel.onForgotConfirmNewPasswordChange(it) },
                                label = { Text("Confirm New Password") },
                                visualTransformation = if (forgotState.isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("forgot_confirm_password_input")
                            )
                        }
                    }
                }
            },
            confirmButton = {
                when (forgotState.step) {
                    1 -> {
                        Button(
                            onClick = { viewModel.requestForgotOtp() },
                            enabled = forgotState.identifier.isNotBlank() && !forgotState.isLoading,
                            colors = ButtonDefaults.buttonColors(containerColor = AgroGreenPrimary)
                        ) {
                            Text(if (forgotState.isLoading) "Sending..." else "Send Code")
                        }
                    }
                    2 -> {
                        Button(
                            onClick = { viewModel.verifyForgotOtp() },
                            enabled = forgotState.otpCode.length == 6 && !forgotState.isLoading,
                            colors = ButtonDefaults.buttonColors(containerColor = AgroGreenPrimary)
                        ) {
                            Text(if (forgotState.isLoading) "Verifying..." else "Verify")
                        }
                    }
                    3 -> {
                        Button(
                            onClick = { viewModel.submitResetPassword(onSessionReady) },
                            enabled = forgotState.newPassword.length >= 8 &&
                                    forgotState.newPassword == forgotState.confirmNewPassword &&
                                    !forgotState.isLoading,
                            colors = ButtonDefaults.buttonColors(containerColor = AgroGreenPrimary)
                        ) {
                            Text(if (forgotState.isLoading) "Resetting..." else "Update & Log In")
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.closeForgotPassword() }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun PasswordStrengthIndicator(strength: PasswordStrength) {
    val (color, fraction) = when (strength) {
        PasswordStrength.NONE -> Color.Gray to 0.0f
        PasswordStrength.WEAK -> MaterialTheme.colorScheme.error to 0.25f
        PasswordStrength.FAIR -> Color(0xFFF57F17) to 0.5f
        PasswordStrength.GOOD -> Color(0xFF1976D2) to 0.75f
        PasswordStrength.STRONG -> AgroSuccess to 1.0f
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Strength:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = strength.label,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, color = color)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}
