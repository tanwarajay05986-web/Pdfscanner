package com.akay.pdfscanner

import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// ---------- PDF writer: JPEG seedha PDF mein (chhoti file) ----------
fun buildPdf(files: List<File>, out: OutputStream) {
    val offsets = ArrayList<Long>()
    var pos = 0L
    fun w(s: String) {
        val b = s.toByteArray(Charsets.ISO_8859_1)
        out.write(b)
        pos += b.size
    }
    fun wb(b: ByteArray) {
        out.write(b)
        pos += b.size
    }
    val n = files.size
    w("%PDF-1.4\n")
    offsets.add(pos)
    w("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
    offsets.add(pos)
    val kids = (0 until n).joinToString(" ") { "${3 + 3 * it} 0 R" }
    w("2 0 obj\n<< /Type /Pages /Kids [$kids] /Count $n >>\nendobj\n")
    for (i in 0 until n) {
        val opts = BitmapFactory.Options()
        opts.inJustDecodeBounds = true
        BitmapFactory.decodeFile(files[i].absolutePath, opts)
        val iw = opts.outWidth
        val ih = opts.outHeight
        if (iw <= 0 || ih <= 0) throw IllegalStateException("Bad image")
        val pw = 595f
        val ph = pw * ih / iw
        val jpg = files[i].readBytes()
        val po = 3 + 3 * i
        offsets.add(pos)
        w("$po 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $pw $ph] /Resources << /XObject << /Im0 ${po + 2} 0 R >> >> /Contents ${po + 1} 0 R >>\nendobj\n")
        val content = "q $pw 0 0 $ph 0 0 cm /Im0 Do Q"
        offsets.add(pos)
        w("${po + 1} 0 obj\n<< /Length ${content.length} >>\nstream\n$content\nendstream\nendobj\n")
        offsets.add(pos)
        w("${po + 2} 0 obj\n<< /Type /XObject /Subtype /Image /Width $iw /Height $ih /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpg.size} >>\nstream\n")
        wb(jpg)
        w("\nendstream\nendobj\n")
    }
    val xrefPos = pos
    w("xref\n0 ${offsets.size + 1}\n")
    w("0000000000 65535 f \n")
    for (o in offsets) {
        w(String.format(Locale.US, "%010d 00000 n \n", o))
    }
    w("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xrefPos\n%%EOF\n")
    out.flush()
}

// ---------- Crop view: 8 blue handles ----------
class CropView(context: Context) : View(context) {
    var bitmap: Bitmap? = null
    var filter: ColorFilter? = null
    var bottomReserve = 0f
    val crop = RectF(0f, 0f, 1f, 1f)

    private val density = resources.displayMetrics.density
    private val hr = 13f * density
    private val img = RectF()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG)
    private var active = -1

    init {
        dimPaint.color = Color.argb(150, 0, 0, 0)
        linePaint.color = Color.parseColor("#4DA3FF")
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = 2f * density
        handleFill.color = Color.argb(200, 214, 238, 255)
        handleRing.color = Color.parseColor("#4DA3FF")
        handleRing.style = Paint.Style.STROKE
        handleRing.strokeWidth = 2.5f * density
    }

    private fun computeImgRect(bm: Bitmap) {
        val pad = hr + 6f * density
        val aw = width - 2 * pad
        val ah = height - 2 * pad - bottomReserve
        val s = minOf(aw / bm.width, ah / bm.height)
        val w = bm.width * s
        val h = bm.height * s
        val cx = width / 2f
        val cy = (height - bottomReserve) / 2f
        img.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }

    // order: 0 TL, 1 T, 2 TR, 3 R, 4 BR, 5 B, 6 BL, 7 L
    private fun handlePoints(): FloatArray {
        val l = img.left + crop.left * img.width()
        val r = img.left + crop.right * img.width()
        val t = img.top + crop.top * img.height()
        val b = img.top + crop.bottom * img.height()
        val mx = (l + r) / 2f
        val my = (t + b) / 2f
        return floatArrayOf(l, t, mx, t, r, t, r, my, r, b, mx, b, l, b, l, my)
    }

    override fun onDraw(canvas: Canvas) {
        val bm = bitmap ?: return
        computeImgRect(bm)
        bmpPaint.colorFilter = filter
        canvas.drawBitmap(bm, null, img, bmpPaint)

        val l = img.left + crop.left * img.width()
        val r = img.left + crop.right * img.width()
        val t = img.top + crop.top * img.height()
        val b = img.top + crop.bottom * img.height()
        canvas.drawRect(img.left, img.top, img.right, t, dimPaint)
        canvas.drawRect(img.left, b, img.right, img.bottom, dimPaint)
        canvas.drawRect(img.left, t, l, b, dimPaint)
        canvas.drawRect(r, t, img.right, b, dimPaint)
        canvas.drawRect(l, t, r, b, linePaint)

        val p = handlePoints()
        for (k in 0 until 8) {
            canvas.drawCircle(p[2 * k], p[2 * k + 1], hr, handleFill)
            canvas.drawCircle(p[2 * k], p[2 * k + 1], hr, handleRing)
        }
    }

    private fun hit(x: Float, y: Float): Int {
        val p = handlePoints()
        var best = -1
        var bd = 44f * density
        for (k in 0 until 8) {
            val d = hypot(x - p[2 * k], y - p[2 * k + 1])
            if (d < bd) {
                bd = d
                best = k
            }
        }
        return best
    }

    private fun move(x: Float, y: Float) {
        val nx = ((x - img.left) / img.width()).coerceIn(0f, 1f)
        val ny = ((y - img.top) / img.height()).coerceIn(0f, 1f)
        val m = 0.08f
        if (active == 0 || active == 6 || active == 7) crop.left = minOf(nx, crop.right - m)
        if (active == 2 || active == 3 || active == 4) crop.right = maxOf(nx, crop.left + m)
        if (active == 0 || active == 1 || active == 2) crop.top = minOf(ny, crop.bottom - m)
        if (active == 4 || active == 5 || active == 6) crop.bottom = maxOf(ny, crop.top + m)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val bm = bitmap ?: return false
        computeImgRect(bm)
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                active = hit(e.x, e.y)
                return active >= 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (active >= 0) {
                    move(e.x, e.y)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> active = -1
        }
        return true
    }

    fun resetCrop() {
        crop.set(0f, 0f, 1f, 1f)
        invalidate()
    }
}

// ---------- Transparent round icons: 0 = bin, 1 = crop, 2 = rotate ----------
class IconView(context: Context, private val kind: Int) : View(context) {
    private val d = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    init {
        bg.color = Color.argb(115, 0, 0, 0)
        line.color = Color.argb(240, 255, 255, 255)
        line.style = Paint.Style.STROKE
        line.strokeWidth = 2.2f * d
        line.strokeCap = Paint.Cap.ROUND
        line.strokeJoin = Paint.Join.ROUND
        solid.color = line.color
        solid.style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height).toFloat()
        val ox = (width - s) / 2f
        val oy = (height - s) / 2f
        canvas.drawCircle(width / 2f, height / 2f, s / 2f, bg)
        fun fx(f: Float): Float = ox + f * s
        fun fy(f: Float): Float = oy + f * s
        when (kind) {
            0 -> {
                canvas.drawLine(fx(0.28f), fy(0.33f), fx(0.72f), fy(0.33f), line)
                path.reset()
                path.moveTo(fx(0.42f), fy(0.33f))
                path.lineTo(fx(0.42f), fy(0.26f))
                path.lineTo(fx(0.58f), fy(0.26f))
                path.lineTo(fx(0.58f), fy(0.33f))
                canvas.drawPath(path, line)
                path.reset()
                path.moveTo(fx(0.33f), fy(0.39f))
                path.lineTo(fx(0.37f), fy(0.74f))
                path.lineTo(fx(0.63f), fy(0.74f))
                path.lineTo(fx(0.67f), fy(0.39f))
                canvas.drawPath(path, line)
                canvas.drawLine(fx(0.46f), fy(0.47f), fx(0.46f), fy(0.66f), line)
                canvas.drawLine(fx(0.54f), fy(0.47f), fx(0.54f), fy(0.66f), line)
            }
            1 -> {
                path.reset()
                path.moveTo(fx(0.30f), fy(0.14f))
                path.lineTo(fx(0.30f), fy(0.70f))
                path.lineTo(fx(0.86f), fy(0.70f))
                canvas.drawPath(path, line)
                path.reset()
                path.moveTo(fx(0.14f), fy(0.30f))
                path.lineTo(fx(0.70f), fy(0.30f))
                path.lineTo(fx(0.70f), fy(0.86f))
                canvas.drawPath(path, line)
            }
            else -> {
                val r = 0.26f * s
                val cx = fx(0.5f)
                val cy = fy(0.5f)
                canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -70f, 290f, false, line)
                val a = Math.toRadians(220.0)
                val px = cx + r * cos(a).toFloat()
                val py = cy + r * sin(a).toFloat()
                val dx = (-sin(a)).toFloat()
                val dy = cos(a).toFloat()
                val nx = -dy
                val ny = dx
                val hl = 0.14f * s
                val hw = 0.09f * s
                path.reset()
                path.moveTo(px + dx * hl, py + dy * hl)
                path.lineTo(px + nx * hw, py + ny * hw)
                path.lineTo(px - nx * hw, py - ny * hw)
                path.close()
                canvas.drawPath(path, solid)
            }
        }
    }
}

class MainActivity : ComponentActivity() {

    private val AUTH = "com.akay.pdfscanner.fileprovider"
    private val DARK = Color.parseColor("#1C1C1C")
    private val GREEN = Color.parseColor("#2E9E5B")
    private val BLUE = Color.parseColor("#0288D1")
    private val BLUE_H = Color.parseColor("#4DA3FF")
    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

    private val pages = ArrayList<File>()
    private val edited = ArrayList<File>()
    private val cropRects = ArrayList<RectF>()
    private val filterNames = listOf(
        "Original", "Magic Color", "Magic White", "B&W", "Grayscale", "Lighten", "Vivid"
    )
    private val filterFrames = ArrayList<FrameLayout>()
    private val filterLabels = ArrayList<TextView>()
    private var filterIdx = 0
    private var cropIndex = 0
    private var editIndex = 0
    private var cropView: CropView? = null
    private var busy = false

    private var scanName = ""
    private var nameInput: EditText? = null
    private var statusView: TextView? = null

    private var screen = "camera"
    private var pending = 0
    private var torchOn = false
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var previewView: PreviewView? = null
    private var thumbBox: FrameLayout? = null
    private var thumbImg: ImageView? = null
    private var thumbBadge: TextView? = null
    private var proceedBadge: TextView? = null
    private val worker = Executors.newSingleThreadExecutor()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        if (ok) startCamera() else toast("Camera permission chahiye. Phone Settings mein allow karo.")
    }

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) importUris(uris)
    }

    // ---------- helpers ----------

    private fun toast(m: String) {
        Toast.makeText(this, m, Toast.LENGTH_LONG).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

    private fun lp(w: Int, h: Int, weight: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight)

    private fun rounded(color: Int, radius: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = dp(radius).toFloat()
        return d
    }

    private fun oval(color: Int): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(color)
        return d
    }

    private fun badge(): TextView {
        val t = TextView(this)
        t.textSize = 12f
        t.setTextColor(Color.WHITE)
        t.setTypeface(null, Typeface.BOLD)
        t.gravity = Gravity.CENTER
        t.background = oval(Color.parseColor("#C62828"))
        return t
    }

    private fun label(text: String, size: Float, color: Int): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(color)
        return t
    }

    private fun iconText(text: String, size: Float, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(Color.WHITE)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(16), dp(10), dp(16), dp(10))
        t.setOnClickListener { onClick() }
        return t
    }

    private fun pill(text: String, color: Int, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 16f
        t.setTextColor(Color.WHITE)
        t.setTypeface(null, Typeface.BOLD)
        t.gravity = Gravity.CENTER
        t.background = rounded(color, 28)
        t.setOnClickListener { onClick() }
        return t
    }

    private fun tool(text: String, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 13f
        t.setTextColor(Color.WHITE)
        t.setTypeface(null, Typeface.BOLD)
        t.gravity = Gravity.CENTER
        t.background = rounded(Color.parseColor("#3A3A3A"), 14)
        t.setOnClickListener { onClick() }
        return t
    }

    private fun arrow(text: String, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 24f
        t.setTextColor(Color.WHITE)
        t.gravity = Gravity.CENTER
        t.background = oval(Color.argb(150, 0, 0, 0))
        t.setOnClickListener { onClick() }
        return t
    }

    private fun pagesDir(): File {
        val d = File(filesDir, "pages")
        d.mkdirs()
        return d
    }

    private fun exifDegrees(ei: ExifInterface): Int {
        return when (ei.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }

    private fun importFromUri(uri: Uri): File? {
        return try {
            val deg = try {
                contentResolver.openInputStream(uri)?.use { exifDegrees(ExifInterface(it)) } ?: 0
            } catch (e: Throwable) {
                0
            }
            val opts = BitmapFactory.Options()
            opts.inJustDecodeBounds = true
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            var sample = 1
            val maxSide = maxOf(opts.outWidth, opts.outHeight)
            while (maxSide / sample > 2400) sample *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = sample
            var bmp = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, o2)
            } ?: return null
            if (deg != 0) {
                val m = Matrix()
                m.postRotate(deg.toFloat())
                val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                bmp.recycle()
                bmp = r
            }
            val f = File(pagesDir(), "p_${System.nanoTime()}.jpg")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bmp.recycle()
            f
        } catch (e: Throwable) {
            null
        }
    }

    private fun thumb(f: File, maxSide: Int): Bitmap? {
        return try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeFile(f.absolutePath, o)
            var s = 1
            while (maxOf(o.outWidth, o.outHeight) / s > maxSide) s *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = s
            BitmapFactory.decodeFile(f.absolutePath, o2)
        } catch (e: Throwable) {
            null
        }
    }

    // ---------- filters ----------

    private fun contrastMatrix(c: Float, brightness: Float): ColorMatrix {
        val t = (-0.5f * c + 0.5f) * 255f + brightness
        return ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }

    private fun filterMatrix(i: Int): ColorMatrix? {
        return when (i) {
            1 -> {
                val m = ColorMatrix()
                m.setSaturation(1.2f)
                m.postConcat(contrastMatrix(1.3f, 12f))
                m
            }
            2 -> {
                val m = ColorMatrix()
                m.setSaturation(0.25f)
                m.postConcat(contrastMatrix(1.8f, 45f))
                m
            }
            3 -> {
                val m = ColorMatrix()
                m.setSaturation(0f)
                m.postConcat(contrastMatrix(2.8f, 25f))
                m
            }
            4 -> {
                val m = ColorMatrix()
                m.setSaturation(0f)
                m
            }
            5 -> contrastMatrix(1.1f, 45f)
            6 -> {
                val m = ColorMatrix()
                m.setSaturation(1.8f)
                m.postConcat(contrastMatrix(1.15f, 0f))
                m
            }
            else -> null
        }
    }

    private fun filterFilter(i: Int): ColorFilter? {
        val m = filterMatrix(i) ?: return null
        return ColorMatrixColorFilter(m)
    }

    private fun applyEdit(src: File, rect: RectF, fi: Int, dst: File) {
        val bmp = BitmapFactory.decodeFile(src.absolutePath)
            ?: throw IllegalStateException("Image load nahi hui")
        val x = (rect.left * bmp.width).toInt().coerceIn(0, bmp.width - 1)
        val y = (rect.top * bmp.height).toInt().coerceIn(0, bmp.height - 1)
        val w = ((rect.right - rect.left) * bmp.width).toInt().coerceIn(1, bmp.width - x)
        val h = ((rect.bottom - rect.top) * bmp.height).toInt().coerceIn(1, bmp.height - y)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val cf = filterFilter(fi)
        if (cf != null) paint.colorFilter = cf
        canvas.drawBitmap(bmp, Rect(x, y, x + w, y + h), Rect(0, 0, w, h), paint)
        FileOutputStream(dst).use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        out.recycle()
    }

    // ---------- camera ----------

    private fun startCamera() {
        val pv = previewView ?: return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(pv.surfaceProvider)
                val ic = ImageCapture.Builder().build()
                imageCapture = ic
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, ic)
                camera?.cameraControl?.enableTorch(torchOn)
            } catch (e: Throwable) {
                toast("Camera error: $e")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        try {
            ProcessCameraProvider.getInstance(this).get().unbindAll()
        } catch (e: Throwable) {
        }
        camera = null
        imageCapture = null
    }

    private fun toggleTorch(btn: TextView) {
        val cam = camera
        if (cam == null) {
            toast("Camera abhi ready nahi hai")
            return
        }
        if (!cam.cameraInfo.hasFlashUnit()) {
            toast("Is phone mein flash nahi hai")
            return
        }
        torchOn = !torchOn
        cam.cameraControl.enableTorch(torchOn)
        btn.text = if (torchOn) "Flash ON" else "Flash OFF"
        btn.setTextColor(if (torchOn) Color.YELLOW else Color.WHITE)
    }

    private fun takePhoto() {
        val ic = imageCapture
        if (ic == null) {
            toast("Camera abhi ready nahi hai")
            return
        }
        val raw = File(pagesDir(), "raw_${System.nanoTime()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(raw).build()
        pending += 1
        ic.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    worker.execute {
                        val f = importFromUri(Uri.fromFile(raw))
                        raw.delete()
                        runOnUiThread {
                            pending -= 1
                            if (f != null) pages.add(f) else toast("Photo save nahi ho payi")
                            if (screen == "camera") refreshOverlay()
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    pending -= 1
                    toast("Capture error: ${exception.message}")
                }
            }
        )
    }

    private fun importUris(uris: List<Uri>) {
        pending += 1
        worker.execute {
            val added = ArrayList<File>()
            for (u in uris) {
                val f = importFromUri(u)
                if (f != null) added.add(f)
            }
            runOnUiThread {
                pages.addAll(added)
                pending -= 1
                if (screen == "camera") refreshOverlay()
                if (added.size < uris.size) toast("Kuch images load nahi ho payi")
            }
        }
    }

    private fun removeLast() {
        if (pages.isNotEmpty()) {
            pages.removeAt(pages.size - 1).delete()
            refreshOverlay()
        }
    }

    private fun refreshOverlay() {
        val n = pages.size
        thumbBox?.visibility = if (n == 0) View.GONE else View.VISIBLE
        proceedBadge?.visibility = if (n == 0) View.GONE else View.VISIBLE
        if (n > 0) {
            thumbImg?.setImageBitmap(thumb(pages[n - 1], 300))
            thumbBadge?.text = n.toString()
            proceedBadge?.text = n.toString()
        }
    }

    private fun proceed() {
        if (pending > 0) {
            toast("Ruko, photo process ho rahi hai...")
            return
        }
        if (pages.isEmpty()) {
            toast("Pehle kam se kam 1 page capture karo")
            return
        }
        cropRects.clear()
        for (p in pages) cropRects.add(RectF(0f, 0f, 1f, 1f))
        filterIdx = 0
        showCrop(0)
    }

    // ---------- crop + filters (Stage 2) ----------

    private fun updateFilterSelection() {
        for (i in filterFrames.indices) {
            val d = GradientDrawable()
            d.cornerRadius = dp(10).toFloat()
            d.setColor(Color.parseColor("#2A2A2A"))
            if (i == filterIdx) d.setStroke(dp(3), BLUE_H)
            filterFrames[i].background = d
            filterLabels[i].setTextColor(
                if (i == filterIdx) Color.WHITE else Color.parseColor("#9E9E9E")
            )
        }
    }

    private fun selectFilter(i: Int) {
        filterIdx = i
        cropView?.filter = filterFilter(i)
        cropView?.invalidate()
        updateFilterSelection()
    }

    private fun buildFilterStrip(tb: Bitmap?): View {
        filterFrames.clear()
        filterLabels.clear()
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(dp(10), dp(8), dp(10), dp(8))
        for (i in filterNames.indices) {
            val item = LinearLayout(this)
            item.orientation = LinearLayout.VERTICAL
            item.gravity = Gravity.CENTER_HORIZONTAL
            val frame = FrameLayout(this)
            frame.setPadding(dp(3), dp(3), dp(3), dp(3))
            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            if (tb != null) iv.setImageBitmap(tb)
            val cf = filterFilter(i)
            if (cf != null) iv.setColorFilter(cf)
            frame.addView(iv, FrameLayout.LayoutParams(dp(64), dp(64)))
            item.addView(frame, lp(WRAP, WRAP))
            val lb = label(filterNames[i], 11f, Color.parseColor("#9E9E9E"))
            lb.setPadding(0, dp(4), 0, 0)
            item.addView(lb, lp(WRAP, WRAP))
            item.setOnClickListener { selectFilter(i) }
            row.addView(item, lp(dp(84), WRAP))
            filterFrames.add(frame)
            filterLabels.add(lb)
        }
        hs.addView(row)
        updateFilterSelection()
        return hs
    }

    private fun showCrop(i: Int) {
        screen = "crop"
        cropIndex = i
        stopCamera()
        val n = pages.size
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#121212"))

        val head = LinearLayout(this)
        head.orientation = LinearLayout.VERTICAL
        head.gravity = Gravity.CENTER_HORIZONTAL
        head.setPadding(0, dp(28), 0, dp(8))
        head.addView(label("Crop", 22f, Color.WHITE))
        head.addView(label("Page ${i + 1} of $n", 14f, Color.parseColor("#9E9E9E")))
        root.addView(head, lp(MATCH, WRAP))

        val cv = CropView(this)
        cv.bottomReserve = 60f * resources.displayMetrics.density
        cv.bitmap = thumb(pages[i], 1600)
        cv.crop.set(cropRects[i])
        cv.filter = filterFilter(filterIdx)
        cropView = cv

        val area = FrameLayout(this)
        area.addView(cv, FrameLayout.LayoutParams(MATCH, MATCH))

        val icons = LinearLayout(this)
        icons.orientation = LinearLayout.HORIZONTAL
        icons.gravity = Gravity.CENTER_VERTICAL
        icons.setPadding(dp(18), 0, dp(18), dp(8))
        val binIcon = IconView(this, 0)
        binIcon.setOnClickListener { deleteInCrop(i) }
        icons.addView(binIcon, lp(dp(46), dp(46)))
        icons.addView(View(this), lp(0, 1, 1f))
        val cropIcon = IconView(this, 1)
        cropIcon.setOnClickListener { applyCropNow(i) }
        val cl = lp(dp(46), dp(46))
        cl.rightMargin = dp(14)
        icons.addView(cropIcon, cl)
        val rotIcon = IconView(this, 2)
        rotIcon.setOnClickListener { rotateInCrop(i) }
        icons.addView(rotIcon, lp(dp(46), dp(46)))
        area.addView(icons, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        root.addView(area, lp(MATCH, 0, 1f))

        root.addView(buildFilterStrip(thumb(pages[i], 160)), lp(MATCH, dp(118)))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(16), dp(8), dp(16), dp(20))
        bar.addView(pill("Back", Color.parseColor("#3A3A3A")) { cropBack() }, lp(dp(96), dp(52)))
        bar.addView(View(this), lp(0, 1, 1f))
        val reset = iconText("Reset crop", 15f) { cropView?.resetCrop() }
        bar.addView(reset, lp(WRAP, WRAP))
        bar.addView(View(this), lp(0, 1, 1f))
        bar.addView(pill("Next", GREEN) { cropNext() }, lp(dp(110), dp(56)))
        root.addView(bar)
        setContentView(root)
    }

    private fun deleteInCrop(i: Int) {
        if (busy) return
        pages[i].delete()
        pages.removeAt(i)
        if (i < cropRects.size) cropRects.removeAt(i)
        toast("Page deleted")
        if (pages.isEmpty()) showCamera() else showCrop(minOf(i, pages.size - 1))
    }

    private fun rotateInCrop(i: Int) {
        if (busy) return
        val cv = cropView ?: return
        val c = RectF(cv.crop)
        busy = true
        val f = pages[i]
        worker.execute {
            var err: Throwable? = null
            try {
                val bmp = BitmapFactory.decodeFile(f.absolutePath)
                    ?: throw IllegalStateException("Image load failed")
                val m = Matrix()
                m.postRotate(90f)
                val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                FileOutputStream(f).use { r.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                bmp.recycle()
                r.recycle()
            } catch (e: Throwable) {
                err = e
            }
            runOnUiThread {
                busy = false
                if (err != null) {
                    cropRects[i].set(c)
                    toast("Rotate error: $err")
                } else {
                    cropRects[i].set(1f - c.bottom, c.left, 1f - c.top, c.right)
                }
                if (screen == "crop") showCrop(i)
            }
        }
    }

    private fun applyCropNow(i: Int) {
        if (busy) return
        val cv = cropView ?: return
        val r = RectF(cv.crop)
        if (r.left <= 0.001f && r.top <= 0.001f && r.right >= 0.999f && r.bottom >= 0.999f) {
            toast("Drag the blue dots to select the crop area first")
            return
        }
        busy = true
        val src = pages[i]
        val dst = File(pagesDir(), "p_${System.nanoTime()}.jpg")
        worker.execute {
            var err: Throwable? = null
            try {
                applyEdit(src, r, 0, dst)
            } catch (e: Throwable) {
                err = e
            }
            runOnUiThread {
                busy = false
                if (err != null) {
                    dst.delete()
                    cropRects[i].set(r)
                    toast("Crop error: $err")
                } else {
                    src.delete()
                    pages[i] = dst
                    cropRects[i].set(0f, 0f, 1f, 1f)
                }
                if (screen == "crop") showCrop(i)
            }
        }
    }

    private fun cropNext() {
        if (busy) return
        cropView?.let { cropRects[cropIndex].set(it.crop) }
        if (cropIndex < pages.size - 1) showCrop(cropIndex + 1) else applyAll()
    }

    private fun cropBack() {
        if (busy) return
        cropView?.let { cropRects[cropIndex].set(it.crop) }
        if (cropIndex > 0) showCrop(cropIndex - 1) else showCamera()
    }

    private fun applyAll() {
        if (busy) return
        busy = true
        toast("Processing...")
        val srcs = pages.toList()
        val rects = cropRects.map { RectF(it) }
        val fi = filterIdx
        worker.execute {
            val out = ArrayList<File>()
            var err: Throwable? = null
            try {
                for (k in srcs.indices) {
                    val dst = File(pagesDir(), "e_${System.nanoTime()}.jpg")
                    applyEdit(srcs[k], rects[k], fi, dst)
                    out.add(dst)
                }
            } catch (e: Throwable) {
                err = e
            }
            runOnUiThread {
                busy = false
                if (err != null) {
                    for (f in out) f.delete()
                    toast("Process error: $err")
                } else {
                    for (f in edited) f.delete()
                    edited.clear()
                    edited.addAll(out)
                    showEdit(0)
                }
            }
        }
    }

    // ---------- rotate / re-crop / delete (Stage 3) ----------

    private fun showEdit(i: Int) {
        screen = "edit"
        editIndex = i
        val n = edited.size
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#121212"))

        val head = LinearLayout(this)
        head.orientation = LinearLayout.VERTICAL
        head.gravity = Gravity.CENTER_HORIZONTAL
        head.setPadding(0, dp(28), 0, dp(8))
        head.addView(label("Edit", 22f, Color.WHITE))
        head.addView(label("Page ${i + 1} of $n", 14f, Color.parseColor("#9E9E9E")))
        root.addView(head, lp(MATCH, WRAP))

        val area = FrameLayout(this)
        val iv = ImageView(this)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.setPadding(dp(12), dp(8), dp(12), dp(8))
        iv.setImageBitmap(thumb(edited[i], 1600))
        var downX = 0f
        iv.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                downX = e.x
            } else if (e.action == MotionEvent.ACTION_UP) {
                val dx = e.x - downX
                if (dx > dp(80) && i > 0) {
                    showEdit(i - 1)
                } else if (dx < -dp(80) && i < n - 1) {
                    showEdit(i + 1)
                }
            }
            true
        }
        area.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
        if (i > 0) {
            val a = FrameLayout.LayoutParams(dp(44), dp(44), Gravity.START or Gravity.CENTER_VERTICAL)
            a.leftMargin = dp(6)
            area.addView(arrow("<") { showEdit(i - 1) }, a)
        }
        if (i < n - 1) {
            val a = FrameLayout.LayoutParams(dp(44), dp(44), Gravity.END or Gravity.CENTER_VERTICAL)
            a.rightMargin = dp(6)
            area.addView(arrow(">") { showEdit(i + 1) }, a)
        }
        root.addView(area, lp(MATCH, 0, 1f))

        val tools = LinearLayout(this)
        tools.orientation = LinearLayout.HORIZONTAL
        tools.setPadding(dp(12), dp(8), dp(12), dp(4))
        val names = listOf("Rotate L", "Rotate R", "Crop", "Delete")
        for (k in names.indices) {
            val b = tool(names[k]) {
                when (k) {
                    0 -> rotateEdited(i, -90f)
                    1 -> rotateEdited(i, 90f)
                    2 -> showRecrop(i)
                    else -> deleteEdited(i)
                }
            }
            if (k == 3) b.setTextColor(Color.parseColor("#FF8A80"))
            val l = lp(0, dp(48), 1f)
            l.leftMargin = dp(4)
            l.rightMargin = dp(4)
            tools.addView(b, l)
        }
        root.addView(tools, lp(MATCH, WRAP))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(16), dp(8), dp(16), dp(20))
        bar.addView(pill("Back", Color.parseColor("#3A3A3A")) { showCrop(0) }, lp(dp(96), dp(52)))
        bar.addView(View(this), lp(0, 1, 1f))
        bar.addView(pill("Next", GREEN) { showNext() }, lp(dp(110), dp(56)))
        root.addView(bar)
        setContentView(root)
    }

    private fun rotateEdited(i: Int, deg: Float) {
        if (busy) return
        busy = true
        val f = edited[i]
        worker.execute {
            var err: Throwable? = null
            try {
                val bmp = BitmapFactory.decodeFile(f.absolutePath)
                    ?: throw IllegalStateException("Image load nahi hui")
                val m = Matrix()
                m.postRotate(deg)
                val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                FileOutputStream(f).use { r.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                bmp.recycle()
                r.recycle()
            } catch (e: Throwable) {
                err = e
            }
            runOnUiThread {
                busy = false
                if (err != null) toast("Rotate error: $err")
                if (screen == "edit") showEdit(i)
            }
        }
    }

    private fun deleteEdited(i: Int) {
        if (busy) return
        edited.removeAt(i).delete()
        if (i < pages.size) pages.removeAt(i).delete()
        if (i < cropRects.size) cropRects.removeAt(i)
        if (edited.isEmpty()) showCamera() else showEdit(minOf(i, edited.size - 1))
    }

    private fun showRecrop(i: Int) {
        screen = "recrop"
        editIndex = i
        val n = edited.size
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#121212"))

        val head = LinearLayout(this)
        head.orientation = LinearLayout.VERTICAL
        head.gravity = Gravity.CENTER_HORIZONTAL
        head.setPadding(0, dp(28), 0, dp(8))
        head.addView(label("Crop", 22f, Color.WHITE))
        head.addView(label("Page ${i + 1} of $n", 14f, Color.parseColor("#9E9E9E")))
        root.addView(head, lp(MATCH, WRAP))

        val cv = CropView(this)
        cv.bitmap = thumb(edited[i], 1600)
        cropView = cv
        root.addView(cv, lp(MATCH, 0, 1f))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(16), dp(8), dp(16), dp(20))
        bar.addView(pill("Cancel", Color.parseColor("#3A3A3A")) { showEdit(i) }, lp(dp(104), dp(52)))
        bar.addView(View(this), lp(0, 1, 1f))
        bar.addView(iconText("Reset crop", 15f) { cropView?.resetCrop() }, lp(WRAP, WRAP))
        bar.addView(View(this), lp(0, 1, 1f))
        bar.addView(pill("Done", GREEN) { applyRecrop(i) }, lp(dp(110), dp(56)))
        root.addView(bar)
        setContentView(root)
    }

    private fun applyRecrop(i: Int) {
        if (busy) return
        val cv = cropView ?: return
        val rect = RectF(cv.crop)
        busy = true
        val src = edited[i]
        val d = File(pagesDir(), "e_${System.nanoTime()}.jpg")
        worker.execute {
            var err: Throwable? = null
            try {
                applyEdit(src, rect, 0, d)
            } catch (e: Throwable) {
                err = e
            }
            runOnUiThread {
                busy = false
                if (err != null) {
                    d.delete()
                    toast("Crop error: $err")
                } else {
                    src.delete()
                    edited[i] = d
                }
                showEdit(i)
            }
        }
    }

    // ---------- Final screen (Stage 4): name, Save as PDF / JPG, Share ----------

    private fun baseName(): String {
        var n = (nameInput?.text?.toString() ?: scanName).trim()
        n = n.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        n = n.removeSuffix(".pdf").removeSuffix(".PDF").removeSuffix(".jpg").removeSuffix(".JPG")
        if (n.isEmpty()) n = "Scan_" + stamp()
        return n
    }

    private fun jpgName(base: String, k: Int, total: Int): String =
        if (total == 1) "$base.jpg" else "${base}_${k + 1}.jpg"

    private fun setStatus(s: String) {
        statusView?.text = s
    }

    private fun resetAll() {
        for (f in pages) f.delete()
        for (f in edited) f.delete()
        pages.clear()
        edited.clear()
        cropRects.clear()
        filterIdx = 0
        scanName = ""
        File(cacheDir, "share").deleteRecursively()
    }

    private fun showNext() {
        screen = "next"
        if (scanName.isEmpty()) scanName = "Scan_" + stamp()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#F3F9FF"))

        val head = LinearLayout(this)
        head.orientation = LinearLayout.VERTICAL
        head.setBackgroundColor(BLUE)
        head.setPadding(dp(24), dp(40), dp(24), dp(18))
        head.addView(label("PDF is ready", 24f, Color.WHITE))
        head.addView(label("${edited.size} page(s)", 14f, Color.parseColor("#D6EEFF")))
        root.addView(head, lp(MATCH, WRAP))

        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(20), dp(16), dp(20), dp(16))

        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        for (p in edited) {
            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setPadding(dp(2), dp(2), dp(2), dp(2))
            iv.background = rounded(Color.WHITE, 8)
            iv.setImageBitmap(thumb(p, 300))
            val l = lp(dp(96), dp(132))
            l.rightMargin = dp(8)
            row.addView(iv, l)
        }
        hs.addView(row)
        body.addView(hs)

        val nl = label("File name", 13f, Color.parseColor("#455A64"))
        nl.setPadding(0, dp(18), 0, dp(6))
        body.addView(nl)

        val et = EditText(this)
        et.setText(scanName)
        et.setSingleLine(true)
        et.textSize = 16f
        et.setTextColor(Color.parseColor("#01579B"))
        et.setPadding(dp(14), dp(12), dp(14), dp(12))
        et.background = rounded(Color.WHITE, 12)
        nameInput = et
        body.addView(et, lp(MATCH, WRAP))

        val acts = LinearLayout(this)
        acts.orientation = LinearLayout.HORIZONTAL
        val a1 = lp(0, dp(58), 1f)
        a1.rightMargin = dp(6)
        acts.addView(pill("Save as PDF", GREEN) { doSave(true) }, a1)
        val a2 = lp(0, dp(58), 1f)
        a2.leftMargin = dp(6)
        acts.addView(pill("Save as JPG", BLUE) { doSave(false) }, a2)
        val alp = lp(MATCH, WRAP)
        alp.topMargin = dp(22)
        body.addView(acts, alp)

        val share = TextView(this)
        share.text = "Share"
        share.textSize = 17f
        share.setTextColor(BLUE)
        share.setTypeface(null, Typeface.BOLD)
        share.gravity = Gravity.CENTER
        val sd = GradientDrawable()
        sd.cornerRadius = dp(28).toFloat()
        sd.setColor(Color.WHITE)
        sd.setStroke(dp(2), BLUE)
        share.background = sd
        share.setOnClickListener { askShare() }
        val slp = lp(MATCH, dp(58))
        slp.topMargin = dp(12)
        body.addView(share, slp)

        val st = label("Tap Save as PDF or Save as JPG to save on your phone.", 13f, Color.parseColor("#455A64"))
        st.setPadding(0, dp(14), 0, 0)
        statusView = st
        body.addView(st)

        val sv = ScrollView(this)
        sv.addView(body)
        root.addView(sv, lp(MATCH, 0, 1f))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(16), dp(8), dp(16), dp(20))
        bar.addView(pill("Back", Color.parseColor("#78909C")) { leaveFinal() }, lp(dp(96), dp(52)))
        bar.addView(View(this), lp(0, 1, 1f))
        bar.addView(pill("Done", BLUE) {
            resetAll()
            showCamera()
        }, lp(dp(110), dp(52)))
        root.addView(bar)
        setContentView(root)
    }

    private fun leaveFinal() {
        scanName = baseName()
        showEdit(0)
    }

    private fun askShare() {
        AlertDialog.Builder(this)
            .setTitle("Share as")
            .setItems(arrayOf("PDF", "JPG")) { _, which -> doShare(which == 0) }
            .show()
    }

    private fun doShare(asPdf: Boolean) {
        if (busy) return
        busy = true
        val base = baseName()
        scanName = base
        val files = edited.toList()
        setStatus("Preparing to share...")
        worker.execute {
            var uris: ArrayList<Uri>? = null
            var err: Throwable? = null
            try {
                val dir = File(cacheDir, "share")
                dir.deleteRecursively()
                dir.mkdirs()
                val list = ArrayList<Uri>()
                if (asPdf) {
                    val f = File(dir, "$base.pdf")
                    FileOutputStream(f).buffered().use { buildPdf(files, it) }
                    list.add(FileProvider.getUriForFile(this, AUTH, f))
                } else {
                    for (k in files.indices) {
                        val f = File(dir, jpgName(base, k, files.size))
                        files[k].copyTo(f, true)
                        list.add(FileProvider.getUriForFile(this, AUTH, f))
                    }
                }
                uris = list
            } catch (e: Throwable) {
                err = e
            }
            val res = uris
            val er = err
            runOnUiThread {
                busy = false
                if (er != null || res == null) {
                    setStatus("Share error: $er")
                    toast("Share error: $er")
                } else {
                    setStatus("")
                    try {
                        val i: Intent
                        if (res.size == 1) {
                            i = Intent(Intent.ACTION_SEND)
                            i.putExtra(Intent.EXTRA_STREAM, res[0])
                        } else {
                            i = Intent(Intent.ACTION_SEND_MULTIPLE)
                            i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, res)
                        }
                        i.type = if (asPdf) "application/pdf" else "image/*"
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        startActivity(Intent.createChooser(i, "Share"))
                    } catch (e: Throwable) {
                        toast("Share error: $e")
                    }
                }
            }
        }
    }

    private fun doSave(asPdf: Boolean) {
        if (busy) return
        busy = true
        val base = baseName()
        scanName = base
        val files = edited.toList()
        setStatus("Saving...")
        worker.execute {
            var msg = ""
            var err: Throwable? = null
            try {
                if (asPdf) {
                    val values = ContentValues()
                    values.put(MediaStore.Downloads.DISPLAY_NAME, "$base.pdf")
                    values.put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    values.put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/PDFScanner"
                    )
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("Could not create file")
                    val os = contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("Could not open file")
                    os.buffered().use { buildPdf(files, it) }
                    msg = "PDF saved: Downloads/PDFScanner/$base.pdf"
                } else {
                    for (k in files.indices) {
                        val values = ContentValues()
                        values.put(MediaStore.Images.Media.DISPLAY_NAME, jpgName(base, k, files.size))
                        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        values.put(
                            MediaStore.Images.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_PICTURES + "/PDFScanner"
                        )
                        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                            ?: throw IllegalStateException("Could not create file")
                        val os = contentResolver.openOutputStream(uri)
                            ?: throw IllegalStateException("Could not open file")
                        os.use { out -> files[k].inputStream().use { it.copyTo(out) } }
                    }
                    msg = "JPG saved: Pictures/PDFScanner"
                }
            } catch (e: Throwable) {
                err = e
            }
            val er = err
            val ok = msg
            runOnUiThread {
                busy = false
                if (er != null) {
                    setStatus("Save error: $er")
                    toast("Save error: $er")
                } else {
                    setStatus(ok)
                    toast(ok)
                }
            }
        }
    }

    // ---------- camera screen ----------

    private fun showCamera() {
        screen = "camera"
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(DARK)

        val top = FrameLayout(this)
        top.setPadding(dp(8), dp(24), dp(8), dp(4))
        top.addView(
            iconText("X", 22f) { finish() },
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.START or Gravity.CENTER_VERTICAL)
        )
        val flash = iconText(if (torchOn) "Flash ON" else "Flash OFF", 15f) { }
        flash.setTextColor(if (torchOn) Color.YELLOW else Color.WHITE)
        flash.setOnClickListener { toggleTorch(flash) }
        top.addView(
            flash,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL)
        )
        root.addView(top, lp(MATCH, dp(76)))

        val container = FrameLayout(this)
        val pv = PreviewView(this)
        pv.scaleType = PreviewView.ScaleType.FIT_CENTER
        previewView = pv
        container.addView(pv, FrameLayout.LayoutParams(MATCH, MATCH))

        val tb = FrameLayout(this)
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        img.setPadding(dp(3), dp(3), dp(3), dp(3))
        img.background = rounded(Color.WHITE, 12)
        thumbImg = img
        tb.addView(img, FrameLayout.LayoutParams(dp(84), dp(104), Gravity.BOTTOM or Gravity.START))
        val tbBadge = badge()
        thumbBadge = tbBadge
        tb.addView(tbBadge, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.START))
        val x = TextView(this)
        x.text = "X"
        x.textSize = 12f
        x.setTextColor(Color.BLACK)
        x.setTypeface(null, Typeface.BOLD)
        x.gravity = Gravity.CENTER
        x.background = oval(Color.WHITE)
        x.setOnClickListener { removeLast() }
        tb.addView(x, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP or Gravity.END))
        thumbBox = tb
        val tlp = FrameLayout.LayoutParams(dp(96), dp(116), Gravity.BOTTOM or Gravity.START)
        tlp.setMargins(dp(10), 0, 0, dp(10))
        container.addView(tb, tlp)
        root.addView(container, lp(MATCH, 0, 1f))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(16), dp(12), dp(16), dp(20))

        val gallery = TextView(this)
        gallery.text = "Gallery"
        gallery.textSize = 12f
        gallery.setTextColor(Color.WHITE)
        gallery.gravity = Gravity.CENTER
        gallery.background = rounded(Color.parseColor("#3A3A3A"), 16)
        gallery.setOnClickListener { galleryLauncher.launch("image/*") }
        bar.addView(gallery, lp(dp(72), dp(72)))

        bar.addView(View(this), lp(0, 1, 1f))

        val cap = View(this)
        val capBg = GradientDrawable()
        capBg.shape = GradientDrawable.OVAL
        capBg.setColor(Color.WHITE)
        capBg.setStroke(dp(5), Color.parseColor("#9E9E9E"))
        cap.background = capBg
        cap.setOnClickListener { takePhoto() }
        bar.addView(cap, lp(dp(76), dp(76)))

        bar.addView(View(this), lp(0, 1, 1f))

        val pw = FrameLayout(this)
        val pbtn = TextView(this)
        pbtn.text = "Proceed"
        pbtn.textSize = 15f
        pbtn.setTextColor(Color.WHITE)
        pbtn.setTypeface(null, Typeface.BOLD)
        pbtn.gravity = Gravity.CENTER
        pbtn.background = rounded(GREEN, 32)
        pbtn.setOnClickListener { proceed() }
        pw.addView(pbtn, FrameLayout.LayoutParams(dp(112), dp(64), Gravity.CENTER))
        val pb = badge()
        proceedBadge = pb
        pw.addView(pb, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END))
        bar.addView(pw, lp(dp(124), dp(80)))

        root.addView(bar)
        setContentView(root)
        refreshOverlay()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun showError(e: Throwable) {
        val tv = TextView(this)
        tv.text = "ERROR (screenshot bhejo):\n\n" + android.util.Log.getStackTraceString(e)
        tv.textSize = 11f
        tv.setPadding(24, 90, 24, 24)
        val sv = ScrollView(this)
        sv.addView(tv)
        setContentView(sv)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pagesDir().listFiles()?.forEach { it.delete() }
        File(cacheDir, "share").deleteRecursively()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (screen) {
                    "camera" -> finish()
                    "crop" -> cropBack()
                    "edit" -> showCrop(0)
                    "recrop" -> showEdit(editIndex)
                    "next" -> leaveFinal()
                    else -> showCamera()
                }
            }
        })
        try {
            showCamera()
        } catch (e: Throwable) {
            showError(e)
        }
    }
}
