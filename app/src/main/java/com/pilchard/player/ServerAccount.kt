package com.pilchard.player

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

/**
 * The Jellyfin account Pilchard signs in with (once, from its home screen).
 * Jellyfin's TV app hands Pilchard stream links without an access token, so Pilchard
 * needs its own sign-in to load channels, the guide, and to save resume points.
 */
object ServerAccount {

    private const val PREFS = "server"

    /**
     * Jellyfin keeps one sign-in per account per device ID: signing in again from the same ID
     * cancels the earlier sign-in. Older Pilchard versions sent the same ID ("pilchard-player")
     * from every install, so two Shields on one account kept signing each other out.
     * Each install now makes its own ID the first time it runs.
     */
    private const val LEGACY_DEVICE_ID = "pilchard-player"

    @Volatile private var appContext: Context? = null

    /** Called when each screen starts, so requests can read this install's device ID. */
    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /** This install's own ID, made once and kept until the app is uninstalled. */
    private fun installId(context: Context): String {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString("device_id", null)?.let { return it }
        val id = "pilchard-" + UUID.randomUUID().toString()
        p.edit().putString("device_id", id).apply()
        return id
    }

    /**
     * The ID to send with requests: the one the saved sign-in was made with. Sign-ins from
     * before this change were made with the shared ID and keep using it until the next sign-in.
     */
    private fun requestDeviceId(): String {
        val context = appContext ?: return LEGACY_DEVICE_ID
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getString("token", null) == null) return installId(context)
        return p.getString("token_device_id", null) ?: LEGACY_DEVICE_ID
    }

    data class Account(val baseUrl: String, val token: String, val userId: String, val userName: String)

    fun get(context: Context): Account? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val base = p.getString("base", null) ?: return null
        val token = p.getString("token", null) ?: return null
        val user = p.getString("user_id", null) ?: return null
        return Account(base, token, user, p.getString("user_name", "").orEmpty())
    }

    /** The saved access token. (It works on any address of the same server, e.g. LAN or Tailscale.) */
    @Suppress("UNUSED_PARAMETER")
    fun tokenFor(context: Context, baseUrl: String): String? = get(context)?.token

    /** Remembers the server address from the last stream Jellyfin sent, to pre-fill the sign-in. */
    fun rememberServer(context: Context, baseUrl: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("last_seen", baseUrl).apply()
    }

    fun lastSeenServer(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last_seen", null)

    fun signOut(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("token").remove("user_id").remove("user_name").remove("token_device_id").apply()
    }

    /** Signs in with username and password; [done] gets null on success or an error message. */
    fun signIn(context: Context, server: String, user: String, password: String, done: (String?) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        Executors.newSingleThreadExecutor().execute {
            var base = normalize(server)
            val error = try {
                val deviceId = installId(context)
                var result: String? = context.getString(R.string.sign_in_unreachable)
                // Servers often redirect http:// to https:// (or add a path). Follow up to 3 redirects
                // and keep the address that worked.
                for (attempt in 0..3) {
                    val conn = URL("$base/Users/AuthenticateByName").openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = false
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 8000
                    conn.readTimeout = 15000
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("Authorization", header(null, deviceId))
                    conn.outputStream.use {
                        it.write(JSONObject().put("Username", user).put("Pw", password).toString().toByteArray())
                    }
                    val code = conn.responseCode
                    val moved = conn.getHeaderField("Location")
                    if (code in setOf(301, 302, 303, 307, 308) && moved != null && attempt < 3) {
                        conn.disconnect()
                        val target = URL(URL("$base/Users/AuthenticateByName"), moved).toString()
                        val newBase = target.substringBefore("/Users/AuthenticateByName", "")
                        if (newBase.isEmpty() || newBase == base) break
                        base = newBase.trimEnd('/')
                        continue
                    }
                    result = when (code) {
                        200 -> {
                            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                            val u = json.getJSONObject("User")
                            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                                .putString("base", base)
                                .putString("token", json.getString("AccessToken"))
                                .putString("user_id", u.getString("Id"))
                                .putString("user_name", u.optString("Name"))
                                .putString("token_device_id", deviceId)
                                .apply()
                            null
                        }
                        401 -> context.getString(R.string.sign_in_wrong_password)
                        else -> context.getString(R.string.sign_in_server_error, code)
                    }
                    conn.disconnect()
                    break
                }
                result
            } catch (e: Exception) {
                context.getString(R.string.sign_in_unreachable)
            }
            main.post { done(error) }
        }
    }

    fun authHeader(token: String?): String = header(token, requestDeviceId())

    private fun header(token: String?, deviceId: String): String {
        // Shown in Jellyfin's Dashboard → Devices, e.g. "SHIELD Android TV".
        val device = (Build.MODEL ?: "Android TV").replace("\"", "")
        return "MediaBrowser Client=\"Pilchard Player\", Device=\"$device\", DeviceId=\"$deviceId\", " +
            "Version=\"${BuildConfig.VERSION_NAME}\"" + (token?.let { ", Token=\"$it\"" } ?: "")
    }

    fun normalize(server: String): String {
        var s = server.trim().trimEnd('/')
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        return s
    }
}
