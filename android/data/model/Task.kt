package com.diss.location_based_diary.data.model

/**
 * Represents a fully saved Geofence Mission that has been downloaded from the database.
 */
data class Task(
    val id: Int,                           // The unique ID assigned by the Postgres database
    val time: String,                      // Target time (e.g., "14:00")
    val cycle: String,                     // How often it repeats (e.g., "Every week")
    val weekdays: List<String>,            // Allowed days (e.g., ["Mon", "Wed", "Fri"])
    val locationCategories: List<String>,  // Targeted places (e.g., ["supermarket", "cafe"])
    val description: String,               // The user's custom reminder text

    // This is 'var' so we can flip the Active/Inactive switch on the Dashboard
    // and instantly pause the background alarm logic!
    var isActive: Boolean
)