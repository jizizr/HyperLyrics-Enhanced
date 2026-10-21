package com.juren233.hyperlyricsenhanced.root.island

import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import java.util.WeakHashMap

internal data class IslandCoverPivot(
    val x: Float,
    val y: Float,
)

internal object IslandAlbumCoverRotationGeometry {
    fun centeredPivot(width: Int, height: Int): IslandCoverPivot? {
        if (width <= 0 || height <= 0) return null
        return IslandCoverPivot(width / 2f, height / 2f)
    }
}

internal object IslandAlbumCoverRotationController {
    private const val TAG = "IslandAlbumCoverRotation"
    private const val ROTATION_DURATION_MS = 20_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val states = WeakHashMap<ImageView, RotationState>()

    @Volatile
    private var playbackActive = true

    private val attachStateListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            val imageView = view as? ImageView ?: return
            states[imageView]?.let {
                ensureCenteredPivot(imageView)
                startIfNeeded(imageView, it)
            }
        }

        override fun onViewDetachedFromWindow(view: View) {
            val imageView = view as? ImageView ?: return
            states[imageView]?.let { stopAnimator(imageView, it, resetRotation = true) }
        }
    }

    private val layoutChangeListener = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
        val imageView = view as? ImageView ?: return@OnLayoutChangeListener
        if (states.containsKey(imageView)) {
            ensureCenteredPivot(imageView)
        }
    }

    fun attach(view: ImageView) {
        runOnMain {
            val state = states.getOrPut(view) {
                view.addOnAttachStateChangeListener(attachStateListener)
                view.addOnLayoutChangeListener(layoutChangeListener)
                RotationState()
            }
            ensureCenteredPivot(view)
            startIfNeeded(view, state)
        }
    }

    fun detach(view: ImageView) {
        runOnMain {
            val state = states.remove(view) ?: return@runOnMain
            view.removeOnAttachStateChangeListener(attachStateListener)
            view.removeOnLayoutChangeListener(layoutChangeListener)
            stopAnimator(view, state, resetRotation = true)
        }
    }

    fun setPlaybackActive(active: Boolean) {
        if (playbackActive == active) return
        playbackActive = active
        runOnMain {
            states.toList().forEach { (view, state) ->
                if (active) {
                    startIfNeeded(view, state)
                } else {
                    state.animator?.takeIf { it.isStarted && !it.isPaused }?.pause()
                }
            }
        }
    }

    fun cleanup() {
        runOnMain {
            states.toList().forEach { (view, state) ->
                view.removeOnAttachStateChangeListener(attachStateListener)
                view.removeOnLayoutChangeListener(layoutChangeListener)
                stopAnimator(view, state, resetRotation = true)
            }
            states.clear()
        }
    }

    private fun startIfNeeded(view: ImageView, state: RotationState) {
        if (!playbackActive || !view.isAttachedToWindow) return

        ensureCenteredPivot(view)

        val existing = state.animator
        if (existing != null) {
            if (existing.isPaused) existing.resume()
            return
        }

        state.animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = ROTATION_DURATION_MS
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            // 岛收起/被遮挡时 isShown 为 false：继续写 rotation 只会让宿主重复合成，
            // 看不到任何视觉变化。恢复可见后的下一帧再写回当前角度。
            addUpdateListener { animation ->
                state.angle = animation.animatedValue as Float
                if (!view.isShown) {
                    state.hiddenFrames += 1
                    if (BuildConfig.DEBUG && state.hiddenFrames == 300L) {
                        HookLogger.d(
                            TAG,
                            "旋转封面不可见，已跳过 ${state.hiddenFrames} 帧旋转写入: " +
                                "view=${System.identityHashCode(view)}",
                        )
                    }
                    return@addUpdateListener
                }
                state.hiddenFrames = 0
                view.rotation = state.angle
            }
            start()
        }
    }

    private fun stopAnimator(
        view: ImageView,
        state: RotationState,
        resetRotation: Boolean
    ) {
        state.animator?.cancel()
        state.animator = null
        state.hiddenFrames = 0
        if (resetRotation) {
            state.angle = 0f
            view.rotation = 0f
        }
    }

    private fun ensureCenteredPivot(view: ImageView) {
        val width = view.width
        val height = view.height
        val pivot = IslandAlbumCoverRotationGeometry.centeredPivot(width, height) ?: return
        if (view.pivotX == pivot.x && view.pivotY == pivot.y) return
        view.pivotX = pivot.x
        view.pivotY = pivot.y
        if (BuildConfig.DEBUG) {
            HookLogger.d(
                TAG,
                "旋转封面锚点已校正: view=${System.identityHashCode(view)}, " +
                    "pivot=${pivot.x}x${pivot.y}, size=${width}x${height}",
            )
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private data class RotationState(
        var animator: ValueAnimator? = null,
        var angle: Float = 0f,
        var hiddenFrames: Long = 0L,
    )
}
