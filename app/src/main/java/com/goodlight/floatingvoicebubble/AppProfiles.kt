package com.goodlight.floatingvoicebubble

import android.content.Context

enum class ProfileToggle { INHERIT, ON, OFF }
enum class ProfileRegister { INHERIT, PLAIN, POLITE, BUSINESS }
enum class ProfileCorrectionMode { INHERIT, AUTO, BYOK, GEMMA, NONE }
enum class ProfileLineBreakMode { INHERIT, NONE, SMART, SMART_SPACED }
enum class ProfileRecognitionRepairMode { INHERIT, OFF, NORMAL, STRONG }

data class AppCorrectionProfile(
    val packageName: String,
    val enabled: Boolean = true,
    val addCommas: ProfileToggle = ProfileToggle.INHERIT,
    val addPeriods: ProfileToggle = ProfileToggle.INHERIT,
    val removeFillers: ProfileToggle = ProfileToggle.INHERIT,
    val register: ProfileRegister = ProfileRegister.INHERIT,
    val correctionMode: ProfileCorrectionMode = ProfileCorrectionMode.INHERIT,
    val lineBreakMode: ProfileLineBreakMode = ProfileLineBreakMode.INHERIT,
    val recognitionRepairMode: ProfileRecognitionRepairMode = ProfileRecognitionRepairMode.INHERIT,
) {
    fun applyTo(base: AppSettings): AppSettings {
        if (!enabled) return base
        val registerPair = when (register) {
            ProfileRegister.INHERIT -> base.correctionPolite to base.correctionBusinessPolite
            ProfileRegister.PLAIN -> false to false
            ProfileRegister.POLITE -> true to false
            ProfileRegister.BUSINESS -> false to true
        }
        return base.copy(
            correctionAddCommas = addCommas.resolve(base.correctionAddCommas),
            correctionAddPeriods = addPeriods.resolve(base.correctionAddPeriods),
            correctionRemoveFillers = removeFillers.resolve(base.correctionRemoveFillers),
            correctionPolite = registerPair.first,
            correctionBusinessPolite = registerPair.second,
            correctionMode = correctionMode.resolve(base.correctionMode),
            correctionLineBreakMode = lineBreakMode.resolve(base.correctionLineBreakMode),
            recognitionRepairMode = recognitionRepairMode.resolve(base.recognitionRepairMode),
        )
    }
}

private fun ProfileToggle.resolve(global: Boolean): Boolean = when (this) {
    ProfileToggle.INHERIT -> global
    ProfileToggle.ON -> true
    ProfileToggle.OFF -> false
}

private fun ProfileCorrectionMode.resolve(global: CorrectionMode): CorrectionMode = when (this) {
    ProfileCorrectionMode.INHERIT -> global
    ProfileCorrectionMode.AUTO -> CorrectionMode.AUTO
    ProfileCorrectionMode.BYOK -> CorrectionMode.BYOK
    ProfileCorrectionMode.GEMMA -> CorrectionMode.GEMMA
    ProfileCorrectionMode.NONE -> CorrectionMode.NONE
}

private fun ProfileLineBreakMode.resolve(global: LineBreakMode): LineBreakMode = when (this) {
    ProfileLineBreakMode.INHERIT -> global
    ProfileLineBreakMode.NONE -> LineBreakMode.NONE
    ProfileLineBreakMode.SMART -> LineBreakMode.SMART
    ProfileLineBreakMode.SMART_SPACED -> LineBreakMode.SMART_SPACED
}

private fun ProfileRecognitionRepairMode.resolve(global: RecognitionRepairMode): RecognitionRepairMode = when (this) {
    ProfileRecognitionRepairMode.INHERIT -> global
    ProfileRecognitionRepairMode.OFF -> RecognitionRepairMode.OFF
    ProfileRecognitionRepairMode.NORMAL -> RecognitionRepairMode.NORMAL
    ProfileRecognitionRepairMode.STRONG -> RecognitionRepairMode.STRONG
}

/** Compact, deterministic format so profile persistence can be JVM-tested without Android JSON stubs. */
object AppCorrectionProfileCodec {
    fun encode(profile: AppCorrectionProfile): String = listOf(
        VERSION,
        "enabled=${if (profile.enabled) 1 else 0}",
        "commas=${profile.addCommas.name}",
        "periods=${profile.addPeriods.name}",
        "fillers=${profile.removeFillers.name}",
        "register=${profile.register.name}",
        "mode=${profile.correctionMode.name}",
        "breaks=${profile.lineBreakMode.name}",
        "repair=${profile.recognitionRepairMode.name}",
    ).joinToString("|")

    fun decode(packageName: String, raw: String?): AppCorrectionProfile? {
        if (!isValidPackageName(packageName) || raw.isNullOrBlank()) return null
        val fields = raw.split('|')
        if (fields.firstOrNull() != VERSION) return null
        val values = fields.drop(1).mapNotNull { field ->
            val separator = field.indexOf('=')
            if (separator <= 0) null else field.substring(0, separator) to field.substring(separator + 1)
        }.toMap()
        return AppCorrectionProfile(
            packageName = packageName,
            enabled = values["enabled"] != "0",
            addCommas = enumOr(values["commas"], ProfileToggle.INHERIT),
            addPeriods = enumOr(values["periods"], ProfileToggle.INHERIT),
            removeFillers = enumOr(values["fillers"], ProfileToggle.INHERIT),
            register = enumOr(values["register"], ProfileRegister.INHERIT),
            correctionMode = enumOr(values["mode"], ProfileCorrectionMode.INHERIT),
            lineBreakMode = enumOr(values["breaks"], ProfileLineBreakMode.INHERIT),
            recognitionRepairMode = enumOr(values["repair"], ProfileRecognitionRepairMode.INHERIT),
        )
    }

    fun isValidPackageName(value: String): Boolean =
        value.length in 3..255 && PACKAGE_NAME.matches(value)

    private inline fun <reified T : Enum<T>> enumOr(raw: String?, fallback: T): T =
        raw?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    private const val VERSION = "v1"
    private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
}

data class AppProfileStoreHealth(
    val serializedProfiles: Int,
    val decodedProfiles: Int,
    val recentPackages: Int,
) {
    val healthy: Boolean get() = serializedProfiles == decodedProfiles
}

class AppProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun profile(packageName: String): AppCorrectionProfile? =
        AppCorrectionProfileCodec.decode(packageName, prefs.getString(PROFILE_PREFIX + packageName, null))

    fun effectiveSettings(base: AppSettings, packageName: String): AppSettings =
        profile(packageName)?.applyTo(base) ?: base

    fun save(profile: AppCorrectionProfile) {
        require(AppCorrectionProfileCodec.isValidPackageName(profile.packageName)) { "アプリのpackage nameが不正です。" }
        prefs.edit().putString(PROFILE_PREFIX + packageName(profile), AppCorrectionProfileCodec.encode(profile)).apply()
    }

    private fun packageName(profile: AppCorrectionProfile): String = profile.packageName

    fun delete(packageName: String) {
        prefs.edit().remove(PROFILE_PREFIX + packageName).apply()
    }

    fun profiles(): List<AppCorrectionProfile> = prefs.all.keys.asSequence()
        .filter { it.startsWith(PROFILE_PREFIX) }
        .map { it.removePrefix(PROFILE_PREFIX) }
        .mapNotNull(::profile)
        .sortedBy { it.packageName.lowercase() }
        .toList()

    fun health(): AppProfileStoreHealth {
        val snapshot = prefs.all
        val serialized = snapshot.keys.count { it.startsWith(PROFILE_PREFIX) }
        val decoded = snapshot.asSequence()
            .filter { (key, value) -> key.startsWith(PROFILE_PREFIX) && value is String }
            .count { (key, value) ->
                AppCorrectionProfileCodec.decode(key.removePrefix(PROFILE_PREFIX), value as String) != null
            }
        val recents = snapshot.asSequence()
            .count { (key, value) -> key.startsWith(RECENT_PREFIX) && value is Long }
        return AppProfileStoreHealth(serialized, decoded, recents)
    }

    fun recordInputApp(packageName: String, nowMs: Long = System.currentTimeMillis()) {
        if (!AppCorrectionProfileCodec.isValidPackageName(packageName)) return
        prefs.edit().putLong(RECENT_PREFIX + packageName, nowMs).apply()
    }

    fun recentPackages(limit: Int = 40): List<String> = prefs.all.asSequence()
        .filter { (key, value) -> key.startsWith(RECENT_PREFIX) && value is Long }
        .map { (key, value) -> key.removePrefix(RECENT_PREFIX) to (value as Long) }
        .sortedByDescending { it.second }
        .map { it.first }
        .take(limit.coerceAtLeast(0))
        .toList()

    companion object {
        private const val PREFS = "floating_voice_bubble_app_profiles"
        private const val PROFILE_PREFIX = "profile:"
        private const val RECENT_PREFIX = "recent:"
    }
}