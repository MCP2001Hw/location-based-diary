package com.diss.location_based_diary

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.diss.location_based_diary.data.api.ApiClient
import com.diss.location_based_diary.data.api.LocationCheckResult
import com.diss.location_based_diary.data.model.Friend
import com.diss.location_based_diary.data.model.FriendRequest
import com.diss.location_based_diary.data.model.Task
import com.diss.location_based_diary.data.model.TaskEntry
import com.diss.location_based_diary.data.prefs.AppPreferences
import com.diss.location_based_diary.service.LocationService
import com.diss.location_based_diary.ui.auth.LoginScreen
import com.diss.location_based_diary.ui.dashboard.ConsentDialog
import com.diss.location_based_diary.ui.dashboard.DeleteAccountDialog
import com.diss.location_based_diary.ui.dashboard.FriendsDialog
import com.diss.location_based_diary.ui.dashboard.MapDialog
import com.diss.location_based_diary.ui.dashboard.QuestionnaireDialog
import com.diss.location_based_diary.ui.dashboard.SettingsDialog
import com.diss.location_based_diary.ui.dashboard.TaskList
import com.diss.location_based_diary.ui.mission.NewMissionDialog
import com.diss.location_based_diary.ui.theme.LBSTheme
import com.google.android.gms.location.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Entry point of the app.
 *
 * Responsibilities:
 *  1. Request runtime permissions and start [com.diss.location_based_diary.service.LocationService].
 *  2. Route between [com.diss.location_based_diary.ui.auth.LoginScreen] and [DashboardScreen].
 *  3. Own the top-level Compose state (tasks, amenities, loading flag).
 *  4. Wire all network calls through [com.diss.location_based_diary.data.api.ApiClient] and prefs through [AppPreferences].
 *
 * Bug fixes applied here:
 *  Bug 1 — forceUpdateLocation now pattern-matches [com.diss.location_based_diary.data.api.LocationCheckResult] so the
 *           message shown to the user correctly reflects whether a geofence matched.
 *  Bug 5 — Consent is fetched with a stable key so it does not re-fire on every
 *           recomposition, only when the logged-in user actually changes.
 */
class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var tasks: SnapshotStateList<Task>
    private lateinit var amenities: SnapshotStateList<String>
    private var isLoadingTasks by mutableStateOf(true)

    // ── Permission launcher ────────────────────────────────────────────────

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        Log.d(TAG, "Permissions result: $permissions")
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) startLocationService()
        else Log.e(TAG, "Location permission denied.")
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        tasks     = mutableStateListOf()
        amenities = mutableStateListOf()

        requestRequiredPermissions()

        setContent {
            LBSTheme {
                var currentUserId by remember { mutableIntStateOf(AppPreferences.getUserId(this)) }

                LaunchedEffect(currentUserId) {
                    if (currentUserId != -1) {
                        loadAmenities()
                        loadTasks(currentUserId)
                    }
                }

                if (currentUserId == -1) {
                    LoginScreen(onLoginSuccess = { newId ->
                        AppPreferences.saveUserId(this, newId)
                        currentUserId = newId
                    })
                } else {
                    DashboardScreen(
                        currentUserId = currentUserId,
                        onLogout = { AppPreferences.saveUserId(this, -1); currentUserId = -1 }
                    )
                }
            }
        }
    }

    // ── Dashboard screen ───────────────────────────────────────────────────

    @Composable
    private fun DashboardScreen(currentUserId: Int, onLogout: () -> Unit) {
        // ── Dialog visibility flags ────────────────────────────────────────
        var showDialog              by remember { mutableStateOf(false) }
        var showAccountMenu         by remember { mutableStateOf(false) }
        var showDeleteConfirm       by remember { mutableStateOf(false) }
        var showSettingsDialog      by remember { mutableStateOf(false) }
        var showFriendsDialog       by remember { mutableStateOf(false) }
        var showMapDialog           by remember { mutableStateOf(false) }
        var showQuestionnaireDialog by remember { mutableStateOf(false) }

        // ── Consent ────────────────────────────────────────────────────────
        // FIX (Bug 5): Use a nullable Boolean so the three states are explicit:
        //   null  = still loading (show nothing, avoids the flash)
        //   true  = server confirmed consent was given
        //   false = server says user hasn't consented yet (show dialog)
        // Previously this was modelled as two Booleans (hasConsent=true, isLoadingConsent=true)
        // which defaulted to "consent given" to avoid a flash, but left a window where
        // the consent dialog could briefly appear on fast networks even when consent was
        // already on record.  A single nullable value removes the race entirely.
        var consentState by remember(currentUserId) { mutableStateOf<Boolean?>(null) }

        // ── Task / map state ───────────────────────────────────────────────
        var taskToEdit  by remember { mutableStateOf<Task?>(null) }
        var currentLat  by remember { mutableDoubleStateOf(0.0) }
        var currentLon  by remember { mutableDoubleStateOf(0.0) }

        // ── Social state ───────────────────────────────────────────────────
        var friendsList     by remember { mutableStateOf<List<Friend>>(emptyList()) }
        var pendingRequests by remember { mutableStateOf<List<FriendRequest>>(emptyList()) }

        val context = LocalContext.current

        // FIX (Bug 5): key is currentUserId — fires once per login, never on recomposition.
        LaunchedEffect(currentUserId) {
            lifecycleScope.launch(Dispatchers.IO) {
                val result = runCatching { ApiClient.checkConsent(currentUserId) }
                withContext(Dispatchers.Main) {
                    // On network failure we default to true so the user isn't blocked
                    consentState = result.getOrDefault(true)
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxSize().systemBarsPadding(),
            color    = MaterialTheme.colorScheme.background
        ) {
            // Show consent dialog only once we have a definitive false from the server
            if (consentState == false) {
                ConsentDialog(
                    onAccepted = {
                        lifecycleScope.launch(Dispatchers.IO) {
                            if (ApiClient.acceptConsent(currentUserId)) {
                                withContext(Dispatchers.Main) { consentState = true }
                            }
                        }
                    }
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {

                // ── Main task list ─────────────────────────────────────────
                TaskList(
                    tasks = tasks,
                    isLoading = isLoadingTasks,
                    onShowDialog = { showDialog = true },
                    onDeleteTask = { deleteTask(it, currentUserId) },
                    onEditTask = { taskToEdit = it; showDialog = true },
                    onToggleActive = { task, isActive -> toggleTaskActive(task, isActive) }
                )

                // ── Account FAB + dropdown ─────────────────────────────────
                Box(modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 16.dp)) {
                    FloatingActionButton(
                        onClick        = { showAccountMenu = true },
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ) { Icon(Icons.Default.Person, contentDescription = "Account") }

                    DropdownMenu(expanded = showAccountMenu, onDismissRequest = { showAccountMenu = false }) {
                        DropdownMenuItem(text = { Text("Map") }, onClick = {
                            showAccountMenu = false
                            fetchLastLocationThenShow(context) { lat, lon ->
                                currentLat = lat; currentLon = lon; showMapDialog = true
                            }
                        })
                        DropdownMenuItem(text = { Text("Friends") }, onClick = {
                            showAccountMenu = false
                            loadFriends(currentUserId) { friendsList = it }
                            loadPendingRequests(currentUserId) { pendingRequests = it }
                            showFriendsDialog = true
                        })
                        DropdownMenuItem(text = { Text("Settings") },      onClick = { showAccountMenu = false; showSettingsDialog = true })
                        DropdownMenuItem(text = { Text("Questionnaire") }, onClick = { showAccountMenu = false; showQuestionnaireDialog = true })
                        DropdownMenuItem(text = { Text("Log Out") },       onClick = { showAccountMenu = false; onLogout() })
                        DropdownMenuItem(text = { Text("Delete Account") },onClick = { showAccountMenu = false; showDeleteConfirm = true })
                    }
                }

                // ── Delete-account confirmation ────────────────────────────
                if (showDeleteConfirm) {
                    DeleteAccountDialog(
                        onConfirm = {
                            showDeleteConfirm =
                                false; deleteAccount(currentUserId) { onLogout(); tasks.clear() }
                        },
                        onDismiss = { showDeleteConfirm = false }
                    )
                }
            }

            // ── New / edit mission dialog ──────────────────────────────────
            if (showDialog) {
                NewMissionDialog(
                    onDismiss = { showDialog = false; taskToEdit = null },
                    onAddMission = { entry ->
                        if (taskToEdit == null) addTask(entry, currentUserId)
                        else editTask(taskToEdit!!.id, entry, currentUserId)
                        showDialog = false; taskToEdit = null
                    },
                    taskToEdit = taskToEdit,
                    amenities = amenities
                )
            }

            // ── Friends dialog ─────────────────────────────────────────────
            if (showFriendsDialog) {
                FriendsDialog(
                    currentUserId = currentUserId,
                    friendsList = friendsList,
                    pendingRequests = pendingRequests,
                    onSendRequest = { username -> sendFriendRequest(currentUserId, username) {} },
                    onToggleShare = { friendId, sharing ->
                        toggleFriendShare(
                            currentUserId,
                            friendId,
                            sharing
                        )
                    },
                    onApproveRequest = { req ->
                        respondToRequest(
                            currentUserId,
                            req.requesterId,
                            "approved"
                        ) { loadPendingRequests(currentUserId) { pendingRequests = it } }
                    },
                    onDenyRequest = { req ->
                        respondToRequest(
                            currentUserId,
                            req.requesterId,
                            "denied"
                        ) { loadPendingRequests(currentUserId) { pendingRequests = it } }
                    },
                    onDismiss = { showFriendsDialog = false }
                )
            }

            // ── Settings dialog ────────────────────────────────────────────
            if (showSettingsDialog) {
                SettingsDialog(
                    savedUsername = AppPreferences.getUsername(this),
                    savedRadius = AppPreferences.getRadarRadius(this),
                    onSave = { newUser, newPass, newRadius ->
                        AppPreferences.saveRadarRadius(this, newRadius)
                        if (newUser.isNotBlank() || newPass.isNotBlank()) {
                            updateUserSettings(
                                currentUserId,
                                newUser,
                                newPass
                            ) { showSettingsDialog = false }
                        } else {
                            showSettingsDialog = false
                        }
                    },
                    onForceUpdateLocation = { onResult ->
                        // FIX (Bug 1): We now pass a callback that pattern-matches the
                        // LocationCheckResult to show a message that actually reflects
                        // what happened (matched / no match / error) instead of always
                        // saying "Location updated".
                        forceUpdateLocation(
                            userId = currentUserId,
                            radius = AppPreferences.getRadarRadius(this),
                            context = context,
                            onComplete = onResult
                        )
                    },
                    onDismiss = { showSettingsDialog = false }
                )
            }

            // ── Questionnaire dialog ───────────────────────────────────────
            if (showQuestionnaireDialog) {
                QuestionnaireDialog(onDismiss = { showQuestionnaireDialog = false })
            }

            // ── Full-screen map dialog ─────────────────────────────────────
            if (showMapDialog) {
                MapDialog(
                    currentLat = currentLat,
                    currentLon = currentLon,
                    currentUserId = currentUserId,
                    onDismiss = { showMapDialog = false }
                )
            }
        }
    }

    // ── Task API helpers ───────────────────────────────────────────────────

    private fun loadTasks(userId: Int) {
        isLoadingTasks = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val jsonArray = ApiClient.getTasks(userId)
                val newTasks  = mutableListOf<Task>()
                for (i in 0 until jsonArray.length()) {
                    val obj      = jsonArray.getJSONObject(i)
                    val weekdays = obj.getJSONArray("weekdays")
                    val locs     = obj.getJSONArray("locationCategories")
                    newTasks.add(
                        Task(
                            id = obj.getInt("id"),
                            time = obj.getString("time"),
                            cycle = obj.getString("cycle"),
                            weekdays = List(weekdays.length()) { weekdays.getString(it) },
                            locationCategories = List(locs.length()) { locs.getString(it) },
                            description = obj.getString("description"),
                            isActive = obj.getBoolean("isActive")
                        )
                    )
                }
                withContext(Dispatchers.Main) { tasks.clear(); tasks.addAll(newTasks); isLoadingTasks = false }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading tasks: ${e.message}")
                withContext(Dispatchers.Main) { isLoadingTasks = false }
            }
        }
    }

    private fun addTask(entry: TaskEntry, userId: Int) {
        lifecycleScope.launch(Dispatchers.IO) { if (ApiClient.addTask(userId, entry)) loadTasks(userId) }
    }

    private fun editTask(taskId: Int, entry: TaskEntry, userId: Int) {
        lifecycleScope.launch(Dispatchers.IO) { if (ApiClient.editTask(taskId, entry)) loadTasks(userId) }
    }

    private fun deleteTask(task: Task, userId: Int) {
        lifecycleScope.launch(Dispatchers.IO) { if (ApiClient.deleteTask(task.id)) loadTasks(userId) }
    }

    private fun toggleTaskActive(task: Task, isActive: Boolean) {
        // Retry logic lives in TaskComponents.kt (TaskCard) — this is just the
        // fire-and-forget fallback called after a confirmed server success.
        lifecycleScope.launch(Dispatchers.IO) { ApiClient.toggleTaskActive(task.id, isActive) }
    }

    private fun loadAmenities() {
        lifecycleScope.launch(Dispatchers.IO) {
            val list = ApiClient.getAmenities()
            withContext(Dispatchers.Main) { amenities.clear(); amenities.addAll(list) }
        }
    }

    // ── Friends API helpers ────────────────────────────────────────────────

    private fun loadFriends(userId: Int, onResult: (List<Friend>) -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            val arr  = ApiClient.getFriendsLocations(userId)
            val list = mutableListOf<Friend>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    Friend(
                        id = obj.getInt("friend_id"),
                        username = obj.getString("username"),
                        distanceMeters = if (obj.isNull("distance_meters")) null else obj.getDouble(
                            "distance_meters"
                        ),
                        lastUpdated = if (obj.isNull("last_updated")) null else obj.getString("last_updated"),
                        isSharing = obj.getBoolean("share_location")
                    )
                )
            }
            withContext(Dispatchers.Main) { onResult(list) }
        }
    }

    private fun toggleFriendShare(userId: Int, friendId: Int, isSharing: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) { ApiClient.toggleFriendShare(userId, friendId, isSharing) }
    }

    private fun sendFriendRequest(userId: Int, friendUsername: String, onComplete: (String) -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            val msg = ApiClient.sendFriendRequest(userId, friendUsername)
            withContext(Dispatchers.Main) { onComplete(msg) }
        }
    }

    private fun loadPendingRequests(userId: Int, onResult: (List<FriendRequest>) -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            val arr  = ApiClient.getPendingRequests(userId)
            val list = mutableListOf<FriendRequest>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    FriendRequest(
                        obj.getInt("requester_id"),
                        obj.getString("username"),
                        obj.getString("date")
                    )
                )
            }
            withContext(Dispatchers.Main) { onResult(list) }
        }
    }

    private fun respondToRequest(userId: Int, requesterId: Int, status: String, onComplete: () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            if (ApiClient.respondToFriendRequest(userId, requesterId, status)) {
                withContext(Dispatchers.Main) { onComplete() }
            }
        }
    }

    // ── Account API helpers ────────────────────────────────────────────────

    private fun deleteAccount(userId: Int, onSuccess: () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            if (ApiClient.deleteAccount(userId)) withContext(Dispatchers.Main) { onSuccess() }
        }
    }

    private fun updateUserSettings(userId: Int, newUsername: String, newPassword: String, onSuccess: () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            if (ApiClient.updateUser(userId, newUsername, newPassword)) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Settings saved!", Toast.LENGTH_SHORT).show()
                    onSuccess()
                }
            }
        }
    }

    // ── Location / hardware helpers ────────────────────────────────────────

    private fun requestRequiredPermissions() {
        val required = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACTIVITY_RECOGNITION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) required.add(Manifest.permission.POST_NOTIFICATIONS)

        if (required.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED })
            startLocationService()
        else
            requestPermissionLauncher.launch(required.toTypedArray())
    }

    private fun startLocationService() {
        ContextCompat.startForegroundService(this, android.content.Intent(this, LocationService::class.java))
    }

    private fun fetchLastLocationThenShow(context: Context, onReady: (Double, Double) -> Unit) {
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                onReady(location?.latitude ?: 0.0, location?.longitude ?: 0.0)
            }
        } catch (e: SecurityException) {
            Toast.makeText(context, "Location permission denied", Toast.LENGTH_SHORT).show()
            onReady(0.0, 0.0)
        }
    }

    /**
     * Grabs the current GPS fix and calls /check_location.
     *
     * FIX (Bug 1): The result message now accurately reflects the server's response:
     *   - ≥1 matches → "You are near X active mission(s)!"
     *   - 0 matches  → "Location updated — no nearby missions right now."
     *   - Server/network error → descriptive error string
     *
     * Previously the code used `matches.length() >= 0` which is always true,
     * so the user always saw "Location updated" regardless of what actually happened.
     */
    private fun forceUpdateLocation(
        userId: Int,
        radius: Int,
        context: Context,
        onComplete: (String) -> Unit
    ) {
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) { onComplete("Location permission missing."); return }

            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { location ->
                    if (location == null) { onComplete("Hardware could not find GPS signal."); return@addOnSuccessListener }

                    lifecycleScope.launch(Dispatchers.IO) {
                        val result = ApiClient.checkLocation(userId, location.latitude, location.longitude, radius)

                        // FIX (Bug 1): pattern-match the sealed class
                        val message = when (result) {
                            is LocationCheckResult.Success -> {
                                val count = result.matches.length()
                                if (count > 0)
                                    "You are near $count active mission(s)!"
                                else
                                    "Location updated — no nearby missions right now."
                            }
                            is LocationCheckResult.ServerError ->
                                "Server error (HTTP ${result.code}). Try again later."
                            is LocationCheckResult.NetworkError ->
                                "Network error: ${result.message}"
                        }

                        withContext(Dispatchers.Main) { onComplete(message) }
                    }
                }
                .addOnFailureListener { onComplete("Error communicating with GPS hardware.") }

        } catch (e: SecurityException) { onComplete("Permission denied.") }
    }
}
