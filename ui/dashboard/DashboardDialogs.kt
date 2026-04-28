package com.diss.location_based_diary.ui.dashboard

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import com.diss.location_based_diary.ui.map.LeafletMapScreen
import com.diss.location_based_diary.data.model.Friend
import com.diss.location_based_diary.data.model.FriendRequest

/**
 * All modal dialogs shown on the Dashboard screen, extracted from MainActivity
 * so each dialog is independently readable and testable.
 */

// ── Consent gate ──────────────────────────────────────────────────────────

/**
 * Non-dismissible dialog shown when the server reports the user hasn't
 * signed the research consent form yet.
 */
@Composable
fun ConsentDialog(onAccepted: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { /* intentionally blocked */ },
        properties = DialogProperties(
            dismissOnBackPress    = false,
            dismissOnClickOutside = false
        ),
        title = { Text("Research Consent Required") },
        text = {
            Column {
                Text("Before using Location Based Diary, you must read and sign the participant consent form for this academic study.")
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick  = { context.startActivity(Intent(Intent.ACTION_VIEW, "https://forms.cloud.microsoft/e/LuTDW2GAWA".toUri())) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Open Consent Form") }
            }
        },
        confirmButton = { Button(onClick = onAccepted) { Text("I have read and signed") } },
        dismissButton = {
            TextButton(onClick = { (context as? Activity)?.finishAffinity() }) {
                Text("I decline", color = MaterialTheme.colorScheme.error)
            }
        }
    )
}

// ── Delete account confirmation ───────────────────────────────────────────

@Composable
fun DeleteAccountDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Account?") },
        text  = { Text("This will permanently delete your account and all of your missions.") },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors  = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text("Delete Everything") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ── Friends / Social Radar ────────────────────────────────────────────────

@Composable
fun FriendsDialog(
    currentUserId: Int,
    friendsList: List<Friend>,
    pendingRequests: List<FriendRequest>,
    onSendRequest: (String) -> Unit,
    onToggleShare: (friendId: Int, isSharing: Boolean) -> Unit,
    onApproveRequest: (FriendRequest) -> Unit,
    onDenyRequest: (FriendRequest) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTab    by remember { mutableIntStateOf(0) }
    var newFriendName  by remember { mutableStateOf("") }
    var requestMessage by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Friends") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Radar") })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Inbox (${pendingRequests.size})") })
                }
                Spacer(modifier = Modifier.height(16.dp))
                when (selectedTab) {
                    0 -> RadarTab(
                        friendsList    = friendsList,
                        newFriendName  = newFriendName,
                        requestMessage = requestMessage,
                        onNameChange   = { newFriendName = it },
                        onSendRequest  = {
                            if (newFriendName.isNotBlank()) {
                                onSendRequest(newFriendName)
                                requestMessage = "Sending…"
                                newFriendName  = ""
                            }
                        },
                        onToggleShare  = onToggleShare
                    )
                    1 -> InboxTab(pendingRequests = pendingRequests, onApprove = onApproveRequest, onDeny = onDenyRequest)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun RadarTab(
    friendsList: List<Friend>,
    newFriendName: String,
    requestMessage: String,
    onNameChange: (String) -> Unit,
    onSendRequest: () -> Unit,
    onToggleShare: (friendId: Int, isSharing: Boolean) -> Unit
) {
    Column {
        OutlinedTextField(value = newFriendName, onValueChange = onNameChange,
            label = { Text("Request Friend's Location") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onSendRequest, modifier = Modifier.align(Alignment.End)) { Text("Send Request") }
        if (requestMessage.isNotBlank()) {
            Text(requestMessage, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))
        if (friendsList.isEmpty()) {
            Text("Nobody is sharing their location with you.", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                items(friendsList) { friend -> FriendCard(friend = friend, onToggleShare = onToggleShare) }
            }
        }
    }
}

@Composable
private fun FriendCard(friend: Friend, onToggleShare: (friendId: Int, isSharing: Boolean) -> Unit) {
    var switchState by remember(friend.isSharing) { mutableStateOf(friend.isSharing) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier              = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(friend.username, style = MaterialTheme.typography.titleMedium)
                if (switchState) {
                    if (friend.distanceMeters != null) {
                        Text("${friend.distanceMeters} meters away", color = MaterialTheme.colorScheme.primary)
                        Text("Last seen: ${friend.lastUpdated}", style = MaterialTheme.typography.labelSmall)
                    } else {
                        Text("Location unknown", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Text("Tracking Paused", color = MaterialTheme.colorScheme.outline)
                    Text("Last seen: ${friend.lastUpdated ?: "Never"}", style = MaterialTheme.typography.labelSmall)
                }
            }
            Switch(checked = switchState, onCheckedChange = { isChecked ->
                switchState = isChecked
                onToggleShare(friend.id, isChecked)
            })
        }
    }
}

@Composable
private fun InboxTab(
    pendingRequests: List<FriendRequest>,
    onApprove: (FriendRequest) -> Unit,
    onDeny: (FriendRequest) -> Unit
) {
    if (pendingRequests.isEmpty()) {
        Text("No pending location requests.", style = MaterialTheme.typography.bodyMedium)
    } else {
        LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
            items(pendingRequests) { req ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("${req.username} wants to see your location!", style = MaterialTheme.typography.titleMedium)
                        Text("Sent: ${req.date}", style = MaterialTheme.typography.labelSmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                            Button(onClick = { onApprove(req) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) { Text("Approve") }
                            Button(onClick = { onDeny(req) },    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Deny") }
                        }
                    }
                }
            }
        }
    }
}

// ── Settings ──────────────────────────────────────────────────────────────

/**
 * @param onForceUpdateLocation Called when the user taps "Force Update Location".
 *   The lambda receives another lambda [onResult] which it should call with
 *   a human-readable result message once the operation completes.
 *
 * FIX (Bug 1): [onForceUpdateLocation] now expects the caller to pass a
 * meaningful message that reflects whether any geofence actually matched,
 * rather than unconditionally saying "Location updated".  The dialog just
 * displays whatever string the caller provides.
 */
@Composable
fun SettingsDialog(
    savedUsername: String,
    savedRadius: Int,
    onSave: (newUsername: String, newPassword: String, newRadius: Int) -> Unit,
    onForceUpdateLocation: (onResult: (String) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    var editUsername   by remember { mutableStateOf(savedUsername) }
    var editPassword   by remember { mutableStateOf("") }
    var editRadius     by remember { mutableStateOf(savedRadius.toString()) }
    var forceUpdateMsg by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Account Settings") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(value = editUsername, onValueChange = { editUsername = it }, label = { Text("Username") }, singleLine = true)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = editPassword, onValueChange = { editPassword = it }, label = { Text("New Password") }, singleLine = true)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value           = editRadius,
                    onValueChange   = { if (it.isEmpty() || it.all(Char::isDigit)) editRadius = it },
                    label           = { Text("Notification Radius (meters)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine      = true
                )
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick  = { forceUpdateMsg = "Locating…"; onForceUpdateLocation { msg -> forceUpdateMsg = msg } },
                    modifier = Modifier.fillMaxWidth(),
                    colors   = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) { Text("Force Update Location") }
                if (forceUpdateMsg.isNotBlank()) {
                    Text(
                        forceUpdateMsg,
                        color    = MaterialTheme.colorScheme.primary,
                        style    = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(editUsername, editPassword, editRadius.toIntOrNull() ?: 200) }) { Text("Save Settings") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ── Questionnaire ─────────────────────────────────────────────────────────

@Composable
fun QuestionnaireDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("App Evaluation") },
        text = {
            Column {
                Text("Finished testing the app? Tap below to open the MS Forms evaluation questionnaire. Your feedback is highly appreciated!")
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick  = { context.startActivity(Intent(Intent.ACTION_VIEW, "https://forms.cloud.microsoft/e/LZBTMT5bBm".toUri())) },
                    modifier = Modifier.fillMaxWidth(),
                    colors   = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) { Text("Open Questionnaire") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

// ── Full-screen map ───────────────────────────────────────────────────────

@Composable
fun MapDialog(currentLat: Double, currentLon: Double, currentUserId: Int, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier              = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text("Live Map", style = MaterialTheme.typography.headlineSmall)
                    Button(onClick = onDismiss) { Text("Close") }
                }
                HorizontalDivider()
                Box(modifier = Modifier.weight(1f)) {
                    LeafletMapScreen(
                        currentLat = currentLat,
                        currentLon = currentLon,
                        currentUserId = currentUserId
                    )
                }
            }
        }
    }
}
