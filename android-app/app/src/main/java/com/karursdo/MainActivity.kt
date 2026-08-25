package com.karursdo

import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.karursdo.ui.theme.Brand
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.karursdo.data.repo.ActivityType
import com.karursdo.data.repo.AuthRepository
import com.karursdo.data.repo.LoginResult
import com.karursdo.data.repo.SeedLoader
import com.karursdo.data.repo.SessionManager
import com.karursdo.data.repo.SessionUser
import com.karursdo.data.repo.UserDataRepository
import com.karursdo.data.security.DbKeyManager
import com.karursdo.data.sync.SyncEngine
import com.karursdo.notify.AppNotifier
import com.karursdo.ui.nav.KsdApp
import com.karursdo.ui.splash.BrandedSplash
import com.karursdo.ui.theme.KarurSdoTheme
import com.karursdo.ui.theme.ThemeController
import com.karursdo.ui.theme.ThemeMode
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var seedLoader: SeedLoader
    @Inject lateinit var keyManager: DbKeyManager
    @Inject lateinit var themeController: ThemeController
    @Inject lateinit var userData: UserDataRepository
    @Inject lateinit var syncEngine: SyncEngine
    @Inject lateinit var authRepo: AuthRepository
    @Inject lateinit var session: SessionManager
    @Inject lateinit var appNotifier: AppNotifier

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    private var locked by mutableStateOf(true)
    private var splashDone by mutableStateOf(false)
    private var loggingIn by mutableStateOf(false)
    private var loginError by mutableStateOf<String?>(null)
    private var needPasswordChange by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Screenshots are allowed app-wide (FLAG_SECURE intentionally not set).

        // Restore a persisted signed-in session so the app opens straight to the content and never
        // re-prompts for biometric until the user explicitly logs out.
        val restoredUser = keyManager.activeUsername
        if (restoredUser != null) locked = false

        lifecycleScope.launch {
            runCatching { seedLoader.seedIfEmpty() }
            // Pull the latest accounts BEFORE login so a user created on another device
            // can sign in here, then seed the default admin only if nothing exists at all.
            runCatching { syncEngine.refreshUsers() }
            runCatching { authRepo.seedAdminIfEmpty() }
            // Re-hydrate the in-memory session from the persisted username.
            if (restoredUser != null) {
                val acct = runCatching { authRepo.accountByUsername(restoredUser) }.getOrNull()
                if (acct != null && acct.active) {
                    session.setCurrent(SessionUser(acct.username, acct.displayName, acct.role))
                    keyManager.lastUsername = acct.username
                    needPasswordChange = acct.mustChangePassword
                    locked = false
                    runCatching { syncEngine.syncNow() }
                } else {
                    // Account removed/disabled elsewhere — fall back to the login gate.
                    keyManager.activeUsername = null
                    locked = keyManager.appLockEnabled
                }
            }
        }

        // Ask for notification permission on Android 13+ (no-op on older versions).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Foreground poll: while the app is on-screen, check for new chat/programme items
        // every ~20s (near-real-time). The WorkManager job covers the background case.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    if (!locked) runCatching { appNotifier.syncAndNotify() }
                    delay(20_000)
                }
            }
        }

        // Re-lock when the app goes to background — unless we deliberately backgrounded for an
        // in-app flow (e.g. the file picker), in which case consume the one-shot suppression flag.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    if (AppLock.suppressNextAutoLock) {
                        AppLock.suppressNextAutoLock = false
                    } else if (keyManager.appLockEnabled && keyManager.activeUsername == null) {
                        // Only re-lock when nobody is signed in. Once signed in, the app stays
                        // unlocked across backgrounding until the user explicitly logs out.
                        locked = true
                    }
                }
            }
        )

        setContent {
            val themeMode by themeController.mode.collectAsState()
            val dark = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                )
            }
            KarurSdoTheme(darkTheme = dark) {
                val reduceMotion = android.provider.Settings.Global.getFloat(
                    contentResolver,
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
                ) == 0f

                when {
                    !splashDone -> BrandedSplash(reduceMotion = reduceMotion, dark = dark) {
                        splashDone = true
                        // Go straight to the login screen — do NOT auto-prompt for
                        // biometric/PIN. The user can tap "Use biometric" if they want it.
                        if (!keyManager.appLockEnabled) locked = false
                    }
                    locked && keyManager.appLockEnabled -> LoginGate(
                        error = loginError,
                        loggingIn = loggingIn,
                        biometricAvailable = keyManager.lastUsername != null && canAuthenticate(),
                        lastUser = keyManager.lastUsername,
                        onUseBiometric = { authenticateBiometric() },
                        onSubmit = { u, p -> attemptLogin(u, p) }
                    )
                    needPasswordChange -> ForceChangePassword(
                        onSave = { newPassword -> changeOwnPassword(newPassword) }
                    )
                    else -> KsdApp(onLogout = { logout() })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // No automatic biometric prompt — the login screen offers it as an option instead.
    }

    private fun attemptLogin(username: String, password: String) {
        if (loggingIn) return
        loggingIn = true
        loginError = null
        lifecycleScope.launch {
            when (val res = authRepo.login(username, password)) {
                is LoginResult.Success -> {
                    session.setCurrent(SessionUser(res.user.username, res.user.displayName, res.user.role))
                    keyManager.lastUsername = res.user.username
                    keyManager.activeUsername = res.user.username
                    needPasswordChange = res.user.mustChangePassword
                    loggingIn = false
                    onLoggedIn()
                }
                LoginResult.Disabled -> {
                    loggingIn = false
                    loginError = "This account is disabled. Contact your administrator."
                }
                LoginResult.BadCredentials -> {
                    loggingIn = false
                    loginError = "Wrong username or password."
                }
            }
        }
    }

    private fun changeOwnPassword(newPassword: String) {
        val username = session.current.value?.username ?: return
        lifecycleScope.launch {
            if (authRepo.setPassword(username, newPassword)) {
                needPasswordChange = false
                runCatching { userData.log(ActivityType.PROFILE, summary = "Password changed") }
                runCatching { syncEngine.syncNow() }
            }
        }
    }

    /** Unlock the app, record the sign-in, and kick off a best-effort cloud sync. */
    private fun onLoggedIn() {
        locked = false
        lifecycleScope.launch {
            runCatching {
                userData.log(
                    ActivityType.LOGIN,
                    summary = "Signed in as ${session.authorName() ?: "user"}"
                )
            }
            runCatching { syncEngine.syncNow() }
        }
        registerPushToken()
    }

    /** Best-effort FCM token registration. No-op when Firebase isn't configured in this build. */
    private fun registerPushToken() {
        runCatching {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    lifecycleScope.launch {
                        runCatching {
                            syncEngine.registerPushToken(session.current.value?.username, token)
                        }
                    }
                }
        }
    }

    private fun logout() {
        session.clear()
        // Forget the remembered user so biometric can't silently sign them back in, and end the
        // persisted session so the app locks and asks to sign in again.
        keyManager.lastUsername = null
        keyManager.activeUsername = null
        loginError = null
        needPasswordChange = false
        locked = true
    }

    private fun canAuthenticate(): Boolean = runCatching {
        BiometricManager.from(this).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }.getOrDefault(false)

    /** Biometric quick-unlock for the last user who signed in on this device. */
    private fun authenticateBiometric() {
        val username = keyManager.lastUsername ?: return
        runCatching {
            val prompt = BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        // Load the remembered account and sign in (no password needed after biometric).
                        lifecycleScope.launch {
                            val acct = runCatching { authRepo.accountByUsername(username) }.getOrNull()
                            if (acct != null && acct.active) {
                                session.setCurrent(SessionUser(acct.username, acct.displayName, acct.role))
                                keyManager.activeUsername = acct.username
                                needPasswordChange = acct.mustChangePassword
                                onLoggedIn()
                            }
                        }
                    }
                }
            )
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Unlock Karur SDO")
                    .setSubtitle("Signing in as $username")
                    .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
                    .build()
            )
        }
    }
}

/** Professional login: dark brand header card over a surface form card, theme-aware. */
@Composable
private fun LoginGate(
    error: String?,
    loggingIn: Boolean,
    biometricAvailable: Boolean,
    lastUser: String?,
    onUseBiometric: () -> Unit,
    onSubmit: (String, String) -> Unit
) {
    var username by remember { mutableStateOf(lastUser.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 440.dp).padding(24.dp)
        ) {
            // Government of India emblem at the top, on a white disc so it always shows.
            Surface(
                shape = CircleShape,
                color = Color.White,
                shadowElevation = 2.dp,
                modifier = Modifier.size(104.dp)
            ) {
                Image(
                    painter = painterResource(R.drawable.gov_india_emblem),
                    contentDescription = "Government of India emblem",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(12.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            // Brand header (solid dark, no gradient) — title only, no logo.
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Brand.PrimaryDark,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp, horizontal = 20.dp)
                ) {
                    Text(
                        "Karur Sub Division Directory",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "Directory & Office Management",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("Sign in", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it.trim() },
                        label = { Text("User ID") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Filled.VisibilityOff
                                    else Icons.Filled.Visibility,
                                    contentDescription = if (passwordVisible) "Hide password"
                                    else "Show password"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    error?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(18.dp))
                    Button(
                        onClick = { onSubmit(username, password) },
                        enabled = !loggingIn && username.isNotBlank() && password.isNotEmpty(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Brand.PrimaryDark),
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) { Text(if (loggingIn) "Signing in…" else "Sign in", fontWeight = FontWeight.SemiBold) }
                    if (biometricAvailable) {
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            HorizontalDivider(Modifier.weight(1f))
                            Text(
                                "  or  ",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            HorizontalDivider(Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(16.dp))
                        BiometricUnlockButton(onClick = onUseBiometric)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "made by Arun Selvaraj",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Brand.Muted,
                letterSpacing = 0.5.sp
            )
        }
    }
}

/** Attractive biometric / device-unlock button: a fingerprint badge + label on a tinted pill. */
@Composable
private fun BiometricUnlockButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = Brand.PrimaryDark.copy(alpha = 0.08f),
        border = BorderStroke(1.5.dp, Brand.PrimaryDark.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape).background(Brand.PrimaryDark),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Fingerprint,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Biometric / Device Unlock",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "Fingerprint, face or screen lock",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Forces a new password after first login (seeded admin / admin-reset accounts). */
@Composable
private fun ForceChangePassword(onSave: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 3.dp,
            modifier = Modifier.widthIn(max = 440.dp).padding(24.dp).fillMaxWidth()
        ) {
            Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Set a new password", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Please choose your own password to continue.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(18.dp))
                OutlinedTextField(
                    value = pw,
                    onValueChange = { pw = it; error = null },
                    label = { Text("New password (min 6)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it; error = null },
                    label = { Text("Confirm password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = {
                        when {
                            pw.length < 6 -> error = "At least 6 characters"
                            pw != confirm -> error = "Passwords don't match"
                            else -> onSave(pw)
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.PrimaryDark),
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) { Text("Save & continue", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}
