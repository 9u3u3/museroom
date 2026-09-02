package com.museroom.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.museroom.app.net.AuthRepository
import com.museroom.app.net.Supabase
import com.museroom.app.sync.SyncEngine
import com.museroom.app.sync.SyncState
import com.museroom.app.util.formatAgo
import kotlinx.coroutines.launch

/**
 * Signing in, and what has reached the server. Deliberately plain: the real
 * interface comes later, this is here so the pipeline can be exercised.
 */
@Composable
fun AccountCard() {
    val context = LocalContext.current
    val auth = remember { AuthRepository.get(context) }
    val sync = remember { SyncEngine.get(context) }
    val scope = rememberCoroutineScope()

    val session by auth.session.collectAsStateWithLifecycle()
    val syncState by sync.state.collectAsStateWithLifecycle()
    val pending by sync.pendingEvents.collectAsStateWithLifecycle(0)

    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Panel {
        if (session == null) {
            Text(
                text = "Sign in to sync",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text = "Your listening stays on this phone until you do. Signing in is " +
                    "what puts you on the leaderboard.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(14.dp))

            Button(
                enabled = !busy && Supabase.googleConfigured,
                onClick = {
                    busy = true
                    message = null
                    scope.launch {
                        auth.signInWithGoogle(context)
                            .onSuccess { message = null; sync.sync() }
                            .onFailure { message = it.message }
                        busy = false
                    }
                },
            ) {
                Text("Continue with Google")
            }

            if (!Supabase.googleConfigured) {
                Spacer(Modifier.size(6.dp))
                Caption("Google is not configured in this build yet. Use email below.")
            }

            Spacer(Modifier.size(14.dp))
            EmailFallback(
                busy = busy,
                onSignIn = { email, password ->
                    busy = true
                    message = null
                    scope.launch {
                        auth.signInWithPassword(email, password)
                            .onSuccess { sync.sync() }
                            .onFailure { message = it.message }
                        busy = false
                    }
                },
                onSignUp = { email, password ->
                    busy = true
                    message = null
                    scope.launch {
                        auth.signUpWithPassword(email, password)
                            .onSuccess { message = "Account created. Check your email, then sign in." }
                            .onFailure { message = it.message }
                        busy = false
                    }
                },
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Caption("Signed in")
                TextButton(onClick = { auth.signOut() }) { Text("Sign out") }
            }
            Text(
                text = session?.email?.takeIf { it.isNotBlank() } ?: "Anonymous account",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(Modifier.size(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Waiting to upload",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "$pending events",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(Modifier.size(6.dp))
            Caption(
                when (val s = syncState) {
                    is SyncState.Idle -> "Not synced yet this session."
                    is SyncState.SignedOut -> "Signed out."
                    is SyncState.Running -> "Uploading."
                    is SyncState.Synced ->
                        if (s.rows == 0) "Up to date, checked ${formatAgo(s.atMs)}."
                        else "Sent ${s.rows} rows, ${formatAgo(s.atMs)}."
                    is SyncState.Failed -> "Last attempt failed: ${s.reason}"
                },
            )

            Spacer(Modifier.size(10.dp))
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch { sync.sync(); busy = false }
                },
            ) {
                Text("Sync now")
            }
        }

        message?.let {
            Spacer(Modifier.size(10.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun EmailFallback(
    busy: Boolean,
    onSignIn: (String, String) -> Unit,
    onSignUp: (String, String) -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        label = { Text("Email") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.size(8.dp))
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.size(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !busy, onClick = { onSignIn(email, password) }) { Text("Sign in") }
        TextButton(enabled = !busy, onClick = { onSignUp(email, password) }) { Text("Create account") }
    }
}

@Composable
private fun Panel(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(18.dp),
        content = content,
    )
}

@Composable
private fun Caption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
