package com.karursdo.ui.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.karursdo.R
import com.karursdo.ui.theme.Brand
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long the launch screen stays up. */
private const val SPLASH_TOTAL_MS = 7000L

private val SplashBg = Color(0xFFF7F9FC)
private val Muted = Color(0xFF64748B)

/**
 * Government launch screen on a clean LIGHT background.
 *
 * Layout, top → bottom:
 *   • Government of India emblem (upper third)
 *   • Department of Posts · office block
 *   • India Post logo (lower third)
 *   • "made by Arun Selvaraj" credit pinned near the bottom
 *
 * Elements fade in gently; tap anywhere to skip. Honours "remove animations".
 */
@Composable
fun BrandedSplash(
    reduceMotion: Boolean,
    dark: Boolean = false,
    onFinished: () -> Unit
) {
    val emblemAlpha = remember { Animatable(0f) }
    val textAlpha = remember { Animatable(0f) }
    val postAlpha = remember { Animatable(0f) }
    val creditAlpha = remember { Animatable(0f) }
    var skipped by remember { mutableStateOf(false) }

    LaunchedEffect(reduceMotion, skipped) {
        if (skipped) { onFinished(); return@LaunchedEffect }
        coroutineScope {
            launch {
                if (reduceMotion) {
                    emblemAlpha.snapTo(1f); textAlpha.snapTo(1f)
                    postAlpha.snapTo(1f); creditAlpha.snapTo(1f)
                } else {
                    emblemAlpha.animateTo(1f, tween(500))
                    textAlpha.animateTo(1f, tween(500))
                    postAlpha.animateTo(1f, tween(500))
                    creditAlpha.animateTo(1f, tween(500))
                }
            }
            delay(SPLASH_TOTAL_MS)
        }
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SplashBg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { skipped = true }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 30.dp)
        ) {
            // ---- upper third: Government of India emblem ----
            Spacer(Modifier.weight(1.0f))
            Image(
                painter = painterResource(R.drawable.gov_india_emblem),
                contentDescription = "Government of India emblem",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(118.dp)
                    .alpha(emblemAlpha.value)
            )

            // ---- office block ----
            Spacer(Modifier.height(18.dp))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.alpha(textAlpha.value)
            ) {
                Text(
                    "Department of Posts",
                    textAlign = TextAlign.Center,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.3.sp,
                    color = Brand.PrimaryDark
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "O/o the ASP, Karur Sub Division",
                    textAlign = TextAlign.Center,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Brand.Ink2
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "Karur – 639001",
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Muted
                )
            }

            // ---- lower third: India Post logo ----
            Spacer(Modifier.weight(1.15f))
            Image(
                painter = painterResource(R.drawable.india_post_full),
                contentDescription = "India Post logo",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .width(196.dp)
                    .alpha(postAlpha.value)
            )
            Spacer(Modifier.weight(1.0f))

            // ---- bottom credit ----
            Text(
                "Made by Arun Selvaraj",
                textAlign = TextAlign.Center,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontStyle = FontStyle.Italic,
                fontFamily = FontFamily.Cursive,
                letterSpacing = 0.5.sp,
                color = Brand.PrimaryDark,
                modifier = Modifier.alpha(creditAlpha.value)
            )
            Spacer(Modifier.height(22.dp))
        }
    }
}
