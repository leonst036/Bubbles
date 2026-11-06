package tech.leonst.bubbles

import android.content.Context
import java.net.URI

object ServerPreferences {
    private const val PREFS_NAME = "bubbles_settings"
    private const val KEY_BACKEND_IP = "backend_ip"
    const val DEFAULT_IP = "192.168.0.111"
    const val DEFAULT_PORT = 8000

    fun getBackendIp(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_BACKEND_IP, DEFAULT_IP) ?: DEFAULT_IP
    }

    fun setBackendIp(context: Context, ip: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_BACKEND_IP, ip.trim()).apply()
    }

    fun getBaseUrl(context: Context): String {
        return normalizeUrl(getBackendIp(context))
    }

    fun normalizeUrl(rawInput: String): String {
        var input = rawInput.trim()
        if (input.isEmpty()) {
            input = DEFAULT_IP
        }

        var url = if (!input.startsWith("http://") && !input.startsWith("https://")) {
            "http://$input"
        } else {
            input
        }

        try {
            val uri = URI(url)
            if (uri.port == -1) {
                val scheme = uri.scheme ?: "http"
                val host = uri.host ?: input
                url = "$scheme://$host:$DEFAULT_PORT"
            }
        } catch (_: Exception) {
            if (!url.substringAfter("://").contains(":")) {
                url = "$url:$DEFAULT_PORT"
            }
        }

        return url.trimEnd('/')
    }
}
