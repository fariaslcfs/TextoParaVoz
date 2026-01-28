package com.example.handwritingtospeech

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.google.mlkit.vision.digitalink.recognition.Ink
import kotlin.math.abs

class HandwritingView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val TOUCH_TOLERANCE = 4f
    private val WORD_PAUSE_THRESHOLD = 400L
    private val MAX_STROKES = 20

    private var lastX = 0f
    private var lastY = 0f
    private var lastTime = 0L
    private var lastStrokeEndTime = 0L

    private val paint = Paint().apply {
        color = Color.BLACK
        strokeWidth = 10f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val path = Path()
    private var inkBuilder = Ink.builder()
    private var strokeBuilder = Ink.Stroke.builder()

    override fun onTouchEvent(event: MotionEvent): Boolean {

        val x = event.x
        val y = event.y

        var t = event.eventTime
        if (t <= lastTime) t = lastTime + 1
        lastTime = t

        when (event.action) {

            MotionEvent.ACTION_DOWN -> {

                /*if (lastStrokeEndTime != 0L) {
                    val pause = t - lastStrokeEndTime
                    if (pause > WORD_PAUSE_THRESHOLD) {
                        inkBuilder.addStroke(
                            Ink.Stroke.builder().build()
                        )
                    }
                }*/

                path.moveTo(x, y)
                lastX = x
                lastY = y

                strokeBuilder = Ink.Stroke.builder()
                strokeBuilder.addPoint(Ink.Point.create(x, y, t))
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = abs(x - lastX)
                val dy = abs(y - lastY)

                if (dx >= TOUCH_TOLERANCE || dy >= TOUCH_TOLERANCE) {
                    path.lineTo(x, y)
                    strokeBuilder.addPoint(Ink.Point.create(x, y, t))
                    lastX = x
                    lastY = y
                }
            }

            MotionEvent.ACTION_UP -> {
                strokeBuilder.addPoint(Ink.Point.create(x, y, t))
                inkBuilder.addStroke(strokeBuilder.build())
                lastStrokeEndTime = t

                if (inkBuilder.build().strokes.size > MAX_STROKES) {
                    inkBuilder = Ink.builder()
                    path.reset()
                }
            }
        }

        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(path, paint)
    }

    fun getInk(): Ink = inkBuilder.build()

    fun clear() {
        path.reset()
        inkBuilder = Ink.builder()
        strokeBuilder = Ink.Stroke.builder()
        lastStrokeEndTime = 0L
        lastTime = 0L
        invalidate()
    }
}
