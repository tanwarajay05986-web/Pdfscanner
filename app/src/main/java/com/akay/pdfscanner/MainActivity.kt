package com.akay.pdfscanner

import android.Manifest
import android.app.AlertDialog
import android.content.ContentUris
import android.content.ContentValues
import android.content.DialogInterface
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
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.TextUtils
import android.view.Gravity
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
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot

class PdfItem(val uri: Uri, val name: String, val dateSec: Long, val size: Long)

class MainActivity : ComponentActivity() {

    private val AUTH = "com.akay.pdfscanner.fileprovider"
    private val DARK = Color.parseColor("#1C1C1C")
    private val GREEN = Color.parseColor("#2E9E5B")
    private val BLUE = Color.parseColor("#0288D1")
    private val BLUE_H = Color.parseColor("#4DA3FF")
    private val NAVY = Color.parseColor("#1A2340")
    private val GRAY = Color.parseColor("#6B7280")
    private val PINK = Color.parseColor("#E91E63")
    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

    private val pages = ArrayList<File>()
    private val pageQuads = ArrayList<FloatArray?>()
    private val edited = ArrayList<File>()
    private val cropPts = ArrayList<FloatArray>()
    private val filterNames = listOf(
        "Original", "Magic color", "Magic white", "B&W", "Grayscale", "Lighten", "Vivid"
    )
    private val filterFrames = ArrayList<FrameLayout>()
    private val filterLabels = ArrayList<TextView>()
    private var filterIdx = 0
    private var cropIndex = 0
    private var cropView: CropView? = null
    private var busy = false

    private var idMode = false
    private var autoMode = true
    private var scanName = ""
    private var nameInput: EditText? = null
    private var statusView: TextView? = null

    private var screen = "home"
    private var pending = 0
    private var torchOn = false
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var previewView: PreviewView? = null
    private var detectView: DetectView? = null
    private var thumbBox: FrameLayout? = null
    private var thumbImg: ImageView? = null
    private var thumbBadge: TextView? = null
    private var proceedBadge: TextView? = null

    private var lastQuad: FloatArray? = null
    private var capturedQuad: FloatArray? = null
    private var stableSince = 0L
    private var invalidCount = 0
    private var armed = true
    private var lastAutoAt = 0L

    private val worker = Executors.newSingleThreadExecutor()
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        if (ok) startCamera() else toast("Camera permission is required. Allow it in phone Settings.")
    }

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) importUris(uris)
    }

    private val pdfPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importPdf(uri)
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

    private fun boldLabel(text: String, size: Float, color: Int): TextView {
        val t = label(text, size, color)
        t.setTypeface(null, Typeface.BOLD)
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

    private fun fullQuad(): FloatArray = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)

    private fun addPage(f: File, q: FloatArray?) {
        pages.add(f)
        pageQuads.add(q)
    }

    private fun removePageAt(i: Int) {
        pages.removeAt(i).delete()
        if (i < pageQuads.size) pageQuads.removeAt(i)
        if (i < cropPts.size) cropPts.removeAt(i)
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

    // ---------- filters + crop output ----------

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

    // crops the 4-corner area (with perspective fix) and applies the filter
    private fun applyEdit(src: File, pts: FloatArray, fi: Int, dst: File) {
        val bmp = BitmapFactory.decodeFile(src.absolutePath)
            ?: throw IllegalStateException("Image load failed")
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val sp = FloatArray(8)
        for (k in 0 until 4) {
            sp[2 * k] = pts[2 * k] * bw
            sp[2 * k + 1] = pts[2 * k + 1] * bh
        }
        val topLen = hypot(sp[2] - sp[0], sp[3] - sp[1])
        val botLen = hypot(sp[4] - sp[6], sp[5] - sp[7])
        val leftLen = hypot(sp[6] - sp[0], sp[7] - sp[1])
        val rightLen = hypot(sp[4] - sp[2], sp[5] - sp[3])
        val w = maxOf(topLen, botLen).toInt().coerceAtLeast(1)
        val h = maxOf(leftLen, rightLen).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val cf = filterFilter(fi)
        if (cf != null) paint.colorFilter = cf
        val dstPts = floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())
        val m = Matrix()
        if (!m.setPolyToPoly(sp, 0, dstPts, 0, 4)) throw IllegalStateException("Invalid crop area")
        canvas.drawBitmap(bmp, m, paint)
        FileOutputStream(dst).use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        out.recycle()
    }

    // ID card: front and back on one A4 page
    private fun composeId(files: List<File>, dst: File) {
        val pw = 1240
        val ph = 1754
        val page = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        val c = Canvas(page)
        c.drawColor(Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val border = Paint(Paint.ANTI_ALIAS_FLAG)
        border.color = Color.parseColor("#BDBDBD")
        border.style = Paint.Style.STROKE
        border.strokeWidth = 3f
        val slotH = ph / 2
        val maxW = pw * 0.8f
        val maxH = slotH * 0.8f
        for (k in 0 until minOf(2, files.size)) {
            val bmp = BitmapFactory.decodeFile(files[k].absolutePath) ?: continue
            val s = minOf(maxW / bmp.width, maxH / bmp.height)
            val w = bmp.width * s
            val h = bmp.height * s
            val left = (pw - w) / 2f
            val top = k * slotH + (slotH - h) / 2f
            val r = RectF(left, top, left + w, top + h)
            c.drawBitmap(bmp, null, r, paint)
            c.drawRect(r, border)
            bmp.recycle()
        }
        FileOutputStream(dst).use { page.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        page.recycle()
    }

    // ---------- camera + auto scan ----------

    private fun idleHint(): String {
        if (!idMode) return ""
        return when {
            pages.size == 0 -> "Capture the front side"
            pages.size == 1 -> "Capture the back side"
            else -> "Tap Proceed"
        }
    }

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
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) }
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, ic, analysis
                )
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

    private fun maxDiff(a: FloatArray, b: FloatArray): Float {
        var m = 0f
        for (i in 0 until 8) m = maxOf(m, abs(a[i] - b[i]))
        return m
    }

    private fun postOverlay(q: FloatArray?, aspect: Float, hint: String) {
        val h = if (hint.isNotEmpty()) hint else idleHint()
        runOnUiThread { detectView?.update(q, aspect, h) }
    }

    private fun analyze(proxy: ImageProxy) {
        try {
            if (screen != "camera") return
            val w = proxy.width
            val h = proxy.height
            val rot = proxy.imageInfo.rotationDegrees
            val plane = proxy.planes[0]
            val buf = plane.buffer
            val rs = plane.rowStride
            val ps = plane.pixelStride
            val uw = if (rot == 90 || rot == 270) h else w
            val uh = if (rot == 90 || rot == 270) w else h
            val aspect = uw.toFloat() / uh.toFloat()
            if (!autoMode) {
                postOverlay(null, aspect, "")
                return
            }
            val sc = 96f / maxOf(uw, uh)
            val gw = maxOf(8, (uw * sc).toInt())
            val gh = maxOf(8, (uh * sc).toInt())
            val gray = IntArray(gw * gh)
            for (gy in 0 until gh) {
                for (gx in 0 until gw) {
                    var acc = 0
                    var cnt = 0
                    for (oy in -1..1) {
                        for (ox in -1..1) {
                            val ux = ((gx + 0.5f + ox * 0.3f) / gw * uw).toInt().coerceIn(0, uw - 1)
                            val uy = ((gy + 0.5f + oy * 0.3f) / gh * uh).toInt().coerceIn(0, uh - 1)
                            var sx = ux
                            var sy = uy
                            when (rot) {
                                90 -> {
                                    sx = uy
                                    sy = h - 1 - ux
                                }
                                180 -> {
                                    sx = w - 1 - ux
                                    sy = h - 1 - uy
                                }
                                270 -> {
                                    sx = w - 1 - uy
                                    sy = ux
                                }
                            }
                            acc += buf.get(sy * rs + sx * ps).toInt() and 0xFF
                            cnt++
                        }
                    }
                    gray[gy * gw + gx] = acc / cnt
                }
            }
            val quad = DocDetector.detect(gray, gw, gh)
            val now = System.currentTimeMillis()
            var hint = ""
            if (quad == null) {
                invalidCount++
                stableSince = 0L
                lastQuad = null
                if (invalidCount >= 6) armed = true
            } else {
                invalidCount = 0
                val prev = lastQuad
                if (prev != null && maxDiff(prev, quad) < 0.04f) {
                    if (stableSince == 0L) stableSince = now
                } else {
                    stableSince = now
                }
                lastQuad = quad
                val cq = capturedQuad
                if (!armed && cq != null && maxDiff(cq, quad) > 0.12f) armed = true
                val idFull = idMode && pages.size + pending >= 2
                if (armed && !idFull && pending == 0 && now - lastAutoAt > 2500L) {
                    hint = "Hold steady..."
                    if (now - stableSince >= 900L) {
                        armed = false
                        capturedQuad = quad
                        lastAutoAt = now
                        stableSince = 0L
                        runOnUiThread { takePhoto(quad) }
                    }
                }
            }
            postOverlay(quad, aspect, hint)
        } catch (e: Throwable) {
        } finally {
            proxy.close()
        }
    }

    private fun toggleTorch(btn: TextView) {
        val cam = camera
        if (cam == null) {
            toast("Camera is not ready yet")
            return
        }
        if (!cam.cameraInfo.hasFlashUnit()) {
            toast("This phone has no flash")
            return
        }
        torchOn = !torchOn
        cam.cameraControl.enableTorch(torchOn)
        btn.text = if (torchOn) "Flash ON" else "Flash OFF"
        btn.setTextColor(if (torchOn) Color.YELLOW else Color.WHITE)
    }

    private fun takePhoto(autoQuad: FloatArray?) {
        val ic = imageCapture
        if (ic == null) {
            if (autoQuad == null) toast("Camera is not ready yet")
            return
        }
        if (idMode && pages.size + pending >= 2) {
            if (autoQuad == null) toast("Front and back are done. Tap Proceed.")
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
                            if (f != null) addPage(f, autoQuad) else toast("Could not save the photo")
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
        var list = uris
        if (idMode) {
            val room = 2 - pages.size - pending
            if (room <= 0) {
                toast("Front and back are done. Tap Proceed.")
                return
            }
            list = uris.take(room)
        }
        pending += 1
        worker.execute {
            val added = ArrayList<File>()
            for (u in list) {
                val f = importFromUri(u)
                if (f != null) added.add(f)
            }
            runOnUiThread {
                for (f in added) addPage(f, null)
                pending -= 1
                if (screen == "camera") refreshOverlay()
                if (added.size < list.size) toast("Some images could not be loaded")
            }
        }
    }

    private fun removeLast() {
        if (pages.isNotEmpty()) {
            removePageAt(pages.size - 1)
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
            toast("Please wait, the photo is being processed...")
            return
        }
        if (pages.isEmpty()) {
            toast("Capture at least 1 page first")
            return
        }
        cropPts.clear()
        for (q in pageQuads) cropPts.add(q?.copyOf() ?: fullQuad())
        filterIdx = 0
        showCrop(0)
    }

    // ---------- Stage 2: edit (crop dots + filters + rotate + delete) ----------

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

    private fun toolItem(kind: Int, text: String, color: Int, onClick: () -> Unit): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.setPadding(0, dp(6), 0, dp(6))
        box.addView(IconView(this, kind, false, color), lp(dp(40), dp(40)))
        val t = label(text, 14f, color)
        t.gravity = Gravity.CENTER
        t.setPadding(0, dp(2), 0, 0)
        box.addView(t, lp(WRAP, WRAP))
        box.setOnClickListener { onClick() }
        return box
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
        head.setPadding(0, dp(28), 0, dp(8))
        val t1 = boldLabel("Edit", 24f, Color.parseColor("#FF3B30"))
        t1.gravity = Gravity.CENTER
        val t2 = label("Page ${i + 1} of $n", 14f, Color.parseColor("#9E9E9E"))
        t2.gravity = Gravity.CENTER
        head.addView(t1, lp(MATCH, WRAP))
        head.addView(t2, lp(MATCH, WRAP))
        root.addView(head, lp(MATCH, WRAP))

        val cv = CropView(this)
        cv.bitmap = thumb(pages[i], 1600)
        cv.setPoints(cropPts[i])
        cv.filter = filterFilter(filterIdx)
        cropView = cv
        root.addView(cv, lp(MATCH, 0, 1f))

        val tools = LinearLayout(this)
        tools.orientation = LinearLayout.HORIZONTAL
        tools.setPadding(dp(24), dp(4), dp(24), dp(4))
        tools.addView(
            toolItem(0, "Delete", Color.parseColor("#FF5252")) { confirmDelete(i) },
            lp(0, WRAP, 1f)
        )
        tools.addView(
            toolItem(1, "Rotate", Color.WHITE) { rotateInCrop(i) },
            lp(0, WRAP, 1f)
        )
        root.addView(tools, lp(MATCH, WRAP))

        root.addView(buildFilterStrip(thumb(pages[i], 160)), lp(MATCH, dp(118)))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(16), dp(8), dp(16), dp(20))
        bar.addView(pill("Back", Color.parseColor("#3A3A3A")) { cropBack() }, lp(dp(110), dp(52)))
        bar.addView(View(this), lp(0, 1, 1f))
        bar.addView(iconText("Reset crop", 15f) { cropView?.resetCrop() }, lp(WRAP, WRAP))
        bar.addView(View(this), lp(0, 1, 1f))
        bar.addView(pill("Next  \u2192", GREEN) { cropNext() }, lp(dp(130), dp(56)))
        root.addView(bar)
        setContentView(root)
    }

    private fun saveCurrentPts() {
        val cv = cropView ?: return
        if (cropIndex < cropPts.size) {
            for (k in 0 until 8) cropPts[cropIndex][k] = cv.pts[k]
        }
    }

    private fun confirmDelete(i: Int) {
        if (busy) return
        val dlg = AlertDialog.Builder(this)
            .setMessage("Do you want to delete this page?")
            .setPositiveButton("Yes") { _, _ -> deleteInCrop(i) }
            .setNegativeButton("No", null)
            .create()
        dlg.show()
        dlg.getButton(DialogInterface.BUTTON_POSITIVE).setTextColor(BLUE)
        dlg.getButton(DialogInterface.BUTTON_NEGATIVE).setTextColor(BLUE)
    }

    private fun deleteInCrop(i: Int) {
        if (busy) return
        if (i >= pages.size) return
        removePageAt(i)
        toast("Page deleted")
        if (pages.isEmpty()) showCamera() else showCrop(minOf(i, pages.size - 1))
    }

    private fun rotateInCrop(i: Int) {
        if (busy) return
        val cv = cropView ?: return
        val c = cv.pts.copyOf()
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
                    cropPts[i] = c
                    toast("Rotate error: $err")
                } else {
                    val nw = FloatArray(8)
                    // rotate 90 clockwise: (x, y) -> (1 - y, x); corner order shifts by one
                    fun rot(k: Int, outK: Int) {
                        nw[2 * outK] = 1f - c[2 * k + 1]
                        nw[2 * outK + 1] = c[2 * k]
                    }
                    rot(3, 0)
                    rot(0, 1)
                    rot(1, 2)
                    rot(2, 3)
                    cropPts[i] = nw
                }
                if (screen == "crop") showCrop(i)
            }
        }
    }

    private fun cropNext() {
        if (busy) return
        saveCurrentPts()
        if (cropIndex < pages.size - 1) showCrop(cropIndex + 1) else applyAll()
    }

    private fun cropBack() {
        if (busy) return
        saveCurrentPts()
        if (cropIndex > 0) showCrop(cropIndex - 1) else showCamera()
    }

    private fun applyAll() {
        if (busy) return
        busy = true
        toast("Processing...")
        val srcs = pages.toList()
        val pts = cropPts.map { it.copyOf() }
        val fi = filterIdx
        val id = idMode
        worker.execute {
            val out = ArrayList<File>()
            var err: Throwable? = null
            try {
                for (k in srcs.indices) {
                    val dst = File(pagesDir(), "e_${System.nanoTime()}.jpg")
                    applyEdit(srcs[k], pts[k], fi, dst)
                    out.add(dst)
                }
                if (id && out.isNotEmpty()) {
                    val dst = File(pagesDir(), "id_${System.nanoTime()}.jpg")
                    composeId(out, dst)
                    for (f in out) f.delete()
                    out.clear()
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

    // ---------- Stage 3: file name, Save as PDF / JPG, Share ----------

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
        pageQuads.clear()
        edited.clear()
        cropPts.clear()
        filterIdx = 0
        scanName = ""
        pending = 0
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
            showHome()
        }, lp(dp(110), dp(52)))
        root.addView(bar)
        setContentView(root)
    }

    private fun leaveFinal() {
        scanName = baseName()
        if (pages.isEmpty()) showCamera() else showCrop(pages.size - 1)
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

    // ---------- Additional stage: Home, Import PDF, All PDFs ----------

    private fun loadPdfs(): List<PdfItem> {
        val list = ArrayList<PdfItem>()
        try {
            val proj = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.DATE_ADDED,
                MediaStore.Downloads.SIZE
            )
            val sel = MediaStore.Downloads.MIME_TYPE + " = ? AND " +
                MediaStore.Downloads.RELATIVE_PATH + " LIKE ?"
            val args = arrayOf("application/pdf", Environment.DIRECTORY_DOWNLOADS + "/PDFScanner%")
            val c = contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj, sel, args,
                MediaStore.Downloads.DATE_ADDED + " DESC"
            )
            c?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    list.add(
                        PdfItem(
                            ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id),
                            it.getString(1) ?: "PDF",
                            it.getLong(2),
                            it.getLong(3)
                        )
                    )
                }
            }
        } catch (e: Throwable) {
        }
        return list
    }

    private fun fmtMeta(p: PdfItem): String {
        val date = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(p.dateSec * 1000L))
        val size = if (p.size >= 1048576L) {
            String.format(Locale.US, "%.1f MB", p.size / 1048576.0)
        } else {
            "${maxOf(1L, p.size / 1024L)} KB"
        }
        return "$date  \u00B7  $size"
    }

    private fun openPdf(uri: Uri) {
        try {
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uri, "application/pdf")
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        } catch (e: Throwable) {
            toast("No PDF viewer found on this phone")
        }
    }

    private fun importPdf(uri: Uri) {
        toast("Importing...")
        worker.execute {
            var err: Throwable? = null
            try {
                var name = "Imported_" + stamp() + ".pdf"
                val q = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                q?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getString(0)
                        if (!n.isNullOrEmpty()) name = n
                    }
                }
                if (!name.lowercase(Locale.ROOT).endsWith(".pdf")) name += ".pdf"
                val values = ContentValues()
                values.put(MediaStore.Downloads.DISPLAY_NAME, name)
                values.put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                values.put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/PDFScanner"
                )
                val dst = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Could not create file")
                val input = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Could not read the PDF")
                val output = contentResolver.openOutputStream(dst)
                    ?: throw IllegalStateException("Could not write the PDF")
                input.use { i2 -> output.use { o2 -> i2.copyTo(o2) } }
            } catch (e: Throwable) {
                err = e
            }
            val er = err
            runOnUiThread {
                if (er != null) {
                    toast("Import error: $er")
                } else {
                    toast("PDF imported")
                    showHome()
                }
            }
        }
    }

    private fun gradientHeader(): GradientDrawable {
        val g = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.parseColor("#FF5A36"), Color.parseColor("#D81B7A"))
        )
        val r = dp(36).toFloat()
        g.cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
        return g
    }

    private fun navItem(kind: Int, text: String, sel: Boolean, onClick: () -> Unit): View {
        val wrap = FrameLayout(this)
        val item = LinearLayout(this)
        item.orientation = LinearLayout.HORIZONTAL
        item.gravity = Gravity.CENTER_VERTICAL
        item.setPadding(dp(18), dp(10), dp(20), dp(10))
        if (sel) item.background = rounded(Color.parseColor("#FFD9D6"), 24)
        val col = if (sel) PINK else GRAY
        item.addView(IconView(this, kind, false, col), lp(dp(26), dp(26)))
        val t = boldLabel(text, 15f, col)
        t.setPadding(dp(8), 0, 0, 0)
        item.addView(t)
        item.setOnClickListener { onClick() }
        wrap.addView(item, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        return wrap
    }

    private fun navBar(selected: Int): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setBackgroundColor(Color.parseColor("#FCEAE9"))
        bar.setPadding(dp(12), dp(10), dp(12), dp(14))
        bar.addView(navItem(5, "Home", selected == 0) { if (screen != "home") showHome() }, lp(0, WRAP, 1f))
        bar.addView(navItem(6, "Import PDF", selected == 1) { if (screen != "import") showImport() }, lp(0, WRAP, 1f))
        return bar
    }

    private fun homeCard(
        kind: Int, title: String, sub: String, tileBg: Int, glyph: Int, onClick: () -> Unit
    ): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(18), dp(18), dp(18), dp(16))
        card.background = rounded(Color.WHITE, 28)
        card.elevation = dp(6).toFloat()
        val tile = FrameLayout(this)
        tile.background = rounded(tileBg, 22)
        tile.addView(IconView(this, kind, false, glyph), FrameLayout.LayoutParams(dp(68), dp(68)))
        card.addView(tile, lp(dp(68), dp(68)))
        card.addView(View(this), lp(1, 0, 1f))
        card.addView(boldLabel(title, 24f, NAVY))
        val sr = LinearLayout(this)
        sr.orientation = LinearLayout.HORIZONTAL
        sr.gravity = Gravity.CENTER_VERTICAL
        sr.addView(label(sub, 15f, GRAY), lp(0, WRAP, 1f))
        sr.addView(label("\u2192", 20f, GRAY))
        card.addView(sr, lp(MATCH, WRAP))
        card.setOnClickListener { onClick() }
        return card
    }

    private fun pdfRow(p: PdfItem): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(12), dp(12), dp(12), dp(12))
        row.background = rounded(Color.WHITE, 16)
        val lpRow = lp(MATCH, WRAP)
        lpRow.topMargin = dp(10)
        row.layoutParams = lpRow
        val tile = FrameLayout(this)
        tile.background = rounded(Color.parseColor("#FDECEA"), 14)
        tile.addView(IconView(this, 7, false, PINK), FrameLayout.LayoutParams(dp(48), dp(48)))
        row.addView(tile, lp(dp(48), dp(48)))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), 0, 0, 0)
        val nm = boldLabel(p.name, 15f, NAVY)
        nm.maxLines = 1
        nm.ellipsize = TextUtils.TruncateAt.END
        col.addView(nm, lp(MATCH, WRAP))
        col.addView(label(fmtMeta(p), 12f, GRAY), lp(MATCH, WRAP))
        row.addView(col, lp(0, WRAP, 1f))
        row.setOnClickListener { openPdf(p.uri) }
        return row
    }

    private fun startScanMode(id: Boolean) {
        resetAll()
        idMode = id
        showCamera()
    }

    private fun showHome() {
        screen = "home"
        val pdfs = loadPdfs()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#FAFAFC"))

        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL

        val head = LinearLayout(this)
        head.orientation = LinearLayout.VERTICAL
        head.background = gradientHeader()
        head.setPadding(dp(24), dp(52), dp(24), dp(96))
        head.addView(label("Welcome Back", 16f, Color.parseColor("#FFE3E3")))
        head.addView(boldLabel("Kagaz Scan \uD83D\uDCD6", 34f, Color.WHITE))
        content.addView(head, lp(MATCH, WRAP))

        val cards = LinearLayout(this)
        cards.orientation = LinearLayout.HORIZONTAL
        cards.setPadding(dp(20), 0, dp(20), 0)
        val c1 = lp(0, dp(176), 1f)
        c1.rightMargin = dp(8)
        cards.addView(
            homeCard(3, "Scan", "Documents", Color.parseColor("#E3F2FD"), Color.parseColor("#1E88E5")) {
                startScanMode(false)
            }, c1
        )
        val c2 = lp(0, dp(176), 1f)
        c2.leftMargin = dp(8)
        cards.addView(
            homeCard(4, "ID Card", "Front & back", Color.parseColor("#FFF3CD"), Color.parseColor("#F9A825")) {
                startScanMode(true)
            }, c2
        )
        val cl = lp(MATCH, WRAP)
        cl.topMargin = -dp(70)
        content.addView(cards, cl)

        val tip = LinearLayout(this)
        tip.orientation = LinearLayout.HORIZONTAL
        tip.gravity = Gravity.CENTER_VERTICAL
        tip.setPadding(dp(16), dp(16), dp(16), dp(16))
        val tipBg = GradientDrawable()
        tipBg.cornerRadius = dp(22).toFloat()
        tipBg.setColor(Color.parseColor("#E6F2FD"))
        tipBg.setStroke(dp(2), Color.parseColor("#BBDDF7"))
        tip.background = tipBg
        val plus = TextView(this)
        plus.text = "+"
        plus.textSize = 26f
        plus.setTextColor(Color.WHITE)
        plus.gravity = Gravity.CENTER
        plus.background = oval(Color.parseColor("#1E88E5"))
        tip.addView(plus, lp(dp(46), dp(46)))
        val tc = LinearLayout(this)
        tc.orientation = LinearLayout.VERTICAL
        tc.setPadding(dp(14), 0, 0, 0)
        tc.addView(boldLabel("Quick tip", 17f, Color.parseColor("#1565C0")))
        tc.addView(
            label(
                "Hold your phone steady. Auto scan captures the page when it is aligned.",
                14f, Color.parseColor("#1976D2")
            )
        )
        tip.addView(tc, lp(0, WRAP, 1f))
        val tlp = lp(MATCH, WRAP)
        tlp.setMargins(dp(20), dp(20), dp(20), 0)
        content.addView(tip, tlp)

        val sec = LinearLayout(this)
        sec.orientation = LinearLayout.VERTICAL
        sec.setPadding(dp(20), dp(24), dp(20), dp(16))
        val lib = label("YOUR LIBRARY", 12f, GRAY)
        lib.letterSpacing = 0.15f
        lib.setTypeface(null, Typeface.BOLD)
        sec.addView(lib)
        val hr = LinearLayout(this)
        hr.orientation = LinearLayout.HORIZONTAL
        hr.gravity = Gravity.BOTTOM
        hr.addView(boldLabel("Recent PDFs", 26f, NAVY), lp(0, WRAP, 1f))
        val seeAll = boldLabel("See all", 16f, Color.parseColor("#1E88E5"))
        seeAll.setPadding(dp(8), dp(8), 0, dp(8))
        seeAll.setOnClickListener { showAll() }
        hr.addView(seeAll)
        sec.addView(hr, lp(MATCH, WRAP))
        if (pdfs.isEmpty()) {
            val e = label("No scans yet", 16f, Color.parseColor("#9CA3AF"))
            e.gravity = Gravity.CENTER
            e.setPadding(0, dp(40), 0, dp(40))
            sec.addView(e, lp(MATCH, WRAP))
        } else {
            for (p in pdfs.take(5)) sec.addView(pdfRow(p))
        }
        content.addView(sec, lp(MATCH, WRAP))

        val sv = ScrollView(this)
        sv.addView(content)
        root.addView(sv, lp(MATCH, 0, 1f))
        root.addView(navBar(0), lp(MATCH, WRAP))
        setContentView(root)
    }

    private fun showImport() {
        screen = "import"
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#FAFAFC"))

        val head = LinearLayout(this)
        head.orientation = LinearLayout.VERTICAL
        head.background = gradientHeader()
        head.setPadding(dp(24), dp(52), dp(24), dp(40))
        head.addView(boldLabel("Import PDF", 32f, Color.WHITE))
        head.addView(label("Add an existing PDF to your library", 15f, Color.parseColor("#FFE3E3")))
        root.addView(head, lp(MATCH, WRAP))

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.setPadding(dp(20), dp(28), dp(20), dp(28))
        card.background = rounded(Color.WHITE, 28)
        card.elevation = dp(4).toFloat()
        val tile = FrameLayout(this)
        tile.background = rounded(Color.parseColor("#FDECEA"), 24)
        tile.addView(IconView(this, 7, false, PINK), FrameLayout.LayoutParams(dp(84), dp(84)))
        card.addView(tile, lp(dp(84), dp(84)))
        val t = boldLabel("Choose a PDF from your phone", 18f, NAVY)
        t.gravity = Gravity.CENTER
        t.setPadding(0, dp(16), 0, dp(6))
        card.addView(t, lp(MATCH, WRAP))
        val s = label("Imported files appear in Recent PDFs.", 14f, GRAY)
        s.gravity = Gravity.CENTER
        card.addView(s, lp(MATCH, WRAP))
        val b = pill("Choose PDF", BLUE) { pdfPicker.launch(arrayOf("application/pdf")) }
        val bl = lp(MATCH, dp(56))
        bl.topMargin = dp(22)
        card.addView(b, bl)
        val cl = lp(MATCH, WRAP)
        cl.setMargins(dp(20), dp(24), dp(20), 0)
        root.addView(card, cl)

        root.addView(View(this), lp(1, 0, 1f))
        root.addView(navBar(1), lp(MATCH, WRAP))
        setContentView(root)
    }

    private fun showAll() {
        screen = "all"
        val pdfs = loadPdfs()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#FAFAFC"))

        val head = LinearLayout(this)
        head.orientation = LinearLayout.VERTICAL
        head.background = gradientHeader()
        head.setPadding(dp(24), dp(52), dp(24), dp(28))
        head.addView(boldLabel("All PDFs", 30f, Color.WHITE))
        head.addView(label("${pdfs.size} file(s)", 15f, Color.parseColor("#FFE3E3")))
        root.addView(head, lp(MATCH, WRAP))

        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(dp(20), dp(6), dp(20), dp(16))
        if (pdfs.isEmpty()) {
            val e = label("No scans yet", 16f, Color.parseColor("#9CA3AF"))
            e.gravity = Gravity.CENTER
            e.setPadding(0, dp(60), 0, dp(60))
            list.addView(e, lp(MATCH, WRAP))
        } else {
            for (p in pdfs) list.addView(pdfRow(p))
        }
        val sv = ScrollView(this)
        sv.addView(list)
        root.addView(sv, lp(MATCH, 0, 1f))

        val bar = LinearLayout(this)
        bar.setPadding(dp(16), dp(8), dp(16), dp(20))
        bar.addView(pill("Back", Color.parseColor("#78909C")) { showHome() }, lp(dp(110), dp(52)))
        root.addView(bar)
        setContentView(root)
    }

    // ---------- Stage 1: camera screen ----------

    private fun leaveCamera() {
        stopCamera()
        resetAll()
        showHome()
    }

    private fun showCamera() {
        screen = "camera"
        armed = true
        lastQuad = null
        capturedQuad = null
        stableSince = 0L
        invalidCount = 0
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(DARK)

        val top = FrameLayout(this)
        top.setPadding(dp(8), dp(24), dp(8), dp(4))
        top.addView(
            iconText("X", 22f) { leaveCamera() },
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.START or Gravity.CENTER_VERTICAL)
        )

        val chip = TextView(this)
        chip.textSize = 14f
        chip.setTextColor(Color.WHITE)
        chip.setTypeface(null, Typeface.BOLD)
        chip.gravity = Gravity.CENTER
        chip.setPadding(dp(14), dp(7), dp(14), dp(7))
        fun styleChip() {
            chip.text = if (autoMode) "Auto ON" else "Auto OFF"
            chip.background = rounded(
                if (autoMode) Color.parseColor("#2E7D32") else Color.parseColor("#3A3A3A"), 16
            )
        }
        styleChip()
        chip.setOnClickListener {
            autoMode = !autoMode
            styleChip()
            if (!autoMode) detectView?.update(null, 0.75f, idleHint())
        }
        top.addView(chip, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))

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

        val dv = DetectView(this)
        detectView = dv
        dv.update(null, 0.75f, idleHint())
        container.addView(dv, FrameLayout.LayoutParams(MATCH, MATCH))

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

        val bar = FrameLayout(this)
        bar.setPadding(dp(16), dp(12), dp(16), dp(20))

        val galleryBox = FrameLayout(this)
        galleryBox.background = rounded(Color.parseColor("#3A3A3A"), 16)
        galleryBox.addView(IconView(this, 2), FrameLayout.LayoutParams(dp(72), dp(72)))
        galleryBox.setOnClickListener { galleryLauncher.launch("image/*") }
        bar.addView(
            galleryBox,
            FrameLayout.LayoutParams(dp(72), dp(72), Gravity.START or Gravity.CENTER_VERTICAL)
        )

        val cap = View(this)
        val capBg = GradientDrawable()
        capBg.shape = GradientDrawable.OVAL
        capBg.setColor(Color.WHITE)
        capBg.setStroke(dp(5), Color.parseColor("#9E9E9E"))
        cap.background = capBg
        cap.setOnClickListener { takePhoto(null) }
        bar.addView(cap, FrameLayout.LayoutParams(dp(76), dp(76), Gravity.CENTER))

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
        bar.addView(
            pw,
            FrameLayout.LayoutParams(dp(124), dp(80), Gravity.END or Gravity.CENTER_VERTICAL)
        )

        root.addView(bar, lp(MATCH, dp(116)))
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
        tv.text = "ERROR (send a screenshot):\n\n" + android.util.Log.getStackTraceString(e)
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
                    "camera" -> leaveCamera()
                    "crop" -> cropBack()
                    "next" -> leaveFinal()
                    "all", "import" -> showHome()
                    else -> finish()
                }
            }
        })
        try {
            showHome()
        } catch (e: Throwable) {
            showError(e)
        }
    }
}
