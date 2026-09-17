package com.example.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.example.BuildConfig
import com.example.model.RoleType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.providers.builtin.Phone
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.encodeToJsonElement
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Authentication service backed by the real Supabase (GoTrue) REST API.
 *
 * OTP delivery is selected by the build-time environment variable `OTP_MODE`:
 *   - `OTP_MODE=demo` (default): the mobile OTP flow is a self-contained, on-device
 *     emulation. A random 6-digit code is generated locally (full 000000..999999
 *     range), displayed on the OTP screen as a clearly labelled "Demo OTP" (never
 *     sent via SMS) and verified locally against an in-memory session with expiry,
 *     resend cooldown, attempt limit and one-time use. Only the registered demo
 *     collector number is accepted for the Informal Collector role.
 *   - `OTP_MODE=sms` (production): the code is generated, delivered and verified by
 *     Supabase (or the NestJS OTP service); the app never generates, displays, logs
 *     or speaks the OTP. When the provider is not configured the real flow returns an
 *     explicit [AuthErrorCode.PROVIDER_NOT_CONFIGURED] error instead of faking success.
 */
class SupabaseAuthService private constructor(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("supabase_auth_session", Context.MODE_PRIVATE)

    private val _currentPipelineStep = MutableStateFlow(AuthPipelineStep.IDLE)
    val currentPipelineStep: StateFlow<AuthPipelineStep> = _currentPipelineStep.asStateFlow()

    private val _authenticatedUser = MutableStateFlow<UserProfile?>(loadStoredSession())
    val authenticatedUser: StateFlow<UserProfile?> = _authenticatedUser.asStateFlow()

    /**
     * Demo-mode only: the latest locally generated OTP shown on the OTP verification
     * screen so testers can sign in without an SMS gateway. Always null when
     * `OTP_MODE=sms` or when no code is active.
     */
    private val _devDisplayOtp = MutableStateFlow<String?>(null)
    val devDisplayOtp: StateFlow<String?> = _devDisplayOtp.asStateFlow()

    private var activeOtpSession: OtpSession? = null
    private var lastResendTimestamp: Long = 0L

    /** True when `OTP_MODE=demo`; any value other than `sms` resolves to demo. */
    private val demoOtpMode: Boolean =
        !BuildConfig.OTP_MODE.trim().equals("sms", ignoreCase = true)

    companion object {
        private const val TAG = "SupabaseAuthService"
        private const val RESEND_COOLDOWN_SECONDS = 45L
        private const val OTP_VALIDITY_MS = 5 * 60 * 1000L
        private const val MAX_OTP_ATTEMPTS = 3

        /**
         * Registered demo Informal Collector number. In demo mode this is the only
         * mobile number accepted for the collector role — do not add dummy numbers.
         * Configured through `DEMO_OTP_PHONE` in `.env`.
         */
        private val DEMO_REGISTERED_PHONE: String =
            BuildConfig.DEMO_OTP_PHONE.filter { it.isDigit() }.takeLast(10)

        @Volatile
        private var instance: SupabaseAuthService? = null

        fun getInstance(context: Context): SupabaseAuthService {
            return instance ?: synchronized(this) {
                instance ?: SupabaseAuthService(context.applicationContext).also { instance = it }
            }
        }
    }

    private fun requirePhone(rawPhone: String): String {
        val digits = rawPhone.filter { it.isDigit() }
        if (digits.length !in 10..15) {
            throw IllegalArgumentException(AuthErrorCode.INVALID_OTP.userMessage.let {
                "Please enter a valid 10-digit mobile number"
            })
        }
        return digits
    }

    /** Converts a local 10-digit number into E.164 (+91) used by GoTrue. */
    @VisibleForTesting
    internal fun toE164(rawPhone: String): String {
        val digits = requirePhone(rawPhone)
        return if (digits.startsWith("91") && digits.length == 12) "+$digits" else "+91$digits"
    }

    private fun isConfigured(): Boolean = SupabaseAuthConfig.isConfigured()

    /**
     * True when the on-device (no-SMS) demo OTP flow is active (`OTP_MODE=demo`).
     * The UI uses this to label the flow as Demo Mode and surface the generated code.
     */
    fun isDemoOtpMode(): Boolean = demoOtpMode

    /**
     * Step 1: Request a verification code.
     *
     * `OTP_MODE=demo`: generates a random 6-digit code on-device (000000..999999),
     * stores it in the active session with expiry/attempts and exposes it via
     * [devDisplayOtp]. No SMS is sent and no network call is made.
     *
     * `OTP_MODE=sms`: requests a real code through Supabase; validation, OTP
     * generation, expiry and delivery are handled by the provider. The trailing 10
     * digits of the phone are retained locally only to drive the UI.
     */
    suspend fun generateAndSendOtp(
        destination: OtpDeliveryDestination = OtpDeliveryDestination.MOBILE_SMS,
        phoneNumber: String,
        email: String? = null,
        googleAccount: String? = null,
        targetRole: RoleType? = null
    ): Result<String> {
        if (demoOtpMode) {
            return generateDevOtp(phoneNumber, targetRole)
        }
        if (!isConfigured()) {
            return Result.failure(IllegalStateException(AuthErrorCode.PROVIDER_NOT_CONFIGURED.userMessage))
        }

        val cleanPhone = try { requirePhone(phoneNumber) } catch (e: IllegalArgumentException) {
            return Result.failure(IllegalArgumentException(e.message ?: "Please enter a valid 10-digit mobile number"))
        }

        // Cooldown between resend requests
        val now = System.currentTimeMillis()
        if (now - lastResendTimestamp < RESEND_COOLDOWN_SECONDS * 1000L) {
            val waitSec = RESEND_COOLDOWN_SECONDS - ((now - lastResendTimestamp) / 1000L)
            return Result.failure(IllegalStateException("Please wait $waitSec seconds before requesting a new code"))
        }

        return try {
            SupabaseAuthConfig.client.auth.signInWith(Phone) {
                phone = toE164(cleanPhone)
            }
            lastResendTimestamp = System.currentTimeMillis()
            activeOtpSession = OtpSession(
                destination = OtpDeliveryDestination.MOBILE_SMS,
                phoneNumber = cleanPhone,
                otpCode = "",
                createdAt = System.currentTimeMillis(),
                expiryTimestamp = System.currentTimeMillis() + OTP_VALIDITY_MS
            )
            Log.i(TAG, "SMS verification code requested for +91 $cleanPhone (code never stored locally)")
            Result.success("OTP sent")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            activeOtpSession = null
            Result.failure(sendError(e))
        }
    }

    /**
     * Backward-compatible mobile OTP sender.
     */
    suspend fun sendMobileOtp(phoneNumber: String): Result<String> =
        generateAndSendOtp(destination = OtpDeliveryDestination.MOBILE_SMS, phoneNumber = phoneNumber)

    fun getActiveOtpSession(): OtpSession? = activeOtpSession

    fun getResendCooldownRemaining(): Long {
        val elapsed = (System.currentTimeMillis() - lastResendTimestamp) / 1000L
        return maxOf(0L, RESEND_COOLDOWN_SECONDS - elapsed)
    }

    /**
     * Step 2: Verify the code against Supabase and run the statutory post-auth
     * pipeline (account status -> role -> permissions).
     *
     * `OTP_MODE=demo`: the entered code is checked against the locally stored OTP.
     * On success a synthetic local [UserProfile] is created for the requested role.
     * `OTP_MODE=sms`: the code is verified by Supabase.
     */
    suspend fun verifyOtpAndLogin(
        destination: OtpDeliveryDestination = OtpDeliveryDestination.MOBILE_SMS,
        phoneNumber: String?,
        email: String? = null,
        googleAccount: String? = null,
        enteredOtp: String,
        targetRole: RoleType
    ): AuthResult {
        if (demoOtpMode) {
            return verifyDevOtp(phoneNumber, enteredOtp, targetRole)
        }
        if (!isConfigured()) {
            return failure(AuthErrorCode.PROVIDER_NOT_CONFIGURED, AuthPipelineStep.AUTHENTICATING_USER)
        }
        val session = activeOtpSession
            ?: return failure(AuthErrorCode.OTP_NOT_REQUESTED, AuthPipelineStep.AUTHENTICATING_USER)
        if (session.isExpired) {
            return failure(
                AuthErrorCode.INVALID_OTP,
                AuthPipelineStep.AUTHENTICATING_USER,
                "The verification code has expired. Please tap 'Resend OTP'."
            )
        }
        if (enteredOtp.trim().length != 6) {
            return failure(AuthErrorCode.INVALID_OTP, AuthPipelineStep.AUTHENTICATING_USER, "Please enter the complete 6-digit code.")
        }

        val phone = phoneNumber?.takeIf { it.isNotBlank() }?.let { toE164(it) }
            ?: session.phoneNumber?.let { toE164(it) }
            ?: return failure(AuthErrorCode.INVALID_OTP, AuthPipelineStep.AUTHENTICATING_USER, "Mobile number missing. Please re-enter it.")

        _currentPipelineStep.value = AuthPipelineStep.AUTHENTICATING_USER
        return try {
            SupabaseAuthConfig.client.auth.verifyPhoneOtp(OtpType.Phone.SMS, phone, enteredOtp.trim())
            val supabaseUser = SupabaseAuthConfig.client.auth.currentUserOrNull()
            if (supabaseUser == null) {
                activeOtpSession = null
                return failure(AuthErrorCode.INVALID_OTP, AuthPipelineStep.AUTHENTICATING_USER)
            }
            activeOtpSession = null
            Log.i(TAG, "SMS OTP verified for $phone")
            executePostAuthPipeline(
                authMethod = AuthMethod.MOBILE_OTP,
                targetRole = targetRole,
                email = supabaseUser.email,
                phoneNumber = "+91 ${session.phoneNumber ?: phone.removePrefix("+91")}"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure(mapException(e), AuthPipelineStep.AUTHENTICATING_USER)
        }
    }

    /**
     * Backward-compatible mobile OTP verification.
     */
    suspend fun verifyMobileOtpAndLogin(
        phoneNumber: String,
        enteredOtp: String,
        targetRole: RoleType
    ): AuthResult = verifyOtpAndLogin(
        phoneNumber = phoneNumber,
        enteredOtp = enteredOtp,
        targetRole = targetRole
    )

    /**
     * Email & password authentication performed by Supabase GoTrue.
     */
    suspend fun loginWithEmail(
        email: String,
        password: String,
        targetRole: RoleType
    ): AuthResult {
        val trimmedEmail = email.trim().lowercase()
        val trimmedPassword = password.trim()

        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(trimmedEmail).matches()) {
            return failure(AuthErrorCode.INVALID_CREDENTIALS, AuthPipelineStep.AUTHENTICATING_USER, "Please enter a valid registered email address.")
        }
        if (trimmedPassword.length < 6) {
            return failure(AuthErrorCode.INVALID_CREDENTIALS, AuthPipelineStep.AUTHENTICATING_USER, "Password must be at least 6 characters long.")
        }
        if (demoOtpMode) {
            return createDemoEmailSession(trimmedEmail, targetRole)
        }
        if (!isConfigured()) {
            return failure(AuthErrorCode.PROVIDER_NOT_CONFIGURED, AuthPipelineStep.AUTHENTICATING_USER)
        }

        _currentPipelineStep.value = AuthPipelineStep.AUTHENTICATING_USER
        return try {
            SupabaseAuthConfig.client.auth.signInWith(Email) {
                this.email = trimmedEmail
                this.password = trimmedPassword
            }
            val user = SupabaseAuthConfig.client.auth.currentUserOrNull()
                ?: return failure(AuthErrorCode.INVALID_CREDENTIALS, AuthPipelineStep.AUTHENTICATING_USER)
            Log.i(TAG, "Email login succeeded for ${user.email}")
            executePostAuthPipeline(
                authMethod = AuthMethod.EMAIL_PASSWORD,
                targetRole = targetRole,
                email = user.email,
                phoneNumber = user.phone
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure(mapException(e), AuthPipelineStep.AUTHENTICATING_USER)
        }
    }

    @Deprecated("Remove path to simulated login. Use loginWithGoogleAuthResult() instead.")
    suspend fun loginWithGoogle(
        googleAccountEmail: String,
        googleAccountName: String,
        targetRole: RoleType
    ): AuthResult = failure(
        AuthErrorCode.PROVIDER_NOT_CONFIGURED,
        AuthPipelineStep.AUTHENTICATING_USER,
        "Direct account logins are not supported. Use 'Continue with Google'."
    )

    /**
     * Exchanges a Google id_token (from Credential Manager) with Supabase using
     * the ID Token sign-in flow, then runs the statutory pipeline.
     */
    suspend fun loginWithGoogleAuthResult(
        authResult: GoogleAuthResult,
        targetRole: RoleType
    ): AuthResult {
        if (!authResult.isSuccess) {
            return AuthResult(
                isSuccess = false,
                errorMessage = authResult.errorMessage ?: "Google Sign-In failed.",
                failureStep = AuthPipelineStep.AUTHENTICATING_USER,
                errorCode = AuthErrorCode.GENERIC
            )
        }
        val idToken = authResult.idToken
        if (idToken.isNullOrBlank()) {
            return failure(AuthErrorCode.GENERIC, AuthPipelineStep.AUTHENTICATING_USER, "Google could not provide an identity token.")
        }
        if (!isConfigured()) {
            return failure(AuthErrorCode.PROVIDER_NOT_CONFIGURED, AuthPipelineStep.AUTHENTICATING_USER)
        }

        _currentPipelineStep.value = AuthPipelineStep.AUTHENTICATING_USER
        return try {
            SupabaseAuthConfig.client.auth.signInWith(IDToken) {
                this.idToken = idToken
                provider = Google
                if (!authResult.nonce.isNullOrBlank()) nonce = authResult.nonce
            }
            val user = SupabaseAuthConfig.client.auth.currentUserOrNull()
                ?: return failure(AuthErrorCode.GENERIC, AuthPipelineStep.AUTHENTICATING_USER)
            Log.i(TAG, "Google ID token exchanged for ${user.email}")
            executePostAuthPipeline(
                authMethod = AuthMethod.GOOGLE_OAUTH,
                targetRole = targetRole,
                email = user.email,
                phoneNumber = user.phone
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure(mapException(e), AuthPipelineStep.AUTHENTICATING_USER)
        }
    }

    /**
     * Statutory pipeline executed after a positive GoTrue authentication:
     * 1. account status/KYC from `profiles`,
     * 2. role match versus the logged-in portal,
     * 3. permissions granted for that role.
     */
    private suspend fun executePostAuthPipeline(
        authMethod: AuthMethod,
        targetRole: RoleType,
        email: String?,
        phoneNumber: String?
    ): AuthResult {
        val client = SupabaseAuthConfig.client
        val supabaseUser = client.auth.currentUserOrNull()
            ?: return failure(AuthErrorCode.GENERIC, AuthPipelineStep.AUTHENTICATING_USER)

        // Step 2: Verify account & KYC profile from the database
        _currentPipelineStep.value = AuthPipelineStep.VERIFYING_ACCOUNT
        val profile = try {
            client.from("profiles")
                .select { filter { eq("auth_user_id", supabaseUser.id) } }
                .decodeSingleOrNull<ProfileRow>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return failure(mapException(e), AuthPipelineStep.VERIFYING_ACCOUNT)
        }

        if (profile == null) {
            // Profiles are provisioned centrally (see supabase/migrations). The app
            // never self-assigns a role: a missing profile means the account has not
            // been onboarded, so access is refused rather than granted.
            return failure(
                AuthErrorCode.ROLE_NOT_ASSIGNED,
                AuthPipelineStep.VERIFYING_ACCOUNT,
                "This account has no assigned role. Contact the CPCB helpdesk to complete onboarding."
            )
        }

        return executePostAuthPipelineWithProfile(client, supabaseUser, profile, authMethod, targetRole, email, phoneNumber)
    }

    private suspend fun executePostAuthPipelineWithProfile(
        client: io.github.jan.supabase.SupabaseClient,
        supabaseUser: io.github.jan.supabase.auth.user.UserInfo,
        profile: ProfileRow,
        authMethod: AuthMethod,
        targetRole: RoleType,
        email: String?,
        phoneNumber: String?
    ): AuthResult {

        val assignedRole = mapRole(profile.role)
            ?: return failure(
                AuthErrorCode.ROLE_NOT_ASSIGNED,
                AuthPipelineStep.VERIFYING_ACCOUNT,
                "No recognized role has been assigned to this account."
            )

        // Step 3: Verify statutory role versus requested portal
        _currentPipelineStep.value = AuthPipelineStep.VERIFYING_ROLE
        if (assignedRole != targetRole) {
            val message = when (assignedRole) {
                RoleType.INFORMAL_COLLECTOR -> "This account is registered as an Informal Collector. Continue to the Collector Dashboard?"
                RoleType.FORMAL_RECYCLER -> "This account is registered as a Formal Recycler. Continue to the Recycler Dashboard?"
                RoleType.GOVERNMENT_ADMIN -> "This account is registered as a Government Admin. Continue to the Admin Portal?"
            }
            return failure(AuthErrorCode.ROLE_MISMATCH, AuthPipelineStep.VERIFYING_ROLE, message)
        }

        // Step 3b: Verify account status
        val accountStatus = mapStatus(profile.account_status)
        if (!accountStatus.isAllowed) {
            val statusMessage = when (accountStatus) {
                AccountStatus.PENDING_VERIFICATION -> "Your registration is pending KYC / approval. You will be able to log in once approved."
                AccountStatus.REJECTED -> "Your registration was rejected. Contact the CPCB helpdesk for clarification."
                AccountStatus.SUSPENDED -> "This account is suspended pending a compliance audit. Contact the CPCB helpdesk."
                else -> "This account has been disabled by the administrator."
            }
            return failure(AuthErrorCode.ACCOUNT_GATED, AuthPipelineStep.VERIFYING_ROLE, statusMessage)
        }

        // Step 4: Permissions for the verified role
        _currentPipelineStep.value = AuthPipelineStep.VERIFYING_PERMISSIONS
        val session = client.auth.currentSessionOrNull()
        _currentPipelineStep.value = AuthPipelineStep.SUCCESS

        val profileUser = UserProfile(
            userId = supabaseUser.id,
            displayName = profile.display_name?.takeIf { it.isNotBlank() }
                ?: (email ?: phoneNumber ?: "Registered User"),
            email = email,
            phoneNumber = phoneNumber,
            role = targetRole,
            accountStatus = AccountStatus.ACTIVE,
            permissions = defaultPermissionsOf(targetRole),
            statutoryIdentifier = profile.statutory_identifier ?: "",
            entityName = profile.entity_name ?: "",
            sessionToken = session?.accessToken ?: "",
            authMethod = authMethod,
            verifiedTimestamp = System.currentTimeMillis()
        )
        _authenticatedUser.value = profileUser
        saveSession(profileUser)

        return AuthResult(
            isSuccess = true,
            userProfile = profileUser
        )
    }

    private fun defaultPermissionsOf(role: RoleType): List<String> = when (role) {
        RoleType.INFORMAL_COLLECTOR -> listOf(
            "COLL_ISSUE_RECEIPT", "COLL_VIEW_RATES", "COLL_DIGITAL_WEIGH_IN", "COLL_EPR_CREDIT_ACCRUAL"
        )
        RoleType.FORMAL_RECYCLER -> listOf(
            "REC_CPCB_INBOUND_ACCEPT", "REC_EPR_CERTIFICATE_MINT", "REC_HAZARDOUS_NEUTRALIZE", "REC_DIRECT_ESCROW_SETTLE"
        )
        RoleType.GOVERNMENT_ADMIN -> listOf(
            "ADM_CPCB_PAN_INDIA_OVERSIGHT", "ADM_EPR_COMPLIANCE_AUDIT", "ADM_FACILITY_GEO_INSPECT", "ADM_RULE_13_PENALTY_NOTICE"
        )
    }

    // -----------------------------------------------------------------------
    // DEMO MODE (OTP_MODE=demo): on-device OTP — never used when OTP_MODE=sms
    // -----------------------------------------------------------------------

    /**
     * Generate a random 6-digit OTP in the full range 000000..999999 for demo mode.
     * The code is stored in [activeOtpSession] with expiry, attempt limit and
     * one-time use, and displayed via [devDisplayOtp]. No SMS is sent and no network
     * call is made. For the Informal Collector role only the registered demo number
     * ([DEMO_REGISTERED_PHONE]) is accepted.
     */
    private suspend fun generateDevOtp(phoneNumber: String, targetRole: RoleType?): Result<String> {
        val cleanPhone = try {
            requirePhone(phoneNumber)
        } catch (e: IllegalArgumentException) {
            return Result.failure(IllegalArgumentException(e.message ?: "Please enter a valid 10-digit mobile number"))
        }

        if (targetRole == RoleType.INFORMAL_COLLECTOR &&
            DEMO_REGISTERED_PHONE.isNotEmpty() &&
            cleanPhone != DEMO_REGISTERED_PHONE
        ) {
            return Result.failure(
                IllegalStateException(
                    "This number is not registered for the demo. Use +91 $DEMO_REGISTERED_PHONE."
                )
            )
        }

        val now = System.currentTimeMillis()
        if (now - lastResendTimestamp < RESEND_COOLDOWN_SECONDS * 1000L) {
            val waitSec = RESEND_COOLDOWN_SECONDS - ((now - lastResendTimestamp) / 1000L)
            return Result.failure(IllegalStateException("Please wait $waitSec seconds before requesting a new code"))
        }

        val code = String.format("%06d", Random.nextInt(1_000_000))

        activeOtpSession = OtpSession(
            destination = OtpDeliveryDestination.MOBILE_SMS,
            phoneNumber = cleanPhone,
            otpCode = code,
            createdAt = now,
            expiryTimestamp = now + OTP_VALIDITY_MS,
            attemptsRemaining = MAX_OTP_ATTEMPTS,
            isLocked = false,
            lockExpiryTimestamp = null
        )
        lastResendTimestamp = now
        _devDisplayOtp.value = code
        Log.i(TAG, "[DEMO] Generated OTP $code for +91 $cleanPhone (no SMS sent)")
        return Result.success(code)
    }

    /**
     * Verify the entered OTP against the locally stored session and create a
     * synthetic [UserProfile] on success. Handles expiry, attempts and locking.
     */
    private suspend fun verifyDevOtp(
        phoneNumber: String?,
        enteredOtp: String,
        targetRole: RoleType
    ): AuthResult {
        _currentPipelineStep.value = AuthPipelineStep.AUTHENTICATING_USER

        val session = activeOtpSession
            ?: return failure(AuthErrorCode.OTP_NOT_REQUESTED, AuthPipelineStep.AUTHENTICATING_USER)

        if (session.isExpired) {
            _devDisplayOtp.value = null
            activeOtpSession = null
            return failure(
                AuthErrorCode.INVALID_OTP,
                AuthPipelineStep.AUTHENTICATING_USER,
                "The verification code has expired. Please tap 'Resend OTP'."
            )
        }

        if (session.isLocked) {
            _devDisplayOtp.value = null
            return failure(
                AuthErrorCode.INVALID_OTP,
                AuthPipelineStep.AUTHENTICATING_USER,
                "Too many incorrect attempts. Please request a new code."
            )
        }

        val clean = enteredOtp.trim()
        if (clean.length != 6) {
            return failure(AuthErrorCode.INVALID_OTP, AuthPipelineStep.AUTHENTICATING_USER,
                "Please enter the complete 6-digit code.")
        }

        if (clean != session.otpCode) {
            session.attemptsRemaining -= 1
            if (session.attemptsRemaining <= 0) {
                session.isLocked = true
                activeOtpSession = null
                _devDisplayOtp.value = null
                return failure(
                    AuthErrorCode.INVALID_OTP,
                    AuthPipelineStep.AUTHENTICATING_USER,
                    "Too many incorrect attempts. Please request a new code."
                )
            }
            return failure(
                AuthErrorCode.INVALID_OTP,
                AuthPipelineStep.AUTHENTICATING_USER,
                "Incorrect code. ${session.attemptsRemaining} attempt(s) remaining."
            )
        }

        // OTP matched — create a synthetic local session (no Supabase user involved)
        val phone = session.phoneNumber ?: phoneNumber?.filter { it.isDigit() }?.takeLast(10)
        val profileUser = UserProfile(
            userId = "dev-${phone ?: "user"}",
            displayName = "+91 $phone",
            email = null,
            phoneNumber = "+91 $phone",
            role = targetRole,
            accountStatus = AccountStatus.ACTIVE,
            permissions = defaultPermissionsOf(targetRole),
            statutoryIdentifier = "",
            entityName = "",
            sessionToken = "",
            authMethod = AuthMethod.MOBILE_OTP,
            verifiedTimestamp = System.currentTimeMillis()
        )

        activeOtpSession = null
        _devDisplayOtp.value = null
        _authenticatedUser.value = profileUser
        saveSession(profileUser)
        _currentPipelineStep.value = AuthPipelineStep.SUCCESS
        Log.i(TAG, "[DEMO] OTP verified for +91 $phone — local session created for ${targetRole.name}")
        return AuthResult(isSuccess = true, userProfile = profileUser)
    }

    /**
     * Demo-mode only: builds an offline local session for recycler/admin email
     * logins without contacting Supabase. Mirrors [verifyDevOtp] so the whole
     * role workflow can be exercised without a configured backend.
     */
    private fun createDemoEmailSession(email: String, targetRole: RoleType): AuthResult {
        val (entity, statutory) = when (targetRole) {
            RoleType.FORMAL_RECYCLER -> "Demo E-Waste Recycling Facility" to "CPCB-RECYCLE-DEMO"
            RoleType.GOVERNMENT_ADMIN -> "MoEFCC Demo Regulatory Cell" to "MoEFCC-ADMIN-DEMO"
            RoleType.INFORMAL_COLLECTOR -> "Demo Informal Collector" to DEMO_REGISTERED_PHONE
        }
        val profileUser = UserProfile(
            userId = "dev-email-$email",
            displayName = email,
            email = email,
            phoneNumber = null,
            role = targetRole,
            accountStatus = AccountStatus.ACTIVE,
            permissions = defaultPermissionsOf(targetRole),
            statutoryIdentifier = statutory,
            entityName = entity,
            sessionToken = "",
            authMethod = AuthMethod.EMAIL_PASSWORD,
            verifiedTimestamp = System.currentTimeMillis()
        )
        _authenticatedUser.value = profileUser
        saveSession(profileUser)
        _currentPipelineStep.value = AuthPipelineStep.SUCCESS
        Log.i(TAG, "[DEMO] Email login for $email — local session created for ${targetRole.name}")
        return AuthResult(isSuccess = true, userProfile = profileUser)
    }

    // -----------------------------------------------------------------------

    private fun failure(
        errorCode: AuthErrorCode,
        step: AuthPipelineStep,
        message: String? = null
    ): AuthResult {
        _currentPipelineStep.value = AuthPipelineStep.FAILED
        return AuthResult(
            isSuccess = false,
            errorMessage = message ?: errorCode.userMessage,
            failureStep = step,
            errorCode = errorCode
        )
    }

    private fun sendError(e: Exception): Exception {
        val code = mapException(e)
        return IllegalStateException(code.userMessage, e)
    }

    private fun mapException(e: Exception): AuthErrorCode = when (e) {
        is CancellationException -> throw e
        is UnknownHostException, is ConnectException, is SocketTimeoutException, is java.net.SocketException -> AuthErrorCode.NETWORK_ERROR
        is java.net.HttpRetryException -> AuthErrorCode.NETWORK_ERROR
        is java.io.IOException -> AuthErrorCode.NETWORK_ERROR
        is AuthRestException -> mapRestException(e)
        else -> AuthErrorCode.GENERIC
    }

    private fun mapRestException(e: AuthRestException): AuthErrorCode {
        val name = e.errorCode?.name?.uppercase() ?: ""
        return when {
            name.contains("OTP") || name.contains("TOKEN_EXPIRED") || name.contains("EXPIRED") ||
                name.contains("INVALID_CODE") || name == "WRONG_OTP_VERIFY_SETTING" -> AuthErrorCode.INVALID_OTP
            name.contains("INVALID_CREDENTIALS") || name.contains("WRONG_PASSWORD") -> AuthErrorCode.INVALID_CREDENTIALS
            name.contains("EMAIL_NOT_CONFIRMED") || name.contains("PHONE_NOT_CONFIRMED") || name.contains("UNVERIFIED") -> AuthErrorCode.EMAIL_NOT_CONFIRMED
            name.contains("USER_NOT_FOUND") || name.contains("ACCOUNT_NOT_FOUND") -> AuthErrorCode.ACCOUNT_NOT_FOUND
            name.contains("USER_ALREADY_EXISTS") || name.contains("ALREADY_REGISTERED") -> AuthErrorCode.EMAIL_ALREADY_REGISTERED
            name.contains("RATE_LIMITED") || name.contains("OVER_REQUEST") ->
                AuthErrorCode.NETWORK_ERROR
            name.contains("SMS_SEND") || name.contains("PROVIDER") || name.contains("SIGNUP_DISABLED") ->
                AuthErrorCode.PROVIDER_NOT_CONFIGURED
            else -> AuthErrorCode.GENERIC
        }
    }

    fun signOut() {
        _authenticatedUser.value = null
        _currentPipelineStep.value = AuthPipelineStep.IDLE
        // Clear all OTP state (code, expiry, attempts) and the resend cooldown so a
        // subsequent login starts fresh. Unrelated preferences (e.g. language) live in
        // a different SharedPreferences file and are intentionally left untouched.
        activeOtpSession = null
        _devDisplayOtp.value = null
        lastResendTimestamp = 0L
        prefs.edit().clear().apply()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (isConfigured()) SupabaseAuthConfig.client.auth.signOut()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Local sign-out completed; remote sign-out skipped: ${e.message}")
            }
        }
    }

    private fun saveSession(profile: UserProfile) {
        prefs.edit().apply {
            putString("user_id", profile.userId)
            putString("display_name", profile.displayName)
            putString("email", profile.email)
            putString("phone", profile.phoneNumber)
            putString("role", profile.role.name)
            putString("status", profile.accountStatus.name)
            putString("statutory_id", profile.statutoryIdentifier)
            putString("entity_name", profile.entityName)
            putString("token", profile.sessionToken)
            putString("auth_method", profile.authMethod.name)
            putLong("verified_time", profile.verifiedTimestamp)
            apply()
        }
    }

    private fun loadStoredSession(): UserProfile? {
        val userId = prefs.getString("user_id", null) ?: return null
        val roleStr = prefs.getString("role", null) ?: return null
        val role = try { RoleType.valueOf(roleStr) } catch (e: Exception) { return null }

        return UserProfile(
            userId = userId,
            displayName = prefs.getString("display_name", "Authorized User") ?: "Authorized User",
            email = prefs.getString("email", null),
            phoneNumber = prefs.getString("phone", null),
            role = role,
            accountStatus = AccountStatus.ACTIVE,
            permissions = defaultPermissionsOf(role),
            statutoryIdentifier = prefs.getString("statutory_id", "") ?: "",
            entityName = prefs.getString("entity_name", "") ?: "",
            sessionToken = prefs.getString("token", "") ?: "",
            authMethod = AuthMethod.valueOf(prefs.getString("auth_method", AuthMethod.MOBILE_OTP.name) ?: AuthMethod.MOBILE_OTP.name),
            verifiedTimestamp = prefs.getLong("verified_time", System.currentTimeMillis())
        )
    }

    private fun mapRole(value: String?): RoleType? = when (value?.trim()?.lowercase()) {
        "informal_collector", "collector" -> RoleType.INFORMAL_COLLECTOR
        "formal_recycler", "recycler" -> RoleType.FORMAL_RECYCLER
        "government_admin", "admin" -> RoleType.GOVERNMENT_ADMIN
        else -> null
    }

    private fun mapStatus(value: String?): AccountStatus = when (value?.trim()?.lowercase()) {
        "pending", "pending_verification" -> AccountStatus.PENDING_VERIFICATION
        "rejected" -> AccountStatus.REJECTED
        "suspended" -> AccountStatus.SUSPENDED
        "disabled" -> AccountStatus.DISABLED
        else -> AccountStatus.ACTIVE
    }
}