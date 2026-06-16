package com.remote.claude.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.remote.claude.AppViewModel
import com.remote.claude.ui.theme.C

// The "Connect" screen shown on first launch / when not yet configured.
// Primary path: scan the QR code printed by the terminal (install.sh) with the phone's camera → this screen opens and connects automatically.
// Fallback: manually enter the server address + token here.
@Composable
fun ConnectScreen(vm: AppViewModel) {
    var url by remember { mutableStateOf("ws://") }
    var token by remember { mutableStateOf("") }

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
                "Run ./install.sh on your computer and scan the QR code in the terminal with your phone's camera to connect automatically.\nOr enter it manually below:",
                color = C.subtle,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
            Spacer(Modifier.height(22.dp))

            Field(label = "Server address", value = url, placeholder = "ws://100.x.x.x:4000", mono = true) { url = it }
            Spacer(Modifier.height(12.dp))
            Field(label = "Token", value = token, placeholder = "Paste the token shown on your computer", mono = true) { token = it }

            Spacer(Modifier.height(24.dp))
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
