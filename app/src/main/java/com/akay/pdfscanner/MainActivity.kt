package com.akay.pdfscanner

import android.Manifest
import android.content.Context
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
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
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
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.math.hypot

// ---------- Crop view: 8 blue handles ----------
class CropView(context: Context) : View(context) {
    var bitmap: Bitmap? = null
    var filter: ColorFilter? = null
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
        val ah = height - 2 * pad
        val s = minOf(aw / bm.width, ah / bm.height)
        val w = bm.width * s
        val h = bm.height * s
        val cx = width / 2f
        val cy = height / 2f
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

class MainActivity : ComponentActivity() {

    private val DARK = Color.parseColor("#1C1C1C")
    private val GREEN = Color.parseColor("#2E9E5B")
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
    private var cropView: CropView? = null
    private var busy = false

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

    // ---------- crop + filters ----------

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
        cv.bitmap = thumb(pages[i], 1600)
        cv.crop.set(cropRects[i])
        cv.filter = filterFilter(filterIdx)
        cropView = cv
        root.addView(cv, lp(MATCH, 0, 1f))

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
                    showNext()
                }
            }
        }
    }

    // ---------- screens ----------

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

    // Stage 2 ka end screen: Stage 3 mein yahan Rotate + Crop review aayega
    private fun showNext() {
        screen = "next"
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#F3F9FF"))
        root.setPadding(dp(24), dp(60), dp(24), dp(24))

        val t = TextView(this)
        t.text = "Stage 2 pass"
        t.textSize = 26f
        t.setTypeface(null, Typeface.BOLD)
        t.setTextColor(Color.parseColor("#01579B"))
        root.addView(t)

        val c = TextView(this)
        c.text = "${edited.size} page(s) crop + filter ke saath tayyar hain. Rotate aur review agle stage mein aayega."
        c.textSize = 15f
        c.setTextColor(Color.parseColor("#455A64"))
        c.setPadding(0, dp(8), 0, dp(16))
        root.addView(c)

        val hs = HorizontalScrollView(this)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        for (p in edited) {
            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setImageBitmap(thumb(p, 300))
            val l = lp(dp(100), dp(140))
            l.rightMargin = dp(8)
            row.addView(iv, l)
        }
        hs.addView(row)
        root.addView(hs)

        val back = TextView(this)
        back.text = "Back (Crop par)"
        back.textSize = 16f
        back.setTextColor(Color.WHITE)
        back.gravity = Gravity.CENTER
        back.background = rounded(Color.parseColor("#0288D1"), 14)
        back.setOnClickListener { showCrop(0) }
        val bl = lp(MATCH, dp(56))
        bl.topMargin = dp(24)
        root.addView(back, bl)
        setContentView(root)
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
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (screen) {
                    "camera" -> finish()
                    "crop" -> cropBack()
                    "next" -> showCrop(0)
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
