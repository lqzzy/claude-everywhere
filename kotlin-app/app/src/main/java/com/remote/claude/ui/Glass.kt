package com.remote.claude.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.remote.claude.ui.theme.C
import com.remote.claude.ui.theme.Coral

// Warm-white gradient background + three blurred light blobs (minSdk 31: Modifier.blur uses the system RenderEffect).
@Composable
fun BlobBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFFFFFAF6), Color(0xFFFEF4EE), Color(0xFFFFFBF8))
                )
            )
    ) {
        Box(
            Modifier.align(Alignment.TopEnd).offset(x = 40.dp, y = (-30).dp).size(240.dp)
                .blur(70.dp).background(Color(0xFFF4AB8A).copy(alpha = 0.32f), CircleShape)
        )
        Box(
            Modifier.align(Alignment.BottomStart).offset(x = (-70).dp, y = (-120).dp).size(220.dp)
                .blur(70.dp).background(Color(0xFFF5CF94).copy(alpha = 0.26f), CircleShape)
        )
        Box(
            Modifier.align(Alignment.BottomEnd).offset(x = 50.dp, y = 30.dp).size(240.dp)
                .blur(70.dp).background(Color(0xFFEDA0AA).copy(alpha = 0.22f), CircleShape)
        )
        content()
    }
}

// Inline glass card: translucent white + white border + top-left specular highlight overlay.
@Composable
fun Glass(
    modifier: Modifier = Modifier,
    radius: Dp = 18.dp,
    strong: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .clip(shape)
            .background(if (strong) C.glassBgStrong else C.glassBg)
            .border(1.dp, C.glassBorder, shape)
    ) {
        Box(
            Modifier.matchParentSize().background(
                Brush.linearGradient(
                    0f to Color.White.copy(alpha = 0.55f),
                    0.42f to Color.Transparent,
                )
            )
        )
        content()
    }
}

// Coral gradient brush (user bubble / send button / orb).
fun coralBrush(): Brush = Brush.linearGradient(Coral)
