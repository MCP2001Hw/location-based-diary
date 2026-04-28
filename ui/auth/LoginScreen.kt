package com.diss.location_based_diary.ui.auth

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.diss.location_based_diary.data.api.ApiClient
import com.diss.location_based_diary.data.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First screen the user sees when they are not logged in.
 * Handles both login and registration via the same UI, distinguished by
 * which button is tapped.
 *
 * Changes from original:
 *  - The network call is now a thin wrapper around [ApiClient.authenticate].
 *  - Preference writes go through [AppPreferences].
 */
@Composable
fun LoginScreen(onLoginSuccess: (Int) -> Unit) {
    var username  by remember { mutableStateOf("") }
    var password  by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    val context        = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    fun submit(endpoint: String) {
        if (username.isBlank() || password.isBlank()) {
            Toast.makeText(context, "Fill in all fields", Toast.LENGTH_SHORT).show()
            return
        }
        isLoading = true
        coroutineScope.launch {
            authenticate(
                username   = username,
                password   = password,
                endpoint   = endpoint,
                context    = context,
                onSuccess  = onLoginSuccess,
                onComplete = { isLoading = false }
            )
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier              = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement   = Arrangement.Center,
            horizontalAlignment   = Alignment.CenterHorizontally
        ) {
            Text("Login / Register", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(32.dp))

            OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value               = password,
                onValueChange       = { password = it },
                label               = { Text("Password") },
                singleLine          = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions     = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier            = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(32.dp))

            if (isLoading) {
                CircularProgressIndicator()
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Button(onClick = { submit("login") }, modifier = Modifier.weight(1f)) { Text("Login") }
                    Spacer(modifier = Modifier.width(16.dp))
                    OutlinedButton(onClick = { submit("register") }, modifier = Modifier.weight(1f)) { Text("Register") }
                }
            }
        }
    }
}

// ── Network helper ─────────────────────────────────────────────────────────

/**
 * Calls [ApiClient.authenticate] on the IO dispatcher and routes the result
 * back to the UI thread.
 */
private suspend fun authenticate(
    username: String,
    password: String,
    endpoint: String,
    context: Context,
    onSuccess: (Int) -> Unit,
    onComplete: () -> Unit
) {
    withContext(Dispatchers.IO) {
        try {
            val userId = ApiClient.authenticate(username, password, endpoint)
            withContext(Dispatchers.Main) {
                AppPreferences.saveUsername(context, username)
                Toast.makeText(context, "Success", Toast.LENGTH_SHORT).show()
                onSuccess(userId)
            }
        } catch (e: Exception) {
            val message = e.message?.takeIf { it.isNotBlank() } ?: "Network Error"
            withContext(Dispatchers.Main) {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        } finally {
            withContext(Dispatchers.Main) { onComplete() }
        }
    }
}
