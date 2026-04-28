package com.diss.location_based_diary.ui.dashboard

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.diss.location_based_diary.data.api.ApiClient
import com.diss.location_based_diary.data.model.Task
import com.diss.location_based_diary.ui.theme.LBSTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// =========================================================================
// 1. THE MAIN DASHBOARD LIST
// =========================================================================

@Composable
fun TaskList(
    tasks: List<Task>,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    onShowDialog: () -> Unit,
    onDeleteTask: (Task) -> Unit,
    onEditTask: (Task) -> Unit,
    onToggleActive: (Task, Boolean) -> Unit
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(
                onClick        = onShowDialog,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor   = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Mission")
            }
        }
    ) { innerPadding ->
        when {
            isLoading -> {
                Box(
                    modifier         = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Connecting to database...", color = MaterialTheme.colorScheme.onBackground)
                    }
                }
            }
            tasks.isEmpty() -> {
                Box(
                    modifier         = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No missions found", color = MaterialTheme.colorScheme.onBackground)
                }
            }
            else -> {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                    items(tasks) { task ->
                        TaskCard(
                            task           = task,
                            onDelete       = { onDeleteTask(task) },
                            onEdit         = { onEditTask(task) },
                            onToggleActive = { onToggleActive(task, it) }
                        )
                    }
                }
            }
        }
    }
}

// =========================================================================
// 2. INDIVIDUAL TASK CARD
// =========================================================================

@Composable
fun TaskCard(
    task: Task,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onToggleActive: (Boolean) -> Unit
) {
    // FIX (Bug 4): We keep track of the *confirmed* server state separately from
    // what the switch is displaying.  The switch optimistically shows the user's
    // intended value immediately, but if all 3 server retries fail we revert it
    // to the last known-good value and show a Toast.
    var confirmedState by remember(task.isActive) { mutableStateOf(task.isActive) }
    var switchDisplay  by remember(task.isActive) { mutableStateOf(task.isActive) }
    var isSaving       by remember { mutableStateOf(false) }

    val context        = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    Card(
        modifier  = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor   = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier          = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text  = "${task.time} - ${task.locationCategories.joinToString(", ")}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = task.description, style = MaterialTheme.typography.bodyMedium)
                if (task.weekdays.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text  = "Days: ${task.weekdays.joinToString(", ")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Switch(
                checked         = switchDisplay,
                enabled         = !isSaving, // Disable switch while a save attempt is in flight
                onCheckedChange = { intendedValue ->
                    // Immediately update the UI (optimistic update)
                    switchDisplay = intendedValue
                    isSaving = true

                    coroutineScope.launch {
                        val success = tryToggleWithRetries(task.id, intendedValue, maxRetries = 3)

                        withContext(Dispatchers.Main) {
                            if (success) {
                                // Server confirmed — update the authoritative state
                                confirmedState = intendedValue
                                task.isActive  = intendedValue
                                onToggleActive(intendedValue)
                            } else {
                                // All retries failed — revert the switch and tell the user
                                switchDisplay = confirmedState
                                task.isActive = confirmedState
                                Toast.makeText(
                                    context,
                                    "Failed to update mission. Please check your connection.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            isSaving = false
                        }
                    }
                }
            )
        }

        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Button(onClick = onEdit, modifier = Modifier.weight(1f)) { Text("Edit") }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onDelete,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text("Delete") }
        }
    }
}

/**
 * Attempts to toggle a task's active status on the server, retrying up to
 * [maxRetries] times on failure.
 *
 * FIX (Bug 4): The original code made a single fire-and-forget call with no
 * feedback on failure.  This function retries with exponential-style waits and
 * returns true only when the server confirms success, or false after all retries
 * are exhausted.
 *
 * Must be called from a coroutine — runs on Dispatchers.IO internally.
 */
private suspend fun tryToggleWithRetries(taskId: Int, isActive: Boolean, maxRetries: Int): Boolean {
    repeat(maxRetries) { attempt ->
        val success = withContext(Dispatchers.IO) {
            runCatching { ApiClient.toggleTaskActive(taskId, isActive) }.getOrDefault(false)
        }
        if (success) return true

        // Brief pause before the next attempt (300 ms, 600 ms, 900 ms …)
        if (attempt < maxRetries - 1) {
            kotlinx.coroutines.delay(300L * (attempt + 1))
        }
    }
    return false
}

// =========================================================================
// 3. ANDROID STUDIO PREVIEW
// =========================================================================

@Preview(showBackground = true)
@Composable
fun TaskListPreview() {
    LBSTheme {
        TaskList(
            tasks = emptyList(),
            isLoading = false,
            onShowDialog = {},
            onDeleteTask = {},
            onEditTask = {},
            onToggleActive = { _, _ -> }
        )
    }
}
