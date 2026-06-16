package com.remote.claude.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.remote.claude.AppViewModel
import com.remote.claude.ui.theme.C

// The "Connect" screen shown on first launch / when not yet configured.
// Primary path: tap "Scan QR code" to open the in-app camera and scan the QR code
// printed by the desktop install.sh — the address + token are filled in automatically.
// Fallback: enter the server address + token manually below.
@Composable
fun ConnectScreen(vm: AppViewModel) {
    var url by remember { mutableStateOf("ws://") }
    var token by remember { mutableStateOf("") }

    // In-app QR scanner (zxing). On success, contents = "claudeeverywhere://connect?url=..&token=.."
    val scan = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { applyConnectPayload(it, vm) }
    }
    val launchScan = {
        scan.launch(
            ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Point at the QR code in your terminal")
                setBeepEnabled(false)
                setOrientationLocked(false)
            }
        )
    }

    BlobBackground {
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Claude Everywhere", color = C.ink, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(10.dp))
            Text(
                "Run ./install.sh on your computer, then scan the QR code in the terminal.",
                color = C.subtle,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
            Spacer(Modifier.height(24.dp))

            // Primary action: scan
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .background(coralBrush())
                    .clickable { launchScan() }
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("⬚  Scan QR code", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(20.dp))
            // divider: or enter manually
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(1.dp).background(C.dim.copy(alpha = 0.3f)))
                Text("  or enter manually  ", color = C.dim, fontSize = 11.sp)
                Box(Modifier.weight(1f).height(1.dp).background(C.dim.copy(alpha = 0.3f)))
            }
            Spacer(Modifier.height(20.dp))

            Field(label = "Server address", value = url, placeholder = "ws://100.x.x.x:4000", mono = true) { url = it }
            Spacer(Modifier.height(12.dp))
            Field(label = "Token", value = token, placeholder = "Paste the token shown on your computer", mono = true) { token = it }

            Spacer(Modifier.height(20.dp))
            val ready = url.trim().startsWith("ws") && token.isNotBlank()
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .background(if (ready) coralBrush() else solidDim())
                    .clickable(enabled = ready) { vm.configure(url.trim(), token.trim()) }
                    .padding(vertical = 15.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Connect", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// Parse a scanned payload — either "claudeeverywhere://connect?url=..&token=.." or a bare ws:// URL —
// and connect. Mirrors MainActivity.handleDeepLink for the camera path.
private fun applyConnectPayload(raw: String, vm: AppViewModel) {
    val uri = runCatching { android.net.Uri.parse(raw) }.getOrNull()
    val url = uri?.getQueryParameter("url") ?: raw.takeIf { it.startsWith("ws") }
    val token = uri?.getQueryParameter("token") ?: ""
    if (url != null) vm.configure(url, token)
}

@Composable
private fun Field(label: String, value: String, placeholder: String, mono: Boolean, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = C.dim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(5.dp))
        Glass(Modifier.fillMaxWidth(), radius = 16.dp) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                if (value.isEmpty()) {
                    Text(placeholder, color = C.dim, fontSize = 14.sp, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = TextStyle(color = C.ink, fontSize = 14.sp, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default),
                    cursorBrush = coralBrush(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// gray fill for the button when not ready
private fun solidDim() = androidx.compose.ui.graphics.SolidColor(Color(0x33000000))
