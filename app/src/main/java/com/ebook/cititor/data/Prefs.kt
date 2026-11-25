package com.ebook.cititor.data

import android.content.Context

enum class ReaderTheme(val bg: String, val fg: String, val link: String, val highlight: String) {
    LIGHT("#FFFFFF", "#1C1B1F", "#1565C0", "rgba(255,213,79,0.55)"),
    SEPIA("#F4ECD8", "#5B4636", "#8B5A2B", "rgba(214,170,90,0.55)"),
    DARK("#121212", "#E0E0E0", "#90CAF9", "rgba(255,193,7,0.35)"),
}

class Prefs(ctx: Context) {
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

    var askedNotifications: Boolean
        get() = sp.getBoolean("askedNotif", false)
        set(v) = sp.edit().putBoolean("askedNotif", v).apply()
}
