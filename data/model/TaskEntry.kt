package com.diss.location_based_diary.data.model

/**
 * Represents a brand-new (or currently being edited) mission.
 * * Why do we need this separate from 'Task'?
 * Because when a user is filling out the "New Mission" pop-up, the mission
 * doesn't have an 'id' or an 'isActive' status yet! The Python server assigns
 * those automatically once we send this entry to the database.
 */
data class TaskEntry(
    val time: String,
    val cycle: String,
    val weekdays: List<String>,
    val locationCategories: List<String>,
    val description: String
)