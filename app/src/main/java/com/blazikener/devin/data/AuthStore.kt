package com.blazikener.devin.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Account(
    val apiKey: String,
    val orgId: String,
    val name: String,
    val userId: String?,
    val baseUrl: String,
)

/** Stores the API key in Keystore-backed encrypted prefs; never in plain text. */
class AuthStore(context: Context) {
    private val prefs: SharedPreferences = runCatching { encrypted(context) }.getOrElse {
        // Keystore can be corrupted after a backup restore; start fresh instead of crashing.
        context.deleteSharedPreferences(FILE)
        encrypted(context)
    }

    private val _account = MutableStateFlow(read())
    val account: StateFlow<Account?> = _account

    val baseUrl: String get() = _account.value?.baseUrl ?: DEFAULT_BASE_URL

    fun save(a: Account) {
        prefs.edit()
            .putString("api_key", a.apiKey).putString("org_id", a.orgId)
            .putString("name", a.name).putString("user_id", a.userId)
            .putString("base_url", a.baseUrl).apply()
        _account.value = a
    }

    /** API URL of the last account, kept after sign-out so enterprise users don't retype it. */
    val lastBaseUrl: String get() = prefs.getString("base_url", null) ?: DEFAULT_BASE_URL

    fun clear() {
        val base = prefs.getString("base_url", null)
        prefs.edit().clear().apply {
            if (base != null && base != DEFAULT_BASE_URL) putString("base_url", base)
        }.apply()
        _account.value = null
    }

    private fun read(): Account? {
        val key = prefs.getString("api_key", null) ?: return null
        val org = prefs.getString("org_id", null) ?: return null
        return Account(
            key, org, prefs.getString("name", null) ?: "Devin user",
            prefs.getString("user_id", null), prefs.getString("base_url", null) ?: DEFAULT_BASE_URL,
        )
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.devin.ai"
        private const val FILE = "devin_secure"
        private fun encrypted(context: Context): SharedPreferences {
            val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(
                context, FILE, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
