package com.museroom.app.net

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialOption
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.museroom.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Signing in. Google is the intended route; email and password exists so the app
 * can be exercised before the Google client is configured.
 */
class AuthRepository private constructor(private val store: SessionStore) {

    val session get() = store.session

    /**
     * Native Google sign-in through Credential Manager.
     *
     * The nonce is doubled deliberately: Google is given a hash of it and embeds
     * that in the token, while Supabase is given the original and checks the two
     * agree. That is what stops a token issued for someone else being replayed.
     */
    suspend fun signInWithGoogle(activityContext: Context): Result<Session> = runCatching {
        require(Supabase.googleConfigured) {
            "Google sign-in is not configured. Add GOOGLE_WEB_CLIENT_ID to .env.local " +
                "and enable the Google provider in Supabase."
        }

        val rawNonce = randomNonce()
        val hashedNonce = sha256(rawNonce)
        val clientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
        val manager = CredentialManager.create(activityContext)

        // The user pressed a button, so they want the account chooser. That is
        // what GetSignInWithGoogleOption gives. GetGoogleIdOption is the One Tap
        // flow, which reports "no credentials available" when it has nothing
        // already authorised to offer, even with an account on the device.
        val credential = try {
            request(
                manager, activityContext,
                GetSignInWithGoogleOption.Builder(clientId).setNonce(hashedNonce).build(),
            )
        } catch (chooserFailed: NoCredentialException) {
            try {
                request(
                    manager, activityContext,
                    GetGoogleIdOption.Builder()
                        .setFilterByAuthorizedAccounts(false)
                        .setServerClientId(clientId)
                        .setNonce(hashedNonce)
                        .setAutoSelectEnabled(false)
                        .build(),
                )
            } catch (bothFailed: NoCredentialException) {
                throw IllegalStateException(
                    "No Google account is available to this app. Add one in Android " +
                        "Settings under Passwords & accounts, and make sure that address " +
                        "is a test user on the Google Cloud consent screen.",
                    bothFailed,
                )
            }
        }

        withContext(Dispatchers.IO) {
            Supabase.signInWithGoogle(credential.idToken, rawNonce)
        }.also(store::save)
    }

    private suspend fun request(
        manager: CredentialManager,
        activityContext: Context,
        option: CredentialOption,
    ): GoogleIdTokenCredential {
        val response = manager.getCredential(
            activityContext,
            GetCredentialRequest.Builder().addCredentialOption(option).build(),
        )
        return GoogleIdTokenCredential.createFrom(response.credential.data)
    }

    suspend fun signInWithPassword(email: String, password: String): Result<Session> =
        withContext(Dispatchers.IO) {
            runCatching { Supabase.signInWithPassword(email.trim(), password) }
                .onSuccess(store::save)
        }

    suspend fun signUpWithPassword(email: String, password: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching { Supabase.signUpWithPassword(email.trim(), password) }.map { }
        }

    /**
     * A valid access token, refreshing first if the current one is about to lapse.
     * Returns null when nobody is signed in, or when the refresh token is spent.
     */
    suspend fun validAccessToken(): String? = withContext(Dispatchers.IO) {
        val current = store.session.value ?: return@withContext null
        if (!current.expiringWithin()) return@withContext current.accessToken

        runCatching { Supabase.refresh(current.refreshToken) }
            .onSuccess(store::save)
            .map { it.accessToken }
            .getOrElse {
                if (it is SupabaseError && it.status in 400..403) store.clear()
                null
            }
    }

    fun signOut() = store.clear()

    private fun randomNonce(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        @Volatile private var instance: AuthRepository? = null

        fun get(context: Context): AuthRepository =
            instance ?: synchronized(this) {
                instance ?: AuthRepository(SessionStore.get(context)).also { instance = it }
            }
    }
}
