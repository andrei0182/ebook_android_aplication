package com.ebook.cititor.data

import android.content.Context

enum class ReaderTheme(val bg: String, val fg: String, val link: String, val highlight: String, val word: String) {
    LIGHT("#FFFFFF", "#1C1B1F", "#1565C0", "rgba(255,213,79,0.28)", "rgba(255,179,0,0.75)"),
    SEPIA("#F4ECD8", "#5B4636", "#8B5A2B", "rgba(214,170,90,0.30)", "rgba(196,128,40,0.65)"),
    DARK("#121212", "#E0E0E0", "#90CAF9", "rgba(255,193,7,0.16)", "rgba(255,193,7,0.55)"),
}

class Prefs(ctx: Context) {
    companion object {
        const val ENGINE_SYSTEM = "system"
        const val ENGINE_ELEVEN = "eleven"
        const val MODEL_QUALITY = "eleven_multilingual_v2"
        const val MODEL_FAST = "eleven_flash_v2_5"
    }

    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var textZoom: Int
        get() = sp.getInt("textZoom", 110)
        set(v) = sp.edit().putInt("textZoom", v).apply()

    var theme: ReaderTheme
        get() = runCatching { ReaderTheme.valueOf(sp.getString("theme", "LIGHT")!!) }.getOrDefault(ReaderTheme.LIGHT)
        set(v) = sp.edit().putString("theme", v.name).apply()

    var serif: Boolean
        get() = sp.getBoolean("serif", true)
        set(v) = sp.edit().putBoolean("serif", v).apply()

    var speechRate: Float
        get() = sp.getFloat("rate", 1.0f)
        set(v) = sp.edit().putFloat("rate", v).apply()

    var speechPitch: Float
        get() = sp.getFloat("pitch", 1.0f)
        set(v) = sp.edit().putFloat("pitch", v).apply()

    /** "system" = Android text-to-speech, "eleven" = ElevenLabs AI voices. */
    var engine: String
        get() = sp.getString("engine", ENGINE_SYSTEM) ?: ENGINE_SYSTEM
        set(v) = sp.edit().putString("engine", v).apply()

    /** Name of the chosen Android TTS voice; null = automatic (by book language). */
    var systemVoice: String?
        get() = sp.getString("systemVoice", null)
        set(v) = sp.edit().putString("systemVoice", v).apply()

    var elevenKey: String
        get() = sp.getString("elevenKey", "") ?: ""
        set(v) = sp.edit().putString("elevenKey", v.trim()).apply()

    var elevenVoiceId: String
        get() = sp.getString("elevenVoiceId", "") ?: ""
        set(v) = sp.edit().putString("elevenVoiceId", v).apply()

    var elevenVoiceName: String
        get() = sp.getString("elevenVoiceName", "") ?: ""
        set(v) = sp.edit().putString("elevenVoiceName", v).apply()

    var elevenModel: String
        get() = sp.getString("elevenModel", MODEL_QUALITY) ?: MODEL_QUALITY
        set(v) = sp.edit().putString("elevenModel", v).apply()

    var askedNotifications: Boolean
        get() = sp.getBoolean("askedNotif", false)
        set(v) = sp.edit().putBoolean("askedNotif", v).apply()
}
