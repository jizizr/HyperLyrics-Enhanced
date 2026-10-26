/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.ui.page.setup

import android.util.Log
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.layout
import com.juren233.hyperlyricsenhanced.BuildConfig

/**
 * 基础设置完成转场的分段计时，仅 debug 构建：记录转场期间的掉帧，以及切换到主页面那一帧里
 * 各部分的组合、测量与绘制耗时。时间均相对点“确定”开始转场的时刻。
 */
internal object SetupOutroTrace {
    private const val TAG = "HLESetupOutro"

    @Volatile
    private var originNanos = 0L

    val active: Boolean get() = BuildConfig.DEBUG && originNanos != 0L

    fun begin() {
        if (!BuildConfig.DEBUG) return
        originNanos = System.nanoTime()
        Log.d(TAG, "begin")
    }

    fun end() {
        if (!active) return
        mark("end")
        originNanos = 0L
    }

    fun mark(label: String) {
        if (active) Log.d(TAG, "+${ms(System.nanoTime() - originNanos)} $label")
    }

    inline fun <T> span(label: String, block: () -> T): T {
        if (!active) return block()
        val start = System.nanoTime()
        val result = block()
        spanDone(label, start)
        return result
    }

    @PublishedApi
    internal fun spanDone(label: String, startNanos: Long) {
        if (active) {
            Log.d(TAG, "+${ms(startNanos - originNanos)} $label took=${ms(System.nanoTime() - startNanos)}")
        }
    }

    private fun ms(nanos: Long): String = "%.1fms".format(nanos / 1_000_000f)
}

/** 转场期间记录这一节点（含子节点）的测量与绘制耗时；非 debug 构建不加任何修饰。 */
internal fun Modifier.setupOutroTraceLayout(label: String): Modifier =
    if (!BuildConfig.DEBUG) {
        this
    } else {
        this
            .layout { measurable, constraints ->
                val placeable = SetupOutroTrace.span("$label measure") { measurable.measure(constraints) }
                layout(placeable.width, placeable.height) {
                    SetupOutroTrace.span("$label place") { placeable.place(0, 0) }
                }
            }
            .drawWithContent { SetupOutroTrace.span("$label draw") { drawContent() } }
    }
