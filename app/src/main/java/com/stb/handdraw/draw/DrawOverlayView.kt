package com.stb.handdraw.draw

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class DrawOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var mirror: Boolean = true
    var imageWidth: Int = 0
    var imageHeight: Int = 0
    var cursor: NormPoint? = null
    var drawing: Boolean = false
    var cursorColor: Int = Color.WHITE
    var skeleton: List<NormPoint>? = null
    var otherTips: List<NormPoint> = emptyList()
    var finished: List<Stroke> = emptyList()
    var active: Stroke? = null

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val otherPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 180, 186, 196)
        style = Paint.Style.FILL
    }
    private val path = Path()
    private val density = resources.displayMetrics.density

    fun refresh() {
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0 || imageWidth <= 0 || imageHeight <= 0) return
        val mapping = FrameMapping(
            viewWidth = width.toFloat(),
            viewHeight = height.toFloat(),
            imageWidth = imageWidth.toFloat(),
            imageHeight = imageHeight.toFloat(),
            mirror = mirror,
        )
        bonePaint.strokeWidth = 2f * density
        val bones = skeleton
        if (bones != null && bones.size >= 21) {
            for ((from, to) in HAND_BONES) {
                val a = mapping.toView(bones[from].x, bones[from].y)
                val b = mapping.toView(bones[to].x, bones[to].y)
                canvas.drawLine(a.x, a.y, b.x, b.y, bonePaint)
            }
        }
        for (stroke in finished) drawStroke(canvas, mapping, stroke)
        active?.let { drawStroke(canvas, mapping, it) }
        val otherRadius = 7f * density
        for (tip in otherTips) {
            val view = mapping.toView(tip.x, tip.y)
            canvas.drawCircle(view.x, view.y, otherRadius, otherPaint)
        }
        val tip = cursor ?: return
        val view = mapping.toView(tip.x, tip.y)
        cursorPaint.color = cursorColor
        val radius = if (drawing) 14f * density else 10f * density
        canvas.drawCircle(view.x, view.y, radius, cursorPaint)
    }

    private fun drawStroke(canvas: Canvas, mapping: FrameMapping, stroke: Stroke) {
        val points = stroke.points
        if (points.size < 2) return
        path.reset()
        val first = mapping.toView(points[0].x, points[0].y)
        path.moveTo(first.x, first.y)
        for (index in 1 until points.size) {
            val point = mapping.toView(points[index].x, points[index].y)
            path.lineTo(point.x, point.y)
        }
        strokePaint.color = stroke.color
        strokePaint.strokeWidth = stroke.widthPx
        canvas.drawPath(path, strokePaint)
    }
}
