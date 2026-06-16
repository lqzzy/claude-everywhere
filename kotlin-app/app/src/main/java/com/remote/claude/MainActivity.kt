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
        enableEdgeToEdge() // 全屏沉浸 + 窗口不为键盘 resize,IME 由 Compose 的 imePadding 统一处理(避免双重计算)
        handleDeepLink(intent) // 冷启动:若由扫码 deep link 唤起,直接配置并连接
        setContent {
            ClaudeRemoteTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                // App 回到前台(ON_START)时刷新当前会话,拉取后台期间产生的新消息(电脑续聊 / 后台轮次)
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val obs = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_START) vm.refreshCurrent()
                    }
                    lifecycleOwner.lifecycle.addObserver(obs)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
                }
                // 在对话页时,系统返回键回到列表
                BackHandler(enabled = state.current != null) { vm.back() }
                when {
                    state.needsSetup -> ConnectScreen(vm)
                    state.current == null -> SessionListScreen(vm)
                    else -> ConversationScreen(vm)
                }
            }
        }
    }

    // App 已开着时再扫码,会走这里
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    // 解析 claudeeverywhere://connect?url=...&token=...,自动配置并连接
    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "claudeeverywhere") return
        val url = data.getQueryParameter("url") ?: return
        val token = data.getQueryParameter("token") ?: ""
        vm.configure(url, token)
    }
}
