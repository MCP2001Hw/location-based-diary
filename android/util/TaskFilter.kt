package com.diss.location_based_diary.util

import java.util.Calendar
import kotlin.math.abs

object TaskFilter {

    fun isTimeWithinWindow(taskTimeStr: String): Boolean {
        if (taskTimeStr == "Any time") return true
        return try {
            val parts = taskTimeStr.split(":")
            val targetMinutes = parts[0].toInt() * 60 + parts[1].toInt()
            val calendar = Calendar.getInstance()
            val currentMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)

            var diff = abs(targetMinutes - currentMinutes)
            if (diff > 720) diff = 1440 - diff
            diff <= 30
        } catch (e: Exception) { false }
    }

    fun isTodayInWeekdays(weekdays: List<String>): Boolean {
        if (weekdays.isEmpty()) return true
        val days = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val today = days[Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1]
        return weekdays.contains(today)
    }
}