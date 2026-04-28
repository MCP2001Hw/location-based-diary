package com.diss.location_based_diary.data.api

import org.json.JSONArray

sealed class LocationCheckResult {
    data class Success(val matches: JSONArray) : LocationCheckResult()
    data class ServerError(val code: Int) : LocationCheckResult()
    data class NetworkError(val message: String) : LocationCheckResult()
}