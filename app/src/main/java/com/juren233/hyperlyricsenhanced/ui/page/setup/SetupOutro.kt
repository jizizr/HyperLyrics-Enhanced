/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.ui.page.setup

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.util.lerp
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.nav.transition.NavTransitionScope
import top.yukonga.miuix.kmp.nav.transition.NavTransitions

/*
 * 基础设置完成后的转场（秒）：图标归位状态栏。
 * 引导页内容按逐项浮现的反向依次淡出（由引导页自己完成）；顶栏图标沿一条弧线缩回状态栏，
 * 封面一直显示并移到超级岛封面的位置，歌曲信息与歌词胶囊化开，岛亮起音频律动。
 * 引导内容大部分淡出时切换页面：切换期间导航不做滑动（见 [SetupOutroAwareTransition]），
 * 目标页面直接在原地以逐项浮现的样式淡入上浮，盖过正在退场的引导页，中间没有空白；岛随后收起。
 * 目标页面切换后分帧组合（见 [rememberSetupOutroStage]），列表项组合出来时各自逐项浮现（见 [setupOutroItemReveal]）。
 */
private object Outro {
    const val FLY = 0.06f
    const val MORPH = 0.1f
    const val MORPH_DURATION = 0.4f
    const val NAVIGATE = 0.28f
    const val MUSIC = 0.36f
    const val REVEAL = NAVIGATE
    const val REVEAL_DURATION = SETUP_STAGGER_DURATION_MILLIS / 1000f
    const val RETREAT = 1.95f
    /** 岛收起：先高度不变、两侧收成圆，再缩小消失（见 [SetupStatusIsland.retract]）。 */
    const val RETREAT_PINCH = 0.24f
    const val RETREAT_VANISH = 0.18f
    const val END = 2.4f
}

internal class SetupOutroRequest(val fromInWindow: Rect, val onCovered: () -> Unit)

/** 完成转场的图标要跨越页面切换继续演出，因此由导航外层的 [SetupOutroHost] 绘制。 */
@Stable
internal class SetupOutroController {
    var request by mutableStateOf<SetupOutroRequest?>(null)
        private set

    /** 转场结束时要显露的页面的浮现进度（0..1）；不在转场中时恒为 1。 */
    var destinationReveal by mutableFloatStateOf(1f)
        internal set

    /** 转场中的页面切换不做导航滑动，目标页面原地浮现。 */
    var holdNavigationMotion by mutableStateOf(false)
        internal set

    /** 从顶栏图标 [fromInWindow] 开始转场；引导内容淡出时调用 [onCovered] 切换页面。 */
    fun start(fromInWindow: Rect, onCovered: () -> Unit) {
        if (request == null) request = SetupOutroRequest(fromInWindow, onCovered)
    }

    fun finish() {
        destinationReveal = 1f
        holdNavigationMotion = false
        request = null
    }
}

internal val LocalSetupOutro = staticCompositionLocalOf<SetupOutroController?> { null }

/**
 * 完成转场显露的页面分帧组合：返回当前允许组合到的阶段（0..[stages]）。
 * 在转场中进入时从 0 开始每帧推进一个阶段，避免切换页面的那一帧一次组合整个页面；不在转场中时直接返回 [stages]。
 */
@Composable
internal fun rememberSetupOutroStage(stages: Int): Int {
    val staged = LocalSetupOutro.current?.request != null
    var stage by remember { mutableIntStateOf(if (staged) 0 else stages) }
    if (stage < stages) {
        LaunchedEffect(Unit) {
            while (stage < stages) {
                withFrameNanos { }
                stage++
                SetupOutroTrace.mark("destination stage $stage")
            }
        }
    }
    return stage
}

/** 完成转场中分帧组合出来的一项：组合出来时按逐项浮现的样式淡入上浮；平时组合出来直接就位。 */
@Composable
internal fun Modifier.setupOutroItemReveal(): Modifier {
    val staged = LocalSetupOutro.current?.request != null
    val progress = remember { Animatable(if (staged) 0f else 1f) }
    // 不在组合里读进度，动画期间只重绘不重组。
    LaunchedEffect(progress) {
        if (progress.value < 1f) progress.animateTo(1f, SetupStaggerSpec)
    }
    return graphicsLayer { setupRevealLayer(progress.value) }
}

@Composable
internal fun rememberSetupOutroController(): SetupOutroController = remember { SetupOutroController() }

/**
 * 引导页、主页面与设置页使用的导航转场：平时与默认转场一致；完成转场切换页面期间不做滑动与压暗，
 * 让目标页面在原地浮现。
 */
internal class SetupOutroAwareTransition(
    private val controller: SetupOutroController,
) : NavTransition by NavTransitions.MiuixDefault {
    override fun Modifier.transformEntry(scope: NavTransitionScope): Modifier =
        if (controller.holdNavigationMotion) {
            this
        } else {
            with(NavTransitions.MiuixDefault) { this@transformEntry.transformEntry(scope) }
        }

    override fun scrimFraction(scope: NavTransitionScope): Float =
        if (controller.holdNavigationMotion) 0f else NavTransitions.MiuixDefault.scrimFraction(scope)
}

/** 转场结束后显露的页面：与引导页内容的逐项浮现同一样式，淡入并上浮 [SetupStaggerOffset]。 */
internal fun Modifier.setupOutroDestination(controller: SetupOutroController): Modifier = graphicsLayer {
    setupRevealLayer(controller.destinationReveal)
}

@Composable
internal fun SetupOutroHost(controller: SetupOutroController) {
    val request = controller.request ?: return
    key(request) {
        SetupOutro(controller = controller, request = request)
    }
}

@Composable
private fun SetupOutro(controller: SetupOutroController, request: SetupOutroRequest) {
    val density = LocalDensity.current
    val palette = rememberIslandLogoPalette()
    val statusBarTop = WindowInsets.statusBars.getTop(density)
    val currentRequest by rememberUpdatedState(request)

    var time by remember { mutableFloatStateOf(0f) }
    var ownCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val blocking by remember { derivedStateOf { time < Outro.REVEAL + Outro.REVEAL_DURATION } }

    LaunchedEffect(Unit) {
        SetupOutroTrace.begin()
        var last = withFrameNanos { it }
        var navigated = false
        while (time < Outro.END) {
            val now = withFrameNanos { it }
            if (now - last > 24_000_000L) {
                SetupOutroTrace.mark("frame gap=${(now - last) / 1_000_000}ms outroTime=$time")
            }
            val delta = ((now - last) / 1_000_000_000f).coerceAtMost(0.05f)
            last = now
            time = minOf(Outro.END, time + delta)
            if (!navigated && time >= Outro.NAVIGATE) {
                navigated = true
                controller.destinationReveal = 0f
                controller.holdNavigationMotion = true
                SetupOutroTrace.span("navigate") { currentRequest.onCovered() }
            }
            if (navigated) {
                controller.destinationReveal = motionTween(time, Outro.REVEAL, Outro.REVEAL_DURATION)
            }
        }
        controller.finish()
        SetupOutroTrace.end()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onPlaced { ownCoordinates = it }
            .then(
                if (blocking) {
                    // 目标页面浮现完成前吞掉触摸，避免误触正在切换的页面；之后不再拦截。
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false).consume()
                            do {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        val width = constraints.maxWidth.toFloat()
        val islandY = SetupStatusIsland.centerY(statusBarTop, density)
        val from = remember(ownCoordinates, request) {
            val origin = ownCoordinates?.takeIf { it.isAttached }?.windowToLocal(Offset.Zero) ?: Offset.Zero
            request.fromInWindow.translate(origin)
        }

        Canvas(Modifier.fillMaxSize()) {
            val t = time
            // 横向与纵向用不同刚度的弹簧，图标的路径自然成一条弧线。
            val moveX = motionSpring(t, Outro.FLY, 0.86f, 150f)
            val moveY = motionSpring(t, Outro.FLY, 0.9f, 95f)
            val resize = motionSpring(t, Outro.FLY, 0.88f, 120f)
            // 归位成首启动画里显示音乐态时的同一个岛，最后两侧收成圆再缩小消失。
            val size = SetupStatusIsland.retract(
                Size(
                    lerp(from.width, SetupStatusIsland.MusicWidth.toPx(), resize),
                    lerp(from.height, SetupStatusIsland.MusicHeight.toPx(), resize),
                ),
                pinch = motionTween(t, Outro.RETREAT, Outro.RETREAT_PINCH),
                vanish = motionTween(t, Outro.RETREAT + Outro.RETREAT_PINCH, Outro.RETREAT_VANISH),
            )
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas
            val center = Offset(
                lerp(from.center.x, width / 2f, moveX),
                lerp(from.center.y, islandY, moveY),
            )
            val rect = Rect(Offset(center.x - w / 2f, center.y - h / 2f), Size(w, h))
            val morph = motionTween(t, Outro.MORPH, Outro.MORPH_DURATION)
            // 骨架在飞行中化开：歌曲信息与歌词胶囊收起；封面一直显示，随化开连续移到超级岛封面的位置。
            drawIslandLogo(
                topLeft = rect.topLeft,
                size = rect.size,
                palette = palette,
                cover = 0f,
                songTitle = 1f - morph,
                songArtist = 1f - morph,
                lyric = 1f - morph,
                lyricAlpha = 1f - morph,
                rim = resize,
            )
            drawIslandMusicState(
                island = rect,
                t = t,
                palette = palette,
                fade = motionTween(t, Outro.MUSIC, 0.25f),
                coverFrom = islandLogoCover(rect),
                coverFromRadius = islandLogoCoverRadius(rect),
                coverDock = morph,
            )
        }
    }
}
