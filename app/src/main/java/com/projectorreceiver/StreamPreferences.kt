package com.projectorreceiver

import android.content.Context
import androidx.core.content.edit

/** Small SharedPreferences wrapper; no database or reactive framework is needed here. */
class StreamPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    var lastUrl: String
        get() = preferences.getString(KEY_LAST_URL, DEFAULT_URL) ?: DEFAULT_URL
        set(value) = preferences.edit { putString(KEY_LAST_URL, value) }

    var autoConnect: Boolean
        get() = preferences.getBoolean(KEY_AUTO_CONNECT, false)
        set(value) = preferences.edit { putBoolean(KEY_AUTO_CONNECT, value) }

    companion object {
        const val DEFAULT_URL = "rtsp://192.168.1.20:8554/live"

        private const val PREFERENCES_NAME = "projector_receiver_preferences"
        private const val KEY_LAST_URL = "last_stream_url"
        private const val KEY_AUTO_CONNECT = "auto_connect"
    }
}
