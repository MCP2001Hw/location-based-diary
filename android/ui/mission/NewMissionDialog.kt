package com.diss.location_based_diary.ui.mission

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.diss.location_based_diary.data.model.Task
import com.diss.location_based_diary.data.model.TaskEntry

// =========================================================================
// 1. MAIN MISSION DIALOG
// =========================================================================

/**
 * Pop-up that lets the user create or edit a geofence mission.
 *
 * FIX (Bug 2): Added a separate minute field next to the hour field.
 * Previously the time was always saved as "$hour:00", making it impossible
 * to set a reminder at, say, 14:30.  The saved format is still "HH:MM" so
 * the backend and [com.diss.location_based_diary.util.TaskFilter] require no changes.
 */
@Composable
fun NewMissionDialog(
    onDismiss: () -> Unit,
    onAddMission: (TaskEntry) -> Unit,
    taskToEdit: Task? = null,
    amenities: List<String>
) {
    // ── Helpers to pre-fill fields when editing ────────────────────────────
    val existingHour   = remember(taskToEdit) { parseHour(taskToEdit?.time) }
    val existingMinute = remember(taskToEdit) { parseMinute(taskToEdit?.time) }

    // ── State ──────────────────────────────────────────────────────────────
    var isAnyTime by remember { mutableStateOf(taskToEdit?.time == "Any time") }
    var hour      by remember { mutableStateOf(existingHour) }
    var minute    by remember { mutableStateOf(existingMinute) } // FIX (Bug 2): new field
    var cycle     by remember { mutableStateOf(taskToEdit?.cycle ?: "Once only") }
    var description by remember { mutableStateOf(taskToEdit?.description ?: "") }

    val selectedWeekdays  = remember { mutableStateListOf<String>().also { it.addAll(taskToEdit?.weekdays ?: emptyList()) } }
    val selectedLocations = remember { mutableStateListOf<String>().also { it.addAll(taskToEdit?.locationCategories ?: emptyList()) } }

    var locationText by remember { mutableStateOf("") }
    var expanded     by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    // ── Dialog shell ───────────────────────────────────────────────────────
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 40.dp)
                .imePadding(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
                    .pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ── Title ─────────────────────────────────────────────────
                Text(
                    text  = if (taskToEdit == null) "New mission" else "Edit mission",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(16.dp))

                // =========================================================
                // 2. TIME & FREQUENCY
                // =========================================================

                // "Any Time" checkbox + hour + minute fields
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Any Time:")
                    Checkbox(checked = isAnyTime, onCheckedChange = { isAnyTime = it })

                    // FIX (Bug 2): Show both Hour and Minute fields when "Any Time" is off
                    if (!isAnyTime) {
                        Spacer(modifier = Modifier.width(12.dp))

                        // Hour field
                        OutlinedTextField(
                            value         = hour,
                            onValueChange = { newValue ->
                                if (newValue.isEmpty() || (newValue.length <= 2 && newValue.all { it.isDigit() })) {
                                    val v = newValue.toIntOrNull()
                                    if (v == null || v in 0..23) hour = newValue
                                }
                            },
                            label           = { Text("HH") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine      = true,
                            modifier        = Modifier.width(72.dp)
                        )

                        Text(" : ", style = MaterialTheme.typography.titleMedium)

                        // Minute field (new)
                        OutlinedTextField(
                            value         = minute,
                            onValueChange = { newValue ->
                                if (newValue.isEmpty() || (newValue.length <= 2 && newValue.all { it.isDigit() })) {
                                    val v = newValue.toIntOrNull()
                                    if (v == null || v in 0..59) minute = newValue
                                }
                            },
                            label           = { Text("MM") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine      = true,
                            modifier        = Modifier.width(72.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                // Cycle dropdown
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Cycle:")
                    Spacer(modifier = Modifier.width(8.dp))
                    SimpleDropdown(
                        options          = listOf("Once only", "Every day", "Every week", "Every month", "Every year"),
                        selectedOption   = cycle,
                        onOptionSelected = { cycle = it }
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))

                // Weekday checkboxes
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach { day ->
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(day, style = MaterialTheme.typography.bodySmall, fontSize = 12.sp)
                            Checkbox(
                                checked         = selectedWeekdays.contains(day),
                                onCheckedChange = { isChecked ->
                                    if (isChecked) selectedWeekdays.add(day) else selectedWeekdays.remove(day)
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                // =========================================================
                // 3. LOCATION SEARCH & LIST
                // =========================================================

                Column(modifier = Modifier.fillMaxWidth()) {
                    // Search bar
                    OutlinedTextField(
                        value         = locationText,
                        onValueChange = { locationText = it; expanded = true },
                        label         = { Text("Search Location") },
                        modifier      = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { expanded = it.isFocused },
                        singleLine    = true
                    )

                    // Autocomplete dropdown
                    val filteredAmenities = amenities.filter { it.contains(locationText, ignoreCase = true) }
                    AnimatedVisibility(visible = expanded && filteredAmenities.isNotEmpty()) {
                        Card(
                            modifier  = Modifier.fillMaxWidth().heightIn(max = 150.dp),
                            shape     = RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp),
                            colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            LazyColumn {
                                items(filteredAmenities) { amenity ->
                                    Text(
                                        text     = amenity,
                                        color    = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                locationText = amenity
                                                expanded = false
                                                focusManager.clearFocus()
                                            }
                                            .padding(16.dp)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    // Add location button
                    Button(
                        onClick = {
                            if (locationText.isNotBlank()
                                && !selectedLocations.contains(locationText)
                                && amenities.contains(locationText.trim())
                            ) {
                                selectedLocations.add(locationText.trim())
                                locationText = ""
                                expanded     = false
                                focusManager.clearFocus()
                            }
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) { Text("Add Location") }
                    Spacer(modifier = Modifier.height(8.dp))

                    // Selected locations list
                    if (selectedLocations.isNotEmpty()) {
                        Text("Targeted Locations:", style = MaterialTheme.typography.labelMedium)
                        Card(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp).padding(top = 4.dp),
                            colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            LazyColumn(modifier = Modifier.padding(4.dp)) {
                                items(selectedLocations.toList()) { loc ->
                                    Row(
                                        modifier              = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment     = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("- $loc", style = MaterialTheme.typography.bodyMedium)
                                        IconButton(onClick = { selectedLocations.remove(loc) }, modifier = Modifier.size(24.dp)) {
                                            Icon(Icons.Default.Delete, contentDescription = "Remove Location", tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                // =========================================================
                // 4. DESCRIPTION & SAVE
                // =========================================================

                OutlinedTextField(
                    value         = description,
                    onValueChange = { description = it },
                    label         = { Text("Description") },
                    modifier      = Modifier.fillMaxWidth().height(120.dp),
                    maxLines      = 5
                )
                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        // FIX (Bug 2): Build the time string from both hour and minute,
                        // zero-padding each component so the format is always "HH:MM".
                        val finalTime = if (isAnyTime) "Any time"
                                        else "${hour.padStart(2, '0')}:${minute.padStart(2, '0')}"

                        onAddMission(
                            TaskEntry(
                                time = finalTime,
                                cycle = cycle,
                                weekdays = selectedWeekdays.toList(),
                                locationCategories = selectedLocations.toList(),
                                description = description
                            )
                        )
                    },
                    enabled = selectedLocations.isNotEmpty(),
                    colors  = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(
                        text  = if (taskToEdit == null) "Add" else "Save",
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

// =========================================================================
// 5. HELPER COMPOSABLE
// =========================================================================

@Composable
fun SimpleDropdown(
    options: List<String>,
    selectedOption: String,
    onOptionSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Button(onClick = { expanded = true }) { Text(selectedOption) }
        DropdownMenu(
            expanded         = expanded,
            onDismissRequest = { expanded = false },
            modifier         = Modifier.heightIn(max = 300.dp)
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text    = { Text(option) },
                    onClick = { onOptionSelected(option); expanded = false }
                )
            }
        }
    }
}

// =========================================================================
// 6. PRIVATE PARSING HELPERS (Bug 2)
// =========================================================================

/**
 * Extracts the zero-padded hour string from a stored time like "14:30".
 * Returns "00" for "Any time" or null input.
 */
private fun parseHour(time: String?): String {
    if (time == null || time == "Any time") return "00"
    return time.substringBefore(":").padStart(2, '0')
}

/**
 * Extracts the zero-padded minute string from a stored time like "14:30".
 * Returns "00" for "Any time" or null input.
 */
private fun parseMinute(time: String?): String {
    if (time == null || time == "Any time") return "00"
    val parts = time.split(":")
    return if (parts.size >= 2) parts[1].padStart(2, '0') else "00"
}
