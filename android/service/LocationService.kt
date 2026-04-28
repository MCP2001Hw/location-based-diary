package com.diss.location_based_diary.service

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import com.diss.location_based_diary.service.ActivityReceiver
import com.diss.location_based_diary.util.NotificationHelper
import com.diss.location_based_diary.util.TaskFilter
import com.diss.location_based_diary.data.api.ApiClient
import com.diss.location_based_diary.data.api.LocationCheckResult
import com.diss.location_based_diary.data.prefs.AppPreferences
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * Foreground service that continuously tracks the user's location and fires
 * geofence alarm notifications when they enter a targeted place.
 *
 * Changes from original:
 *  - All network calls go through [com.diss.location_based_diary.data.api.ApiClient] instead of raw HttpURLConnection code.
 *  - All preference reads/writes go through [com.diss.location_based_diary.data.prefs.AppPreferences].
 *  - Time/weekday logic is delegated to [TaskFilter].
 *  - [NotificationHelper] owns notification creation.
 *  - Private helpers are clearly grouped and documented.
 */
class LocationService : Service() {

    // ── Android/Google clients ─────────────────────────────────────────────
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var activityRecognitionClient: ActivityRecognitionClient
    private lateinit var activityPendingIntent: PendingIntent

    // ── State ──────────────────────────────────────────────────────────────
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** IDs of tasks the user is *currently* inside, used to avoid re-firing on every ping. */
    private val currentlyNearbyTasks = mutableSetOf<Int>()

    /** Local copy of tasks synced from the server on start-up. */
    private var localTasks: JSONArray = JSONArray()

    private lateinit var notificationHelper: NotificationHelper

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        activityRecognitionClient = ActivityRecognition.getClient(this)
        notificationHelper = NotificationHelper(this)

        val intent = Intent(this, ActivityReceiver::class.java)
        activityPendingIntent = PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(FOREGROUND_NOTIFICATION_ID, notificationHelper.createForegroundNotification())
        syncTasksFromServer()
        startLocationTracking()
        return START_STICKY
    }

    @RequiresPermission(Manifest.permission.ACTIVITY_RECOGNITION)
    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        activityRecognitionClient.removeActivityUpdates(activityPendingIntent)
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Location tracking ──────────────────────────────────────────────────

    private fun startLocationTracking() {
        try {
            activityRecognitionClient.requestActivityUpdates(ACTIVITY_INTERVAL_MS, activityPendingIntent)

            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS)
                .setMinUpdateIntervalMillis(LOCATION_INTERVAL_MS)
                .setMinUpdateDistanceMeters(MIN_DISTANCE_METERS)
                .build()

            locationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    val currentActivity = AppPreferences.getCurrentActivity(this@LocationService)

                    if (currentActivity == "Standing Still") {
                        Log.w(TAG, "Gatekeeper: user is still — skipping GPS check (bypassed for testing).")
                        // return
                    }

                    for (location in locationResult.locations) {
                        onNewLocation(location.latitude, location.longitude)
                    }
                }
            }

            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing", e)
        }
    }

    private fun onNewLocation(lat: Double, lon: Double) {
        serviceScope.launch {
            ApiClient.updateBreadcrumb(
                userId = AppPreferences.getUserId(this@LocationService),
                lat = lat,
                lon = lon
            )

            if (isAnyTaskTemporallyActive()) {
                checkGeofences(lat, lon)
            }
        }
    }

    // ── Geofence checking ──────────────────────────────────────────────────

    /**
     * Returns true if *any* locally cached task is within its allowed time window.
     * Used as a cheap early-exit so we don't call the server when no task is relevant.
     */
    private fun isAnyTaskTemporallyActive(): Boolean {
        for (i in 0 until localTasks.length()) {
            val task = localTasks.getJSONObject(i)
            if (TaskFilter.isTimeWithinWindow(task.optString("time", "Any time"))) return true
        }
        return false
    }

    private fun checkGeofences(lat: Double, lon: Double) {
        val userId = AppPreferences.getUserId(this)
        if (userId == -1) return

        serviceScope.launch {
            try {
                // checkLocation now returns a sealed LocationCheckResult — unwrap it.
                // If the call failed (ServerError / NetworkError) there is nothing to process.
                val result = ApiClient.checkLocation(
                    userId = userId,
                    lat    = lat,
                    lon    = lon,
                    radius = AppPreferences.getRadarRadius(this@LocationService)
                )
                val matches = (result as? LocationCheckResult.Success)?.matches ?: return@launch

                val currentPingMatches = mutableSetOf<Int>()

                for (i in 0 until matches.length()) {
                    val match  = matches.getJSONObject(i)
                    val taskId = match.getInt("task_id")

                    val savedTask = (0 until localTasks.length())
                        .map { localTasks.getJSONObject(it) }
                        .find { it.getInt("id") == taskId }

                    val weekdays = savedTask
                        ?.optJSONArray("weekdays")
                        ?.let { arr -> List(arr.length()) { j -> arr.getString(j) } }
                        ?: emptyList()

                    val isEligible = savedTask != null
                            && savedTask.optBoolean("isActive")
                            && TaskFilter.isTimeWithinWindow(savedTask.optString("time", "Any time"))
                            && TaskFilter.isTodayInWeekdays(weekdays)

                    if (isEligible) {
                        currentPingMatches.add(taskId)

                        // Only fire the notification the *first* time we enter the geofence
                        if (!currentlyNearbyTasks.contains(taskId)) {
                            currentlyNearbyTasks.add(taskId)
                            notificationHelper.showAlarmNotification(
                                category    = match.getString("location_category"),
                                description = match.getString("description")
                            )
                            ApiClient.logTrigger(userId, lat, lon)
                        }
                    }
                }

                // Remove tasks we've exited from the "currently nearby" set
                currentlyNearbyTasks.retainAll(currentPingMatches)

            } catch (e: Exception) {
                Log.e(TAG, "Geofence check failed: ${e.message}")
            }
        }
    }

    // ── Task sync ──────────────────────────────────────────────────────────

    private fun syncTasksFromServer() {
        val userId = AppPreferences.getUserId(this)
        if (userId == -1) return

        serviceScope.launch {
            try {
                localTasks = ApiClient.getTasks(userId)
            } catch (e: Exception) {
                Log.e(TAG, "Task sync failed: ${e.message}")
            }
        }
    }

    // ── Constants ──────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "LocationService"
        private const val FOREGROUND_NOTIFICATION_ID = 12345
        private const val LOCATION_INTERVAL_MS  = 10_000L
        private const val ACTIVITY_INTERVAL_MS  = 10_000L
        private const val MIN_DISTANCE_METERS   = 20f
    }
}