package com.leap8.offline.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.leap8.offline.domain.AiBackendSettings
import com.leap8.offline.domain.AiProvider
import com.leap8.offline.domain.AiProviderConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores non-secret provider settings in SharedPreferences and API keys in an
 * Android Keystore-backed AES/GCM envelope. Plaintext keys are never persisted. */
class AiBackendPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(readSettings())
    val state: StateFlow<AiBackendSettings> = _state.asStateFlow()

    fun select(provider: AiProvider) {
        preferences.edit().putString(KEY_SELECTED, provider.name).apply()
        _state.value = readSettings()
    }

    fun saveProvider(provider: AiProvider, endpoint: String, model: String, apiKey: String?) {
        require(provider != AiProvider.LOCAL) { "本地模型不需要 API 配置" }
        val normalizedEndpoint = validateEndpoint(endpoint)
        require(model.trim().isNotEmpty()) { "模型名称不能为空" }
        val edit = preferences.edit()
            .putString(endpointKey(provider), normalizedEndpoint)
            .putString(modelKey(provider), model.trim())
        if (!apiKey.isNullOrBlank()) edit.putString(secretKey(provider), encrypt(apiKey.trim()))
        edit.apply()
        _state.value = readSettings()
    }

    fun clearApiKey(provider: AiProvider) {
        preferences.edit().remove(secretKey(provider)).apply()
        _state.value = readSettings()
    }

    internal fun apiKey(provider: AiProvider): String? = preferences
        .getString(secretKey(provider), null)
        ?.let { encrypted -> runCatching { decrypt(encrypted) }.getOrNull() }
        ?.takeIf { it.isNotBlank() }

    private fun readSettings(): AiBackendSettings {
        val selected = preferences.getString(KEY_SELECTED, null)
            ?.let { runCatching { AiProvider.valueOf(it) }.getOrNull() }
            ?: AiProvider.LOCAL
        val configs = AiProvider.entries.associateWith { provider ->
            AiProviderConfig(
                provider = provider,
                endpoint = if (provider == AiProvider.LOCAL) "" else preferences.getString(endpointKey(provider), provider.defaultEndpoint) ?: provider.defaultEndpoint,
                model = preferences.getString(modelKey(provider), provider.defaultModel) ?: provider.defaultModel,
                keyConfigured = provider != AiProvider.LOCAL && apiKey(provider) != null
            )
        }
        return AiBackendSettings(selected, configs)
    }

    private fun validateEndpoint(value: String): String {
        val endpoint = value.trim().trimEnd('/')
        val uri = runCatching { URI(endpoint) }.getOrNull()
        require(uri != null && uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            "API 地址必须是有效的 HTTPS 地址"
        }
        return endpoint
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val payload = Base64.decode(value, Base64.NO_WRAP)
        require(payload.size > IV_BYTES) { "加密密钥数据无效" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, payload.copyOfRange(0, IV_BYTES)))
        return cipher.doFinal(payload.copyOfRange(IV_BYTES, payload.size)).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun endpointKey(provider: AiProvider) = "${provider.name.lowercase()}_endpoint"
    private fun modelKey(provider: AiProvider) = "${provider.name.lowercase()}_model"
    private fun secretKey(provider: AiProvider) = "${provider.name.lowercase()}_secret"

    private companion object {
        const val PREFERENCES = "ai_backend_settings"
        const val KEY_SELECTED = "selected_provider"
        const val KEYSTORE_ALIAS = "leap8_api_credentials_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
