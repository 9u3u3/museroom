package com.museroom.app.net

import com.museroom.app.BuildConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A failed call, carrying enough to tell a user what to do about it. */
class SupabaseError(val status: Int, val body: String) : IOException(
    "Supabase call failed ($status): ${body.take(300)}",
)

/**
 * A thin client over Supabase's REST surface.
 *
 * Hand-written rather than pulled from a framework: the app touches five
 * endpoints, and this keeps the dependency list, the APK and the failure modes
 * small enough to hold in your head.
 */
object Supabase {

    val configured: Boolean
        get() = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    val googleConfigured: Boolean
        get() = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    private val url get() = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val anonKey get() = BuildConfig.SUPABASE_ANON_KEY

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val jsonMedia = "application/json".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // ------------------------------------------------------------------ auth --

    /** Exchanges a Google ID token for a Supabase session. */
    fun signInWithGoogle(idToken: String, rawNonce: String?): Session {
        val body = buildJsonObject {
            put("provider", "google")
            put("id_token", idToken)
            if (rawNonce != null) put("nonce", rawNonce)
        }
        return sessionFrom(post("$url/auth/v1/token?grant_type=id_token", body, token = null))
    }

    fun signInWithPassword(email: String, password: String): Session {
        val body = buildJsonObject {
            put("email", email)
            put("password", password)
        }
        return sessionFrom(post("$url/auth/v1/token?grant_type=password", body, token = null))
    }

    fun signUpWithPassword(email: String, password: String): JsonObject {
        val body = buildJsonObject {
            put("email", email)
            put("password", password)
        }
        return post("$url/auth/v1/signup", body, token = null)
    }

    fun refresh(refreshToken: String): Session {
        val body = buildJsonObject { put("refresh_token", refreshToken) }
        return sessionFrom(post("$url/auth/v1/token?grant_type=refresh_token", body, token = null))
    }

    private fun sessionFrom(payload: JsonObject): Session {
        val access = payload["access_token"]?.jsonPrimitive?.content
            ?: throw SupabaseError(500, "No access token in response")
        val refresh = payload["refresh_token"]?.jsonPrimitive?.content.orEmpty()
        val expiresIn = payload["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L
        val user = payload["user"] as? JsonObject
        return Session(
            accessToken = access,
            refreshToken = refresh,
            userId = user?.get("id")?.jsonPrimitive?.content.orEmpty(),
            email = user?.get("email")?.jsonPrimitive?.content.orEmpty(),
            expiresAtMs = System.currentTimeMillis() + expiresIn * 1000,
        )
    }

    // -------------------------------------------------------------- postgrest --

    /** Inserts rows. [upsertOnConflict] names the conflict target for an upsert. */
    fun insert(
        table: String,
        rows: JsonElement,
        accessToken: String,
        upsertOnConflict: String? = null,
    ) {
        var target = "$url/rest/v1/$table"
        if (upsertOnConflict != null) target += "?on_conflict=$upsertOnConflict"

        val request = Request.Builder()
            .url(target)
            .post(json.encodeToString(JsonElement.serializer(), rows).toRequestBody(jsonMedia))
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .header(
                "Prefer",
                if (upsertOnConflict != null) "resolution=merge-duplicates,return=minimal"
                else "return=minimal",
            )
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw SupabaseError(response.code, response.body?.string().orEmpty())
            }
        }
    }

    /** Deletes rows matching a PostgREST filter. Policies decide what is allowed. */
    fun delete(table: String, query: String, accessToken: String) {
        val request = Request.Builder()
            .url("$url/rest/v1/$table?$query")
            .delete()
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $accessToken")
            .header("Prefer", "return=minimal")
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw SupabaseError(response.code, response.body?.string().orEmpty())
            }
        }
    }

    fun select(table: String, query: String, accessToken: String): String {
        val request = Request.Builder()
            .url("$url/rest/v1/$table?$query")
            .get()
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $accessToken")
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw SupabaseError(response.code, body)
            return body
        }
    }

    private fun post(target: String, body: JsonObject, token: String?): JsonObject {
        val request = Request.Builder()
            .url(target)
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(jsonMedia))
            .header("apikey", anonKey)
            .header("Content-Type", "application/json")
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .build()

        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw SupabaseError(response.code, text)
            return json.parseToJsonElement(text) as JsonObject
        }
    }
}
