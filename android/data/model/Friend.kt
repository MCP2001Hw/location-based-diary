package com.diss.location_based_diary.data.model

/**
 * Represents a user you are friends with on the Social Radar.
 */
data class Friend(
    val id: Int,                   // The friend's unique database ID
    val username: String,          // The friend's display name

    // These use '?' because they can be null! If your friend turns off their
    // location sharing, the server won't send a distance or a time.
    val distanceMeters: Double?,
    val lastUpdated: String?,

    // Notice this is 'var' instead of 'val'.
    // 'var' means the variable can be changed. We need this so you can instantly
    // flip the ON/OFF location sharing toggle on the UI!
    var isSharing: Boolean
)