package com.example.headphonehub

import android.content.Context

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var voiceAlertEnabled: Boolean
        get() = prefs.getBoolean("voice_alert", true)
        set(value) = prefs.edit().putBoolean("voice_alert", value).apply()

    var listenerEnabled: Boolean
        get() = prefs.getBoolean("listener_enabled", true)
        set(value) = prefs.edit().putBoolean("listener_enabled", value).apply()

    fun actionFor(type: ClickType): HeadphoneAction {
        val name = prefs.getString("action_${type.name}", null)
        if (name != null) {
            runCatching { return HeadphoneAction.valueOf(name) }
        }
        return when (type) {
            ClickType.SINGLE -> HeadphoneAction.PLAY_PAUSE
            ClickType.DOUBLE -> HeadphoneAction.NEXT
            ClickType.TRIPLE -> HeadphoneAction.PREVIOUS
        }
    }

    fun setAction(type: ClickType, action: HeadphoneAction) {
        prefs.edit().putString("action_${type.name}", action.name).apply()
    }
}
