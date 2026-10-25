/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.ui.page.setup

import android.graphics.ColorMatrixColorFilter
import android.graphics.Shader
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * 首启动画与完成转场共用的时间函数：画面是时间的纯函数，每帧只推进时间，
 * 位置与大小都由下面的缓动和弹簧解析式算出。
 */

internal val MotionEaseIn: Easing = CubicBezierEasing(0.55f, 0f, 0.9f, 0.4f)

internal fun clamp01(value: Float) = value.coerceIn(0f, 1f)

internal fun motionTween(t: Float, start: Float, duration: Float, easing: Easing = FastOutSlowInEasing): Float =
    easing.transform(clamp01((t - start) / duration))

/** Compose `spring(dampingRatio, stiffness)` 的阶跃响应解析式（质量 1，角频率 √stiffness）。 */
internal fun motionSpring(t: Float, start: Float, dampingRatio: Float, stiffness: Float): Float {
    val elapsed = t - start
    if (elapsed <= 0f) return 0f
    val omega = sqrt(stiffness)
    return if (dampingRatio < 1f) {
        val damped = omega * sqrt(1f - dampingRatio * dampingRatio)
        1f - exp(-dampingRatio * omega * elapsed) *
            (cos(damped * elapsed) + dampingRatio * omega / damped * sin(damped * elapsed))
    } else {
        1f - exp(-omega * elapsed) * (1f + omega * elapsed)
    }
}

internal fun lerpRect(start: Rect, stop: Rect, fraction: Float): Rect = Rect(
    left = lerp(start.left, stop.left, fraction),
    top = lerp(start.top, stop.top, fraction),
    right = lerp(start.right, stop.right, fraction),
    bottom = lerp(start.bottom, stop.bottom, fraction),
)

/** 液态层：模糊后用 Alpha 阈值收边，形状靠近时自然粘连、拉丝。 */
@Composable
internal fun rememberGooEffect(): RenderEffect {
    val density = LocalDensity.current
    return remember(density) {
        val radius = with(density) { 9.dp.toPx() }
        android.graphics.RenderEffect.createChainEffect(
            android.graphics.RenderEffect.createColorFilterEffect(
                ColorMatrixColorFilter(
                    floatArrayOf(
                        1f, 0f, 0f, 0f, 0f,
                        0f, 1f, 0f, 0f, 0f,
                        0f, 0f, 1f, 0f, 0f,
                        0f, 0f, 0f, 26f, -11f * 255f,
                    ),
                ),
            ),
            android.graphics.RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL),
        ).asComposeRenderEffect()
    }
}
