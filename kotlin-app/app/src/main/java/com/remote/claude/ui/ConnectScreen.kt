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

// 首启 / 未配置时的「连接」页。
// 主路径:用手机系统相机扫电脑终端(install.sh)打印的二维码 → 自动唤起本页并连接。
// 兜底:在这里手动填写服务器地址 + 令牌。
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
                "在电脑上运行 ./install.sh,用手机相机扫描终端里的二维码即可自动连接。\n或在下面手动填写:",
                color = C.subtle,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
            Spacer(Modifier.height(22.dp))

            Field(label = "服务器地址", value = url, placeholder = "ws://100.x.x.x:4000", mono = true) { url = it }
            Spacer(Modifier.height(12.dp))
            Field(label = "令牌 (token)", value = token, placeholder = "粘贴电脑上显示的令牌", mono = true) { token = it }

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
                Text("连接", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
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

// 未就绪时按钮的灰底
private fun solidDim() = androidx.compose.ui.graphics.SolidColor(Color(0x33000000))
