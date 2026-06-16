package com.remote.claude

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remote.claude.ui.ConnectScreen
import com.remote.claude.ui.ConversationScreen
import com.remote.claude.ui.SessionListScreen
import com.remote.claude.ui.theme.ClaudeRemoteTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // full-screen immersive + don't resize the window for the keyboard; the IME is handled uniformly by Compose's imePadding (avoids double-counting)
        handleDeepLink(intent) // cold start: if launched by a scanned deep link, configure and connect directly
        setContent {
            ClaudeRemoteTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                // When the app returns to the foreground (ON_START), refresh the current session to pull new messages produced while backgrounded (desktop continuing the chat / background turns)
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val obs = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_START) vm.refreshCurrent()
                    }
                    lifecycleOwner.lifecycle.addObserver(obs)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
                }
                // On the conversation screen, the system back button returns to the list
                BackHandler(enabled = state.current != null) { vm.back() }
                when {
                    state.needsSetup -> ConnectScreen(vm)
                    state.current == null -> SessionListScreen(vm)
                    else -> ConversationScreen(vm)
                }
            }
        }
    }

    // Scanning a QR code while the app is already running comes through here
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    // Parse claudeeverywhere://connect?url=...&token=... and configure + connect automatically
    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "claudeeverywhere") return
        val url = data.getQueryParameter("url") ?: return
        val token = data.getQueryParameter("token") ?: ""
        vm.configure(url, token)
    }
}
