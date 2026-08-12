package com.goodlight.floatingvoicebubble

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class RecognitionMode { AUTO, SYSTEM, ON_DEVICE, SHERPA_STREAMING }
enum class CorrectionMode { AUTO, BYOK, GEMMA, NONE }
enum class GemmaBackend { AUTO, GPU, CPU }
enum class GemmaVariant { UNKNOWN, E2B, E4B }

data class AppSettings(
    val recognitionMode: RecognitionMode = RecognitionMode.AUTO,
    val correctionMode: CorrectionMode = CorrectionMode.AUTO,
    val offlineMode: Boolean = false,
    val autoStop: Boolean = true,
    val byokEndpoint: String = "https://api.openai.com/v1/chat/completions",
    val byokModel: String = "",
    val gemmaModelPath: String = "",
    val gemmaBackend: GemmaBackend = GemmaBackend.AUTO,
    val gemmaVariant: GemmaVariant = GemmaVariant.UNKNOWN,
    val streamingAsrModelId: String = "",
    val keepSessionTraces: Boolean = true,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("floating_voice_bubble", Context.MODE_PRIVATE)
    private val secrets = KeystoreSecrets(context)

    fun load(): AppSettings = AppSettings(
        recognitionMode = enumValueOr(prefs.getString("recognition_mode", null), RecognitionMode.AUTO),
        correctionMode = enumValueOr(prefs.getString("correction_mode", null), CorrectionMode.AUTO),
        offlineMode = prefs.getBoolean("offline_mode", false),
        autoStop = prefs.getBoolean("auto_stop", true),
        byokEndpoint = prefs.getString("byok_endpoint", null)
            ?: "https://api.openai.com/v1/chat/completions",
        byokModel = prefs.getString("byok_model", "").orEmpty(),
        gemmaModelPath = prefs.getString("gemma_model_path", "").orEmpty(),
        gemmaBackend = enumValueOr(prefs.getString("gemma_backend", null), GemmaBackend.AUTO),
        gemmaVariant = enumValueOr(prefs.getString("gemma_variant", null), GemmaVariant.UNKNOWN),
        streamingAsrModelId = prefs.getString("streaming_asr_model_id", "").orEmpty(),
        keepSessionTraces = prefs.getBoolean("keep_session_traces", true),
    )

    fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        val value = transform(load())
        prefs.edit()
            .putString("recognition_mode", value.recognitionMode.name)
            .putString("correction_mode", value.correctionMode.name)
            .putBoolean("offline_mode", value.offlineMode)
            .putBoolean("auto_stop", value.autoStop)
            .putString("byok_endpoint", value.byokEndpoint)
            .putString("byok_model", value.byokModel)
            .putString("gemma_model_path", value.gemmaModelPath)
            .putString("gemma_backend", value.gemmaBackend.name)
            .putString("gemma_variant", value.gemmaVariant.name)
            .putString("streaming_asr_model_id", value.streamingAsrModelId)
            .putBoolean("keep_session_traces", value.keepSessionTraces)
            .apply()
        return value
    }

    fun apiKey(): String = secrets.read(KEY_API_KEY)
    fun setApiKey(value: String) = secrets.write(KEY_API_KEY, value)

    private inline fun <reified T : Enum<T>> enumValueOr(raw: String?, fallback: T): T =
        raw?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    companion object {
        private const val KEY_API_KEY = "byok_api_key"
    }
}

private class KeystoreSecrets(context: Context) {
    private val prefs = context.getSharedPreferences("floating_voice_bubble_secrets", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun write(name: String, plaintext: String) {
        if (plaintext.isEmpty()) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        prefs.edit().putString(name, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun read(name: String): String {
        val encoded = prefs.getString(name, null) ?: return ""
        return runCatching {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.size > IV_BYTES)
            val iv = packed.copyOfRange(0, IV_BYTES)
            val encrypted = packed.copyOfRange(IV_BYTES, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        }.getOrElse {
            prefs.edit().remove(name).apply()
            ""
        }
    }

    private fun key(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "floating_voice_bubble_settings_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
    }
}
