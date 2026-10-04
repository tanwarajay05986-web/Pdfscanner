package com.akay.pdfscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// ---------- Crop view: free 4-corner crop with 8 blue handles ----------
class CropView(context: Context) : View(context) {
    var bitmap: Bitmap? = null
    var filter: ColorFilter? = null
    // corners: TL, TR, BR, BL (normalized 0..1)
    val pts = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)

    private val density = resources.displayMetrics.density
    private val hr = 13f * density
    private val img = RectF()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outer = Path()
    private val quad = Path()
    private var active = -1
    private var lastX = 0f
    private var lastY = 0f

    init {
        dimPaint.color = Color.argb(150, 0, 0, 0)
        linePaint.color = Color.parseColor("#4DA3FF")
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = 2f * density
        linePaint.strokeJoin = Paint.Join.ROUND
        handleFill.color = Color.argb(200, 214, 238, 255)
        handleRing.color = Color.parseColor("#4DA3FF")
        handleRing.style = Paint.Style.STROKE
        handleRing.strokeWidth = 2.5f * density
    }

    fun setPoints(p: FloatArray) {
        for (i in 0 until 8) pts[i] = p[i]
        invalidate()
    }

    fun resetCrop() {
        setPoints(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f))
    }

    private fun computeImgRect(bm: Bitmap) {
        val pad = hr + 6f * density
        val aw = width - 2 * pad
        val ah = height - 2 * pad
        val s = minOf(aw / bm.width, ah / bm.height)
        val w = bm.width * s
        val h = bm.height * s
        val cx = width / 2f
        val cy = height / 2f
        img.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }

    private fun px(i: Int): Float = img.left + pts[2 * i] * img.width()
    private fun py(i: Int): Float = img.top + pts[2 * i + 1] * img.height()

    // handles 0..3 = corners, 4 = top edge, 5 = right edge, 6 = bottom edge, 7 = left edge
    private fun hx(k: Int): Float = if (k < 4) px(k) else (px(k - 4) + px((k - 3) % 4)) / 2f
    private fun hy(k: Int): Float = if (k < 4) py(k) else (py(k - 4) + py((k - 3) % 4)) / 2f

    override fun onDraw(canvas: Canvas) {
        val bm = bitmap ?: return
        computeImgRect(bm)
        bmpPaint.colorFilter = filter
        canvas.drawBitmap(bm, null, img, bmpPaint)

        quad.reset()
        quad.moveTo(px(0), py(0))
        quad.lineTo(px(1), py(1))
        quad.lineTo(px(2), py(2))
        quad.lineTo(px(3), py(3))
        quad.close()
        outer.reset()
        outer.fillType = Path.FillType.EVEN_ODD
        outer.addRect(img, Path.Direction.CW)
        outer.addPath(quad)
        canvas.drawPath(outer, dimPaint)
        canvas.drawPath(quad, linePaint)

        for (k in 0 until 8) {
            canvas.drawCircle(hx(k), hy(k), hr, handleFill)
            canvas.drawCircle(hx(k), hy(k), hr, handleRing)
        }
    }

    private fun drag(x: Float, y: Float) {
        if (active < 4) {
            pts[2 * active] = ((x - img.left) / img.width()).coerceIn(0f, 1f)
            pts[2 * active + 1] = ((y - img.top) / img.height()).coerceIn(0f, 1f)
        } else {
            val i = active - 4
            val j = (active - 3) % 4
            val ex = px(j) - px(i)
            val ey = py(j) - py(i)
            val len = hypot(ex, ey)
            if (len < 1f) return
            val nx = -ey / len
            val ny = ex / len
            val proj = (x - lastX) * nx + (y - lastY) * ny
            val dx = proj * nx / img.width()
            val dy = proj * ny / img.height()
            for (idx in intArrayOf(i, j)) {
                pts[2 * idx] = (pts[2 * idx] + dx).coerceIn(0f, 1f)
                pts[2 * idx + 1] = (pts[2 * idx + 1] + dy).coerceIn(0f, 1f)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val bm = bitmap ?: return false
        computeImgRect(bm)
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                var best = -1
                var bd = 44f * density
                for (k in 0 until 8) {
                    val d = hypot(e.x - hx(k), e.y - hy(k))
                    if (d < bd) {
                        bd = d
                        best = k
                    }
                }
                active = best
                lastX = e.x
                lastY = e.y
                return best >= 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (active >= 0) {
                    drag(e.x, e.y)
                    lastX = e.x
                    lastY = e.y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> active = -1
        }
        return true
    }
}

// ---------- Camera overlay: detected document box, ID guide corners, hint pill ----------
class DetectView(context: Context) : View(context) {
    private var quad: FloatArray? = null
    private var aspect = 0.75f
    private var hint = ""
    var showFrame = false
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frameP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    init {
        fill.color = Color.argb(70, 30, 120, 255)
        stroke.color = Color.parseColor("#2979FF")
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = 3f * d
        stroke.strokeJoin = Paint.Join.ROUND
        frameP.color = Color.parseColor("#38B6FF")
        frameP.style = Paint.Style.STROKE
        frameP.strokeWidth = 4.5f * d
        frameP.strokeCap = Paint.Cap.ROUND
        frameP.strokeJoin = Paint.Join.ROUND
        pillPaint.color = Color.argb(190, 40, 40, 40)
        textPaint.color = Color.WHITE
        textPaint.textSize = 18f * d
        textPaint.typeface = Typeface.DEFAULT_BOLD
    }

    fun update(q: FloatArray?, a: Float, h: String) {
        quad = q
        aspect = a
        hint = h
        invalidate()
    }

    private fun drawFrame(canvas: Canvas, ox: Float, oy: Float, rw: Float, rh: Float) {
        val fw = rw * 0.82f
        val fh = fw / 1.586f
        val l = ox + (rw - fw) / 2f
        val t = oy + (rh - fh) / 2f
        val r = l + fw
        val b = t + fh
        val len = fw * 0.14f
        val rad = fw * 0.05f
        path.reset()
        path.moveTo(l, t + len)
        path.lineTo(l, t + rad)
        path.quadTo(l, t, l + rad, t)
        path.lineTo(l + len, t)
        canvas.drawPath(path, frameP)
        path.reset()
        path.moveTo(r - len, t)
        path.lineTo(r - rad, t)
        path.quadTo(r, t, r, t + rad)
        path.lineTo(r, t + len)
        canvas.drawPath(path, frameP)
        path.reset()
        path.moveTo(r, b - len)
        path.lineTo(r, b - rad)
        path.quadTo(r, b, r - rad, b)
        path.lineTo(r - len, b)
        canvas.drawPath(path, frameP)
        path.reset()
        path.moveTo(l + len, b)
        path.lineTo(l + rad, b)
        path.quadTo(l, b, l, b - rad)
        path.lineTo(l, b - len)
        canvas.drawPath(path, frameP)
    }

    override fun onDraw(canvas: Canvas) {
        val cw = width.toFloat()
        val ch = height.toFloat()
        var rw = cw
        var rh = rw / aspect
        if (rh > ch) {
            rh = ch
            rw = rh * aspect
        }
        val ox = (cw - rw) / 2f
        val oy = (ch - rh) / 2f
        if (showFrame) drawFrame(canvas, ox, oy, rw, rh)
        val q = quad
        if (q != null) {
            path.reset()
            path.moveTo(ox + q[0] * rw, oy + q[1] * rh)
            for (k in 1 until 4) path.lineTo(ox + q[2 * k] * rw, oy + q[2 * k + 1] * rh)
            path.close()
            canvas.drawPath(path, fill)
            canvas.drawPath(path, stroke)
        }
        if (hint.isNotEmpty()) {
            val tw = textPaint.measureText(hint)
            val padX = 22f * d
            val padY = 14f * d
            val pillH = textPaint.textSize + 2 * padY
            val left = (cw - tw) / 2f - padX
            val top = oy + rh - pillH - 36f * d
            val rect = RectF(left, top, left + tw + 2 * padX, top + pillH)
            canvas.drawRoundRect(rect, pillH / 2f, pillH / 2f, pillPaint)
            canvas.drawText(hint, left + padX, top + padY + textPaint.textSize * 0.82f, textPaint)
        }
    }
}

// ---------- Shutter button: white ring + white disc ----------
class ShutterView(context: Context) : View(context) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        ring.color = Color.WHITE
        ring.style = Paint.Style.STROKE
        inner.color = Color.WHITE
    }

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height).toFloat()
        val c = s / 2f
        ring.strokeWidth = 0.07f * s
        canvas.drawCircle(c, c, s / 2f - ring.strokeWidth / 2f, ring)
        canvas.drawCircle(c, c, s / 2f - 0.17f * s, inner)
    }
}

// ---------- Icons ----------
// 0 bin, 1 rotate, 2 image, 3 scan corners, 4 id card, 5 home, 6 download, 7 document,
// 8 share, 9 bolt, 10 check, 11 clock, 12 cloud-download, 13 chevron-left, 14 close
class IconView(
    context: Context,
    private val kind: Int,
    private val withBg: Boolean = false,
    glyph: Int = Color.WHITE,
    private val strokeF: Float = 0.062f
) : View(context) {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    init {
        bg.color = Color.argb(115, 0, 0, 0)
        line.color = glyph
        line.style = Paint.Style.STROKE
        line.strokeCap = Paint.Cap.ROUND
        line.strokeJoin = Paint.Join.ROUND
        solid.color = glyph
        solid.style = Paint.Style.FILL
    }

    fun setGlyph(c: Int) {
        line.color = c
        solid.color = c
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height).toFloat()
        val ox = (width - s) / 2f
        val oy = (height - s) / 2f
        line.strokeWidth = strokeF * s
        if (withBg) canvas.drawCircle(width / 2f, height / 2f, s / 2f, bg)

        fun poly(close: Boolean, vararg v: Float) {
            path.reset()
            path.moveTo(ox + v[0] * s, oy + v[1] * s)
            var k = 2
            while (k < v.size) {
                path.lineTo(ox + v[k] * s, oy + v[k + 1] * s)
                k += 2
            }
            if (close) path.close()
            canvas.drawPath(path, line)
        }

        fun circ(cx: Float, cy: Float, r: Float) {
            canvas.drawCircle(ox + cx * s, oy + cy * s, r * s, line)
        }

        fun rrect(l: Float, t: Float, r: Float, b: Float, rad: Float) {
            canvas.drawRoundRect(
                RectF(ox + l * s, oy + t * s, ox + r * s, oy + b * s), rad * s, rad * s, line
            )
        }

        when (kind) {
            0 -> {
                poly(false, 0.28f, 0.33f, 0.72f, 0.33f)
                poly(false, 0.42f, 0.33f, 0.42f, 0.26f, 0.58f, 0.26f, 0.58f, 0.33f)
                poly(false, 0.33f, 0.39f, 0.37f, 0.74f, 0.63f, 0.74f, 0.67f, 0.39f)
                poly(false, 0.46f, 0.47f, 0.46f, 0.66f)
                poly(false, 0.54f, 0.47f, 0.54f, 0.66f)
            }
            1 -> {
                val r = 0.30f * s
                val cx = ox + 0.5f * s
                val cy = oy + 0.5f * s
                canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -70f, 290f, false, line)
                val a = Math.toRadians(220.0)
                val px = cx + r * cos(a).toFloat()
                val py = cy + r * sin(a).toFloat()
                val dx = (-sin(a)).toFloat()
                val dy = cos(a).toFloat()
                val nx = -dy
                val ny = dx
                val hl = 0.16f * s
                val hw = 0.10f * s
                path.reset()
                path.moveTo(px + dx * hl, py + dy * hl)
                path.lineTo(px + nx * hw, py + ny * hw)
                path.lineTo(px - nx * hw, py - ny * hw)
                path.close()
                canvas.drawPath(path, solid)
            }
            2 -> {
                rrect(0.18f, 0.20f, 0.82f, 0.80f, 0.09f)
                canvas.drawCircle(ox + 0.36f * s, oy + 0.38f * s, 0.055f * s, solid)
                poly(false, 0.20f, 0.74f, 0.42f, 0.50f, 0.56f, 0.64f, 0.66f, 0.54f, 0.80f, 0.70f)
            }
            3 -> {
                poly(false, 0.20f, 0.40f, 0.20f, 0.22f, 0.40f, 0.22f)
                poly(false, 0.60f, 0.22f, 0.80f, 0.22f, 0.80f, 0.40f)
                poly(false, 0.80f, 0.60f, 0.80f, 0.78f, 0.60f, 0.78f)
                poly(false, 0.40f, 0.78f, 0.20f, 0.78f, 0.20f, 0.60f)
            }
            4 -> {
                rrect(0.22f, 0.24f, 0.78f, 0.80f, 0.08f)
                poly(false, 0.38f, 0.15f, 0.38f, 0.30f)
                poly(false, 0.62f, 0.15f, 0.62f, 0.30f)
                circ(0.5f, 0.46f, 0.07f)
                poly(false, 0.36f, 0.68f, 0.64f, 0.68f)
            }
            5 -> {
                poly(false, 0.16f, 0.48f, 0.50f, 0.20f, 0.84f, 0.48f)
                poly(false, 0.27f, 0.42f, 0.27f, 0.80f, 0.73f, 0.80f, 0.73f, 0.42f)
                poly(false, 0.44f, 0.80f, 0.44f, 0.60f, 0.56f, 0.60f, 0.56f, 0.80f)
            }
            6 -> {
                poly(false, 0.50f, 0.18f, 0.50f, 0.60f)
                poly(false, 0.34f, 0.45f, 0.50f, 0.61f, 0.66f, 0.45f)
                poly(false, 0.20f, 0.62f, 0.20f, 0.80f, 0.80f, 0.80f, 0.80f, 0.62f)
            }
            7 -> {
                poly(true, 0.30f, 0.20f, 0.58f, 0.20f, 0.72f, 0.34f, 0.72f, 0.80f, 0.30f, 0.80f)
                poly(false, 0.58f, 0.20f, 0.58f, 0.34f, 0.72f, 0.34f)
                poly(false, 0.38f, 0.52f, 0.64f, 0.52f)
                poly(false, 0.38f, 0.64f, 0.64f, 0.64f)
            }
            8 -> {
                circ(0.28f, 0.50f, 0.075f)
                circ(0.72f, 0.26f, 0.075f)
                circ(0.72f, 0.74f, 0.075f)
                poly(false, 0.35f, 0.46f, 0.65f, 0.30f)
                poly(false, 0.35f, 0.54f, 0.65f, 0.70f)
            }
            9 -> {
                poly(true, 0.58f, 0.12f, 0.28f, 0.54f, 0.48f, 0.54f, 0.42f, 0.88f, 0.72f, 0.44f, 0.52f, 0.44f)
            }
            10 -> {
                poly(false, 0.26f, 0.52f, 0.43f, 0.68f, 0.75f, 0.34f)
            }
            11 -> {
                circ(0.5f, 0.5f, 0.32f)
                poly(false, 0.5f, 0.5f, 0.5f, 0.30f)
                poly(false, 0.5f, 0.5f, 0.64f, 0.58f)
            }
            12 -> {
                path.reset()
                path.moveTo(ox + 0.30f * s, oy + 0.68f * s)
                path.cubicTo(ox + 0.10f * s, oy + 0.68f * s, ox + 0.10f * s, oy + 0.42f * s, ox + 0.32f * s, oy + 0.42f * s)
                path.cubicTo(ox + 0.34f * s, oy + 0.20f * s, ox + 0.68f * s, oy + 0.20f * s, ox + 0.70f * s, oy + 0.44f * s)
                path.cubicTo(ox + 0.90f * s, oy + 0.44f * s, ox + 0.90f * s, oy + 0.68f * s, ox + 0.72f * s, oy + 0.68f * s)
                canvas.drawPath(path, line)
                poly(false, 0.5f, 0.46f, 0.5f, 0.76f)
                poly(false, 0.40f, 0.66f, 0.5f, 0.76f, 0.60f, 0.66f)
            }
            13 -> {
                poly(false, 0.62f, 0.22f, 0.38f, 0.50f, 0.62f, 0.78f)
            }
            else -> {
                poly(false, 0.28f, 0.28f, 0.72f, 0.72f)
                poly(false, 0.72f, 0.28f, 0.28f, 0.72f)
            }
        }
    }
}
