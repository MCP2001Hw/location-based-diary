package com.diss.location_based_diary.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.diss.location_based_diary.data.prefs.AppPreferences
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity

/**
 * Receives periodic activity-recognition broadcasts from the Google Activity
 * Recognition API and saves the most probable activity to shared preferences
 * so that [LocationService] can gate its GPS checks.
 *
 * Changes from original:
 *  - Writes via [com.diss.location_based_diary.data.prefs.AppPreferences] instead of a raw SharedPreferences call.
 *  - Minor readability clean-up (early return, named constant for threshold).
 */
class ActivityReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityRecognitionResult.hasResult(intent)) return

        val result     = ActivityRecognitionResult.extractResult(intent) ?: return
        val activity   = result.mostProbableActivity
        val confidence = activity.confidence

        if (confidence <= CONFIDENCE_THRESHOLD) {
            Log.d(TAG, "Ignored low-confidence result: $confidence%")
            return
        }

        val activityName = activityLabel(activity.type)
        Log.d(TAG, "State: $activityName ($confidence%)")
        AppPreferences.saveCurrentActivity(context, activityName)
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun activityLabel(type: Int): String = when (type) {
        DetectedActivity.IN_VEHICLE -> "Driving"
        DetectedActivity.ON_BICYCLE -> "Cycling"
        DetectedActivity.ON_FOOT    -> "On Foot"
        DetectedActivity.RUNNING    -> "Running"
        DetectedActivity.WALKING    -> "Walking"
        DetectedActivity.STILL      -> "Standing Still"
        else                        -> "Unknown"
    }

    companion object {
        private const val TAG = "LBS_ACTIVITY"
        private const val CONFIDENCE_THRESHOLD = 70
    }
}