package com.diss.location_based_diary.data.prefs

import android.content.Context
import androidx.core.content.edit

/**
 * A single place for every SharedPreferences read/write in the app.
 *
 * Before this file existed, `getSharedPreferences("LBS_PREFS", ...)` was
 * copy-pasted into MainActivity, LocationService, ActivityReceiver, and
 * LoginScreen. Now every piece of code goes through one place.
 */
object AppPreferences {

    private const val PREFS_FILE = "LBS_PREFS"

    // ── Keys ──────────────────────────────────────────────────────────────
    private const val KEY_USER_ID         = "USER_ID"
    private const val KEY_USERNAME        = "USERNAME"
    private const val KEY_RADAR_RADIUS    = "RADAR_RADIUS"
    private const val KEY_CURRENT_ACTIVITY = "CURRENT_ACTIVITY"

    // ── User identity ──────────────────────────────────────────────────────

    fun getUserId(context: Context): Int =
        prefs(context).getInt(KEY_USER_ID, -1)

    fun saveUserId(context: Context, id: Int) =
        prefs(context).edit { putInt(KEY_USER_ID, id) }

    fun getUsername(context: Context): String =
        prefs(context).getString(KEY_USERNAME, "") ?: ""

    fun saveUsername(context: Context, username: String) =
        prefs(context).edit { putString(KEY_USERNAME, username) }

    // ── Radar radius ───────────────────────────────────────────────────────

    fun getRadarRadius(context: Context): Int =
        prefs(context).getInt(KEY_RADAR_RADIUS, 200)

    fun saveRadarRadius(context: Context, radius: Int) =
        prefs(context).edit { putInt(KEY_RADAR_RADIUS, radius) }

    // ── Activity recognition ───────────────────────────────────────────────

    fun getCurrentActivity(context: Context): String =
        prefs(context).getString(KEY_CURRENT_ACTIVITY, "Unknown") ?: "Unknown"

    fun saveCurrentActivity(context: Context, activityName: String) =
        prefs(context).edit { putString(KEY_CURRENT_ACTIVITY, activityName) }

    // ── Internal helper ───────────────────────────────────────────────────

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
}