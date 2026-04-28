package com.diss.location_based_diary.data.model

/**
 * Represents a pending notification in your Inbox when someone
 * asks to see your location.
 */
data class FriendRequest(
    val requesterId: Int,          // The database ID of the person asking
    val username: String,          // The name of the person asking
    val date: String               // The timestamp of when they sent the request
)