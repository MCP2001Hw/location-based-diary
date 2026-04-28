package com.diss.location_based_diary.data.api

import android.util.Log
import com.diss.location_based_diary.data.model.TaskEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Low-level HTTP helpers for the LBS backend.
 *
 * Every function does exactly ONE network request.
 * No Android-specific types (Context, lifecycleScope, Toast) appear here.
 * All functions must be called from a Dispatchers.IO coroutine.
 */
object ApiClient {

    private const val BASE_URL = "https://lbs-api-server.onrender.com"
    private const val TAG = "ApiClient"

    // ── Auth ───────────────────────────────────────────────────────────────

    /**
     * POST /login or /register.
     * Returns user_id on success, throws with the server error message on failure.
     */
    fun authenticate(username: String, password: String, endpoint: String): Int {
        val json = JSONObject().apply {
            put("username", username)
            put("password", password)
        }
        val conn = postJson("$BASE_URL/$endpoint", json)
        return if (conn.responseCode == HttpURLConnection.HTTP_OK ||
                   conn.responseCode == HttpURLConnection.HTTP_CREATED) {
            JSONObject(conn.inputStream.bufferedReader().readText()).getInt("user_id")
        } else {
            val errorBody = conn.errorStream.bufferedReader().readText()
            error(runCatching { JSONObject(errorBody).getString("error") }.getOrDefault(errorBody))
        }
    }

    // ── Consent ────────────────────────────────────────────────────────────

    fun checkConsent(userId: Int): Boolean {
        val conn = openGet("$BASE_URL/check_consent/$userId")
        return JSONObject(conn.inputStream.bufferedReader().readText()).getBoolean("took_consent")
    }

    fun acceptConsent(userId: Int): Boolean =
        openUrl("$BASE_URL/accept_consent/$userId", "PUT").responseCode == HttpURLConnection.HTTP_OK

    // ── Tasks ──────────────────────────────────────────────────────────────

    fun getTasks(userId: Int): JSONArray {
        val conn = openGet("$BASE_URL/get_tasks/$userId")
        return if (conn.responseCode == HttpURLConnection.HTTP_OK)
            JSONArray(conn.inputStream.bufferedReader().readText())
        else JSONArray()
    }

    fun addTask(userId: Int, entry: TaskEntry): Boolean {
        val json = JSONObject().apply {
            put("user_id", userId)
            put("time", entry.time)
            put("cycle", entry.cycle)
            put("weekdays", JSONArray(entry.weekdays))
            put("locationCategories", JSONArray(entry.locationCategories))
            put("description", entry.description)
        }
        val code = postJson("$BASE_URL/add_task", json).responseCode
        return code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_CREATED
    }

    fun editTask(taskId: Int, entry: TaskEntry): Boolean {
        val json = JSONObject().apply {
            put("time", entry.time)
            put("cycle", entry.cycle)
            put("weekdays", JSONArray(entry.weekdays))
            put("locationCategories", JSONArray(entry.locationCategories))
            put("description", entry.description)
        }
        return putJson("$BASE_URL/edit_task/$taskId", json).responseCode == HttpURLConnection.HTTP_OK
    }

    fun deleteTask(taskId: Int): Boolean =
        openUrl("$BASE_URL/delete_task/$taskId", "DELETE").responseCode == HttpURLConnection.HTTP_OK

    fun toggleTaskActive(taskId: Int, isActive: Boolean): Boolean {
        val json = JSONObject().apply { put("isActive", isActive) }
        return putJson("$BASE_URL/toggle_active/$taskId", json).responseCode == HttpURLConnection.HTTP_OK
    }

    // ── Amenities ──────────────────────────────────────────────────────────

    fun getAmenities(): List<String> {
        val conn = openGet("$BASE_URL/get_amenities")
        if (conn.responseCode != HttpURLConnection.HTTP_OK) return emptyList()
        val arr = JSONArray(conn.inputStream.bufferedReader().readText())
        return List(arr.length()) { arr.getString(it) }
    }

    // ── Location ───────────────────────────────────────────────────────────

    fun updateBreadcrumb(userId: Int, lat: Double, lon: Double) {
        try {
            val json = JSONObject().apply {
                put("user_id", userId); put("lat", lat); put("lon", lon)
            }
            postJson("$BASE_URL/update_location", json).responseCode
        } catch (e: Exception) {
            Log.e(TAG, "Breadcrumb failed: ${e.message}")
        }
    }

    /**
     * Checks whether the given coordinates are inside any active geofence missions.
     *
     * FIX (Bug 1): Returns a [LocationCheckResult] sealed class so the caller
     * can properly distinguish between:
     *   - A real geofence match  → [LocationCheckResult.Success] with non-empty matches
     *   - A successful call with no match → [LocationCheckResult.Success] with empty matches
     *   - A server/network error  → [LocationCheckResult.ServerError] / [LocationCheckResult.NetworkError]
     *
     * Previously the caller used `matches.length() >= 0` which is always true,
     * so "Location updated" was shown even when zero geofences matched.
     */
    fun checkLocation(userId: Int, lat: Double, lon: Double, radius: Int): LocationCheckResult {
        return try {
            val json = JSONObject().apply {
                put("lat", lat); put("lon", lon); put("user_id", userId); put("radius", radius)
            }
            val conn = postJson("$BASE_URL/check_location", json)
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val matches = JSONObject(conn.inputStream.bufferedReader().readText())
                    .getJSONArray("matches")
                LocationCheckResult.Success(matches)
            } else {
                LocationCheckResult.ServerError(conn.responseCode)
            }
        } catch (e: Exception) {
            LocationCheckResult.NetworkError(e.message ?: "Unknown error")
        }
    }

    fun logTrigger(userId: Int, lat: Double, lon: Double): Boolean {
        val json = JSONObject().apply { put("user_id", userId); put("lat", lat); put("lon", lon) }
        return postJson("$BASE_URL/log_trigger", json).responseCode == HttpURLConnection.HTTP_OK
    }

    // ── Account ────────────────────────────────────────────────────────────

    fun deleteAccount(userId: Int): Boolean =
        openUrl("$BASE_URL/delete_account/$userId", "DELETE").responseCode == HttpURLConnection.HTTP_OK

    fun updateUser(userId: Int, newUsername: String, newPassword: String): Boolean {
        val json = JSONObject().apply {
            if (newUsername.isNotBlank()) put("username", newUsername)
            if (newPassword.isNotBlank()) put("password", newPassword)
        }
        return putJson("$BASE_URL/update_user/$userId", json).responseCode == HttpURLConnection.HTTP_OK
    }

    // ── Friends ────────────────────────────────────────────────────────────

    fun getFriendsLocations(userId: Int): JSONArray {
        val conn = openGet("$BASE_URL/get_friends_locations/$userId")
        return if (conn.responseCode == HttpURLConnection.HTTP_OK)
            JSONArray(conn.inputStream.bufferedReader().readText())
        else JSONArray()
    }

    fun sendFriendRequest(userId: Int, friendUsername: String): String {
        val json = JSONObject().apply {
            put("user_id", userId); put("friend_username", friendUsername); put("share_location", true)
        }
        val conn   = postJson("$BASE_URL/send_friend_request", json)
        val stream = if (conn.responseCode == HttpURLConnection.HTTP_OK) conn.inputStream else conn.errorStream
        return runCatching { JSONObject(stream.bufferedReader().readText()).getString("message") }
            .getOrDefault("Network Error")
    }

    fun getPendingRequests(userId: Int): JSONArray {
        val conn = openGet("$BASE_URL/get_pending_requests/$userId")
        return if (conn.responseCode == HttpURLConnection.HTTP_OK)
            JSONArray(conn.inputStream.bufferedReader().readText())
        else JSONArray()
    }

    fun respondToFriendRequest(userId: Int, requesterId: Int, status: String): Boolean {
        val json = JSONObject().apply {
            put("user_id", userId); put("requester_id", requesterId); put("status", status)
        }
        return putJson("$BASE_URL/respond_friend_request", json).responseCode == HttpURLConnection.HTTP_OK
    }

    fun toggleFriendShare(userId: Int, friendId: Int, isSharing: Boolean): Boolean {
        val json = JSONObject().apply {
            put("user_id", userId); put("friend_id", friendId); put("share_location", isSharing)
        }
        return putJson("$BASE_URL/toggle_friend_share", json).responseCode == HttpURLConnection.HTTP_OK
    }

    // ── Private HTTP helpers ───────────────────────────────────────────────

    private fun openGet(url: String): HttpURLConnection =
        URL(url).openConnection() as HttpURLConnection

    private fun openUrl(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).also { it.requestMethod = method }

    private fun postJson(url: String, body: JSONObject): HttpURLConnection =
        writeJson(openUrl(url, "POST"), body)

    private fun putJson(url: String, body: JSONObject): HttpURLConnection =
        writeJson(openUrl(url, "PUT"), body)

    private fun writeJson(conn: HttpURLConnection, body: JSONObject): HttpURLConnection {
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
        return conn
    }
}
