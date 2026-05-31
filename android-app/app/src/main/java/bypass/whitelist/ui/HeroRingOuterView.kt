package bypass.whitelist.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import bypass.whitelist.util.UiColors
import bypass.whitelist.R

class HeroRingOuterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    enum class State { IDLE, CONNECTING, CONNECTED }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val matrix = Matrix()
    private var sweepRotationDegrees = 0f
    private var spinAnimator: ValueAnimator? = null
    private var currentState: State = State.IDLE

    private val accent: Int get() = UiColors.accent(context)
    private val accentSoft: Int get() = UiColors.accentSoft(context)
    private val transparent = 0

    private var cachedGradient: SweepGradient? = null
    private var cachedGradientState: State? = null
    private var cachedGradientWidth = 0
    private var cachedGradientHeight = 0

    init {
        applyState(State.IDLE)
    }

    fun applyState(state: State) {
        if (currentState == state) return
        currentState = state
        when (state) {
            State.IDLE -> {
                stopSpin()
                visibility = GONE
                setBlur(0f)
            }
            State.CONNECTING -> {
                visibility = VISIBLE
                setBlur(2f)
                startSpin(durationMs = 6_000L)
            }
            State.CONNECTED -> {
                visibility = VISIBLE
                setBlur(0.5f)
                startSpin(durationMs = 18_000L)
            }
        }
        invalidate()
    }

    private fun startSpin(durationMs: Long) {
        spinAnimator?.cancel()
        spinAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = durationMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                sweepRotationDegrees = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopSpin() {
        spinAnimator?.cancel()
        spinAnimator = null
        sweepRotationDegrees = 0f
    }

    private fun setBlur(radiusPx: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setRenderEffect(
                if (radiusPx > 0f) RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)
                else null
            )
        }
    }

    fun release() {
        stopSpin()
    }

    override fun onDetachedFromWindow() {
        stopSpin()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (currentState == State.IDLE) return
        val cx = width / 2f
        val cy = height / 2f
        val radius = (minOf(width, height) / 2f) - 1f
        val gradient = obtainGradient(cx, cy) ?: return
        matrix.reset()
        matrix.postRotate(sweepRotationDegrees, cx, cy)
        gradient.setLocalMatrix(matrix)
        paint.shader = gradient
        paint.alpha = if (currentState == State.CONNECTED) (0.9f * 255).toInt() else 255
        canvas.drawCircle(cx, cy, radius, paint)
    }

    private fun obtainGradient(cx: Float, cy: Float): SweepGradient? {
        val cached = cachedGradient
        if (cached != null &&
            cachedGradientState == currentState &&
            cachedGradientWidth == width &&
            cachedGradientHeight == height
        ) {
            return cached
        }
        val fresh = when (currentState) {
            State.CONNECTING -> SweepGradient(cx, cy, intArrayOf(accentSoft, transparent, accentSoft), floatArrayOf(0f, 0.5f, 1f))
            State.CONNECTED -> SweepGradient(cx, cy, intArrayOf(transparent, accent, accent, transparent), floatArrayOf(0f, 0.3f, 0.7f, 1f))
            State.IDLE -> return null
        }
        cachedGradient = fresh
        cachedGradientState = currentState
        cachedGradientWidth = width
        cachedGradientHeight = height
        return fresh
    }
}
