package com.akay.pdfscanner

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
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
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
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

    // Google TEST ad unit (safe for testing). Replace with your real ad unit ID before publishing.
    private val AD_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"

    // Minimum gap between two ads in milliseconds (0 = ad on every click, for testing).
    private val AD_GAP_MS = 0L

    private val DARK = Color.parseColor("#1C1C1C")
    private val CIRCLE_BG = Color.parseColor("#3A3A3A")
    private val GREEN = Color.parseColor("#2E9E5B")
    private val BLUE = Color.parseColor("#0288D1")
    private val BLUE_H = Color.parseColor("#4DA3FF")
    private val NAVY = Color.parseColor("#0E1633")
    private val GRAY = Color.parseColor("#5B6B86")
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
    private var counterView: TextView? = null
    private var flashIcon: IconView? = null
    private var autoIcon: IconView? = null
    private var autoLabel: TextView? = null

    private var lastQuad: FloatArray? = null
    private var smoothQuad: FloatArray? = null
    private var capturedQuad: FloatArray? = null
    private var stableSince = 0L
    private var invalidCount = 0
    private var missCount = 0
    private var armed = true
    private var lastAutoAt = 0L
    private var autoWarned = false
    private var errLogged = 0

    private var interstitial: InterstitialAd? = null
    private var adLoading = false
    private var adShowing = false
    private var lastAdAt = 0L
    private var afterMedia: (() -> Unit)? = null

    private val thumbCache = HashMap<String, Bitmap>()
    private val pageCountCache = HashMap<String, Int>()

    private val worker = Executors.newSingleThreadExecutor()
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val thumbPool = Executors.newSingleThreadExecutor()

    private val permAll = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (screen == "camera") {
            if (hasCamera()) startCamera() else toast("Camera permission is required. Allow it in phone Settings.")
        }
    }

    private val mediaPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val cb = afterMedia
        afterMedia = null
        if (granted) {
            cb?.invoke()
        } else if (shouldShowRequestPermissionRationale(mediaPerm())) {
            toast("Photos and media permission is required for this.")
        } else {
            showSettingsDialog()
        }
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

    @Suppress("DEPRECATION")
    private fun bars(color: Int, light: Boolean) {
        try {
            window.statusBarColor = color
        } catch (e: Throwable) {
        }
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = light
    }

    // Shows a screen and keeps status/navigation bar areas coloured (needed for Android 15+ edge-to-edge)
    private fun setScreen(content: View, top: Int, bottom: Int, lightStatus: Boolean, lightNav: Boolean) {
        bars(top, lightStatus)
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setBackgroundColor(bottom)
        val topPad = View(this)
        topPad.setBackgroundColor(top)
        val botPad = View(this)
        botPad.setBackgroundColor(bottom)
        wrap.addView(topPad, lp(MATCH, 0))
        wrap.addView(content, lp(MATCH, 0, 1f))
        wrap.addView(botPad, lp(MATCH, 0))
        ViewCompat.setOnApplyWindowInsetsListener(wrap) { _, insets ->
            val sb = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            (topPad.layoutParams as LinearLayout.LayoutParams).height = sb.top
            (botPad.layoutParams as LinearLayout.LayoutParams).height = maxOf(sb.bottom, ime.bottom)
            topPad.requestLayout()
            botPad.requestLayout()
            WindowInsetsCompat.CONSUMED
        }
        setContentView(wrap)
        if (Build.VERSION.SDK_INT >= 35) {
            WindowInsetsControllerCompat(window, wrap).isAppearanceLightNavigationBars = lightNav
        }
        ViewCompat.requestApplyInsets(wrap)
    }

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

    private fun circleBtn(kind: Int, onClick: () -> Unit): FrameLayout {
        val f = FrameLayout(this)
        f.background = oval(CIRCLE_BG)
        f.addView(IconView(this, kind, false, Color.WHITE), FrameLayout.LayoutParams(dp(44), dp(44)))
        f.setOnClickListener { onClick() }
        return f
    }

    private fun squareBtn(kind: Int, bg: Int, glyph: Int, onClick: () -> Unit): FrameLayout {
        val f = FrameLayout(this)
        f.background = rounded(bg, 18)
        f.addView(IconView(this, kind, false, glyph), FrameLayout.LayoutParams(dp(52), dp(52)))
        f.setOnClickListener { onClick() }
        return f
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

    private fun hasCamera(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    // ---------- media permission (gallery + saving) ----------

    private fun mediaPerm(): String =
        if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun hasMedia(): Boolean =
        ContextCompat.checkSelfPermission(this, mediaPerm()) == PackageManager.PERMISSION_GRANTED

    private fun withMedia(action: () -> Unit) {
        if (hasMedia()) {
            action()
        } else {
            afterMedia = action
            mediaPermLauncher.launch(mediaPerm())
        }
    }

    private fun showSettingsDialog() {
        val dlg = AlertDialog.Builder(this)
            .setMessage("Photos and media permission is needed. Please allow it in Settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                try {
                    val i = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    i.data = Uri.parse("package:$packageName")
                    startActivity(i)
                } catch (e: Throwable) {
                    toast("Open Settings > Apps > PDF Scanner > Permissions")
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        dlg.show()
        dlg.getButton(DialogInterface.BUTTON_POSITIVE).setTextColor(BLUE)
        dlg.getButton(DialogInterface.BUTTON_NEGATIVE).setTextColor(BLUE)
    }

    private fun missingPerms(): Array<String> {
        val l = ArrayList<String>()
        l.add(Manifest.permission.CAMERA)
        l.add(mediaPerm())
        return l.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }

    // ---------- ads (Google test interstitial) ----------

    private fun startAds() {
        Thread {
            try {
                MobileAds.initialize(this) { runOnUiThread { loadInterstitial() } }
            } catch (e: Throwable) {
                Log.e("PDFScanner", "Ads init failed", e)
            }
        }.start()
    }

    private fun loadInterstitial() {
        if (adLoading || interstitial != null) return
        adLoading = true
        try {
            InterstitialAd.load(
                this, AD_UNIT_ID, AdRequest.Builder().build(),
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        interstitial = ad
                        adLoading = false
                        Log.i("PDFScanner", "Ad loaded")
                    }

                    override fun onAdFailedToLoad(err: LoadAdError) {
                        interstitial = null
                        adLoading = false
                        Log.w("PDFScanner", "Ad failed to load: ${err.message}")
                    }
                }
            )
        } catch (e: Throwable) {
            adLoading = false
            Log.e("PDFScanner", "Ad load error", e)
        }
    }

    // Shows an ad (if ready), then runs the action. If no ad is ready, runs the action at once.
    private fun runWithAd(action: () -> Unit) {
        if (adShowing) return
        val ad = interstitial
        val now = System.currentTimeMillis()
        if (ad == null || now - lastAdAt < AD_GAP_MS) {
            loadInterstitial()
            action()
            return
        }
        interstitial = null
        adShowing = true
        var done = false
        fun continueAfterAd() {
            if (done) return
            done = true
            adShowing = false
            lastAdAt = System.currentTimeMillis()
            loadInterstitial()
            action()
        }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                continueAfterAd()
            }

            override fun onAdFailedToShowFullScreenContent(e: AdError) {
                Log.w("PDFScanner", "Ad failed to show: ${e.message}")
                continueAfterAd()
            }
        }
        try {
            ad.show(this)
        } catch (e: Throwable) {
            continueAfterAd()
        }
    }

    // ---------- images ----------

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
            pages.size == 0 -> "ID Card FRONT"
            pages.size == 1 -> "ID Card BACK"
            else -> "Tap the green box to continue"
        }
    }

    private fun resetAutoState() {
        armed = true
        lastQuad = null
        smoothQuad = null
        capturedQuad = null
        stableSince = 0L
        invalidCount = 0
        missCount = 0
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
                Log.e("PDFScanner", "camera start failed", e)
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

    private fun updateAutoPill() {
        autoLabel?.text = if (autoMode) "Auto" else "Manual"
        val c = if (autoMode) Color.WHITE else Color.parseColor("#9E9E9E")
        autoLabel?.setTextColor(c)
        autoIcon?.setGlyph(c)
    }

    private fun analyze(proxy: ImageProxy) {
        try {
            if (screen != "camera") return
            val w = proxy.width
            val h = proxy.height
            val rot = proxy.imageInfo.rotationDegrees
            val uw = if (rot == 90 || rot == 270) h else w
            val uh = if (rot == 90 || rot == 270) w else h
            val aspect = uw.toFloat() / uh.toFloat()
            if (!autoMode) {
                postOverlay(null, aspect, "")
                return
            }
            if (!DocDetector.available()) {
                if (!autoWarned) {
                    autoWarned = true
                    runOnUiThread {
                        toast("Auto scan is not available on this phone. Use the shutter button.")
                        autoMode = false
                        updateAutoPill()
                    }
                }
                return
            }
            val plane = proxy.planes[0]
            val raw = DocDetector.detect(plane.buffer, w, h, plane.rowStride, rot)
            val now = System.currentTimeMillis()
            var quad: FloatArray? = null
            if (raw != null) {
                val prev = smoothQuad
                quad = if (prev == null || maxDiff(prev, raw) > 0.15f) {
                    raw
                } else {
                    FloatArray(8) { prev[it] * 0.6f + raw[it] * 0.4f }
                }
                smoothQuad = quad
                missCount = 0
            } else {
                missCount++
                if (missCount <= 3) {
                    quad = smoothQuad
                } else {
                    smoothQuad = null
                }
            }
            var hint = ""
            if (quad == null) {
                stableSince = 0L
                lastQuad = null
                invalidCount++
                if (invalidCount >= 6) armed = true
            } else {
                invalidCount = 0
                val prev = lastQuad
                if (prev != null && maxDiff(prev, quad) < 0.03f) {
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
                    if (now - stableSince >= 1000L) {
                        armed = false
                        capturedQuad = quad
                        lastAutoAt = now
                        stableSince = 0L
                        val q = quad
                        runOnUiThread { takePhoto(q) }
                    }
                }
            }
            postOverlay(quad, aspect, hint)
        } catch (e: Throwable) {
            if (errLogged < 5) {
                errLogged++
                Log.e("PDFScanner", "analyze error", e)
            }
        } finally {
            proxy.close()
        }
    }

    private fun toggleTorch() {
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
        flashIcon?.setGlyph(if (torchOn) Color.YELLOW else Color.WHITE)
    }

    private fun takePhoto(autoQuad: FloatArray?) {
        val ic = imageCapture
        if (ic == null) {
            if (autoQuad == null) toast("Camera is not ready yet")
            return
        }
        if (idMode && pages.size + pending >= 2) {
            if (autoQuad == null) toast("Front and back are done. Tap the green box.")
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
                toast("Front and back are done. Tap the green box.")
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
        counterView?.let {
            it.text = "$n/2"
            it.background = rounded(if (n > 0) GREEN else CIRCLE_BG, 20)
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

    // ---------- Stage 3: edit (crop dots + filters + rotate + delete) ----------

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
        head.setPadding(0, dp(20), 0, dp(8))
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
        bar.addView(pill("Back", Color.parseColor("#3A3A3A")) { cropBack() }, lp(0, dp(52), 1f))
        val reset = label("Reset crop", 15f, Color.WHITE)
        reset.gravity = Gravity.CENTER
        reset.setPadding(dp(12), dp(10), dp(12), dp(10))
        reset.setOnClickListener { cropView?.resetCrop() }
        bar.addView(reset, lp(WRAP, WRAP))
        bar.addView(pill("Next  \u2192", GREEN) { cropNext() }, lp(0, dp(56), 1.15f))
        root.addView(bar)
        setScreen(root, Color.parseColor("#121212"), Color.parseColor("#121212"), false, false)
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

    // ---------- Stage 4: ready screen ----------

    private fun baseName(): String {
        var n = (nameInput?.text?.toString() ?: scanName).trim()
        n = n.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        n = n.removeSuffix(".pdf").removeSuffix(".PDF").removeSuffix(".jpg").removeSuffix(".JPG")
        if (n.isEmpty()) n = "Scan_" + stamp()
        return n
    }

    private fun jpgName(base: String, k: Int, total: Int): String =
        if (total == 1) "$base.jpg" else "${base}_${k + 1}.jpg"

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

    private fun infoRow(kind: Int, text: String): View {
        val g = LinearLayout(this)
        g.orientation = LinearLayout.HORIZONTAL
        g.gravity = Gravity.CENTER_VERTICAL
        g.addView(IconView(this, kind, false, Color.parseColor("#7A889F")), lp(dp(22), dp(22)))
        val t = label(text, 14f, Color.parseColor("#7A889F"))
        t.setPadding(dp(8), 0, 0, 0)
        g.addView(t)
        return g
    }

    private fun bigButton(kind: Int, text: String, color: Int, h: Int, size: Float, onClick: () -> Unit): View {
        val b = LinearLayout(this)
        b.orientation = LinearLayout.HORIZONTAL
        b.gravity = Gravity.CENTER
        b.background = rounded(color, h / 2)
        b.elevation = dp(8).toFloat()
        b.outlineSpotShadowColor = color
        b.outlineAmbientShadowColor = color
        b.addView(IconView(this, kind, false, Color.WHITE), lp(dp(26), dp(26)))
        val t = boldLabel(text, size, Color.WHITE)
        t.setPadding(dp(10), 0, 0, 0)
        b.addView(t)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun showNext() {
        screen = "next"
        loadInterstitial()
        if (scanName.isEmpty()) scanName = "Scan_" + stamp()

        val sv = ScrollView(this)
        sv.isFillViewport = true
        sv.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#EAF2FF"), Color.WHITE)
        )
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(24), dp(16), dp(24), dp(24))

        val back = FrameLayout(this)
        back.background = rounded(Color.WHITE, 16)
        back.elevation = dp(4).toFloat()
        back.addView(IconView(this, 13, false, NAVY, 0.08f), FrameLayout.LayoutParams(dp(48), dp(48)))
        back.setOnClickListener { leaveFinal() }
        col.addView(back, lp(dp(48), dp(48)))

        val iconWrap = FrameLayout(this)
        val tile = FrameLayout(this)
        val tileBg = rounded(Color.parseColor("#F3F8FF"), 40)
        tileBg.setStroke(dp(2), Color.parseColor("#E1ECFB"))
        tile.background = tileBg
        tile.addView(
            IconView(this, 7, false, Color.parseColor("#2F6FEB"), 0.085f),
            FrameLayout.LayoutParams(dp(126), dp(126))
        )
        iconWrap.addView(tile, FrameLayout.LayoutParams(dp(126), dp(126), Gravity.TOP or Gravity.START))
        val chk = FrameLayout(this)
        val chkBg = oval(Color.parseColor("#00B976"))
        chkBg.setStroke(dp(4), Color.WHITE)
        chk.background = chkBg
        chk.elevation = dp(4).toFloat()
        chk.addView(
            IconView(this, 10, false, Color.WHITE, 0.1f),
            FrameLayout.LayoutParams(dp(50), dp(50))
        )
        iconWrap.addView(chk, FrameLayout.LayoutParams(dp(50), dp(50), Gravity.BOTTOM or Gravity.END))
        val iwl = lp(dp(150), dp(142))
        iwl.gravity = Gravity.CENTER_HORIZONTAL
        iwl.topMargin = dp(8)
        col.addView(iconWrap, iwl)

        val r1 = boldLabel("READY TO SAVE", 13f, Color.parseColor("#2F6FEB"))
        r1.letterSpacing = 0.15f
        r1.gravity = Gravity.CENTER
        r1.setPadding(0, dp(14), 0, dp(4))
        col.addView(r1, lp(MATCH, WRAP))
        val r2 = boldLabel("Your scan is ready", 30f, NAVY)
        r2.gravity = Gravity.CENTER
        col.addView(r2, lp(MATCH, WRAP))
        val r3 = label(
            "Review the name below and save it as PDF or JPG, or share it right away.",
            16f, GRAY
        )
        r3.gravity = Gravity.CENTER
        r3.setPadding(dp(8), dp(10), dp(8), 0)
        col.addView(r3, lp(MATCH, WRAP))

        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(dp(4), dp(6), dp(4), dp(10))
        for (k in edited.indices) {
            val cell = FrameLayout(this)
            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setPadding(dp(3), dp(3), dp(3), dp(3))
            iv.background = rounded(Color.WHITE, 20)
            iv.clipToOutline = true
            iv.elevation = dp(4).toFloat()
            iv.setImageBitmap(thumb(edited[k], 300))
            cell.addView(iv, FrameLayout.LayoutParams(dp(104), dp(136)))
            val nb = boldLabel((k + 1).toString(), 12f, Color.WHITE)
            nb.gravity = Gravity.CENTER
            nb.background = oval(Color.parseColor("#0B4F6C"))
            val nbl = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.BOTTOM or Gravity.START)
            nbl.leftMargin = dp(10)
            nbl.bottomMargin = dp(10)
            cell.addView(nb, nbl)
            val cl = lp(dp(104), dp(136))
            cl.rightMargin = dp(12)
            row.addView(cell, cl)
        }
        hs.addView(row)
        val hl = lp(MATCH, WRAP)
        hl.topMargin = dp(22)
        col.addView(hs, hl)

        val fl = boldLabel("File name", 14f, Color.parseColor("#8793A8"))
        fl.setPadding(dp(4), dp(18), 0, dp(8))
        col.addView(fl, lp(MATCH, WRAP))

        val inputWrap = FrameLayout(this)
        val et = EditText(this)
        et.setText(scanName)
        et.setSingleLine(true)
        et.textSize = 18f
        et.setTypeface(null, Typeface.BOLD)
        et.setTextColor(Color.parseColor("#1A2340"))
        et.setPadding(dp(22), 0, dp(56), 0)
        val etBg = rounded(Color.parseColor("#F4F8FF"), 29)
        etBg.setStroke(dp(2), Color.parseColor("#D3E4FA"))
        et.background = etBg
        nameInput = et
        inputWrap.addView(et, FrameLayout.LayoutParams(MATCH, dp(58)))
        val clr = IconView(this, 14, false, Color.parseColor("#9AA7BC"))
        clr.setOnClickListener { et.setText("") }
        val cll = FrameLayout.LayoutParams(dp(34), dp(34), Gravity.END or Gravity.CENTER_VERTICAL)
        cll.rightMargin = dp(14)
        inputWrap.addView(clr, cll)
        col.addView(inputWrap, lp(MATCH, WRAP))

        val info = LinearLayout(this)
        info.orientation = LinearLayout.HORIZONTAL
        info.setPadding(dp(6), dp(14), dp(6), 0)
        val cnt = if (edited.size == 1) "1 scanned page" else "${edited.size} scanned pages"
        info.addView(infoRow(7, cnt))
        info.addView(View(this), lp(0, 1, 1f))
        info.addView(infoRow(12, "Saved on device"))
        col.addView(info, lp(MATCH, WRAP))

        val savePdf = bigButton(6, "Save as PDF", Color.parseColor("#1B5BFF"), 64, 20f) { doSave(true) }
        val spl = lp(MATCH, dp(64))
        spl.topMargin = dp(28)
        col.addView(savePdf, spl)

        val two = LinearLayout(this)
        two.orientation = LinearLayout.HORIZONTAL
        val jpg = bigButton(2, "Save as JPG", Color.parseColor("#FFB800"), 60, 17f) { doSave(false) }
        val jl = lp(0, dp(60), 1f)
        jl.rightMargin = dp(8)
        two.addView(jpg, jl)
        val shr = bigButton(8, "Share", Color.parseColor("#00B976"), 60, 17f) {
            runWithAd { showShareSheet() }
        }
        val sl = lp(0, dp(60), 1f)
        sl.leftMargin = dp(8)
        two.addView(shr, sl)
        val tl = lp(MATCH, WRAP)
        tl.topMargin = dp(18)
        col.addView(two, tl)

        val home = boldLabel("Home", 18f, GRAY)
        home.gravity = Gravity.CENTER
        home.setPadding(0, dp(18), 0, dp(18))
        home.setOnClickListener {
            runWithAd {
                resetAll()
                showHome()
            }
        }
        val hml = lp(MATCH, WRAP)
        hml.topMargin = dp(12)
        col.addView(home, hml)

        sv.addView(col)
        setScreen(sv, Color.parseColor("#EAF2FF"), Color.WHITE, true, true)
    }

    private fun leaveFinal() {
        scanName = baseName()
        if (pages.isEmpty()) showCamera() else showCrop(pages.size - 1)
    }

    private fun formatCard(
        kind: Int, text: String, bgColor: Int, border: Int, tileColor: Int, glyph: Int, onClick: () -> Unit
    ): View {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.gravity = Gravity.CENTER_HORIZONTAL
        c.setPadding(dp(12), dp(24), dp(12), dp(22))
        val d = GradientDrawable()
        d.setColor(bgColor)
        d.cornerRadius = dp(30).toFloat()
        d.setStroke(dp(3), border)
        c.background = d
        val tile = FrameLayout(this)
        tile.background = rounded(tileColor, 22)
        tile.addView(IconView(this, kind, false, glyph, 0.07f), FrameLayout.LayoutParams(dp(56), dp(56)))
        c.addView(tile, lp(dp(56), dp(56)))
        val t = boldLabel(text, 26f, NAVY)
        t.gravity = Gravity.CENTER
        t.setPadding(0, dp(14), 0, 0)
        c.addView(t, lp(MATCH, WRAP))
        c.setOnClickListener { onClick() }
        return c
    }

    private fun showShareSheet() {
        val dlg = Dialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val sheet = LinearLayout(this)
        sheet.orientation = LinearLayout.VERTICAL
        sheet.gravity = Gravity.CENTER_HORIZONTAL
        sheet.setPadding(dp(20), dp(12), dp(20), dp(28))
        val bg = GradientDrawable()
        bg.setColor(Color.WHITE)
        val r = dp(36).toFloat()
        bg.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        sheet.background = bg

        val handle = View(this)
        handle.background = rounded(Color.parseColor("#DDE3EE"), 3)
        sheet.addView(handle, lp(dp(56), dp(6)))

        val t = boldLabel("Share as", 32f, NAVY)
        t.gravity = Gravity.CENTER
        t.setPadding(0, dp(24), 0, dp(4))
        sheet.addView(t, lp(MATCH, WRAP))
        val s = label("Choose the format you want to send", 17f, GRAY)
        s.gravity = Gravity.CENTER
        sheet.addView(s, lp(MATCH, WRAP))

        val cards = LinearLayout(this)
        cards.orientation = LinearLayout.HORIZONTAL
        val pdfCardView = formatCard(
            7, "PDF", Color.parseColor("#EEF6FF"), Color.parseColor("#D7E9FB"),
            Color.parseColor("#2680EB"), Color.WHITE
        ) {
            dlg.dismiss()
            doShare(true)
        }
        val c1 = lp(0, WRAP, 1f)
        c1.rightMargin = dp(8)
        cards.addView(pdfCardView, c1)
        val jpgCardView = formatCard(
            2, "JPG", Color.parseColor("#FFF9E6"), Color.parseColor("#FCEFC0"),
            Color.parseColor("#FFB800"), Color.parseColor("#1A1A1A")
        ) {
            dlg.dismiss()
            doShare(false)
        }
        val c2 = lp(0, WRAP, 1f)
        c2.leftMargin = dp(8)
        cards.addView(jpgCardView, c2)
        val cl = lp(MATCH, WRAP)
        cl.topMargin = dp(24)
        sheet.addView(cards, cl)

        val cancel = boldLabel("Cancel", 22f, GRAY)
        cancel.gravity = Gravity.CENTER
        cancel.setPadding(0, dp(22), 0, dp(10))
        cancel.setOnClickListener { dlg.dismiss() }
        sheet.addView(cancel, lp(MATCH, WRAP))

        dlg.setContentView(sheet)
        val w = dlg.window
        if (w != null) {
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.BOTTOM)
            w.setDimAmount(0.5f)
        }
        dlg.show()
    }

    private fun doShare(asPdf: Boolean) {
        if (busy) return
        busy = true
        val base = baseName()
        scanName = base
        val files = edited.toList()
        toast("Preparing to share...")
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
                    toast("Share error: $er")
                } else {
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

    // Save needs media permission, then shows an ad, then saves.
    private fun doSave(asPdf: Boolean) {
        withMedia { runWithAd { doSaveNow(asPdf) } }
    }

    private fun doSaveNow(asPdf: Boolean) {
        if (busy) return
        busy = true
        val base = baseName()
        scanName = base
        val files = edited.toList()
        toast("Saving...")
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
                if (er != null) toast("Save error: $er") else toast(ok)
            }
        }
    }

    // ---------- Stage 1: Home / Import / All PDFs ----------

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

    private fun sharePdfUri(uri: Uri) {
        try {
            val i = Intent(Intent.ACTION_SEND)
            i.type = "application/pdf"
            i.putExtra(Intent.EXTRA_STREAM, uri)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "Share"))
        } catch (e: Throwable) {
            toast("Share error: $e")
        }
    }

    private fun confirmDeletePdf(p: PdfItem) {
        val dlg = AlertDialog.Builder(this)
            .setMessage("Do you want to delete this PDF?")
            .setPositiveButton("Yes") { _, _ ->
                try {
                    contentResolver.delete(p.uri, null, null)
                    thumbCache.remove(p.uri.toString())
                } catch (e: Throwable) {
                    toast("Could not delete this file")
                }
                if (screen == "all") showAll() else showHome()
            }
            .setNegativeButton("No", null)
            .create()
        dlg.show()
        dlg.getButton(DialogInterface.BUTTON_POSITIVE).setTextColor(BLUE)
        dlg.getButton(DialogInterface.BUTTON_NEGATIVE).setTextColor(BLUE)
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

    private fun loadThumb(p: PdfItem, iv: ImageView, meta: TextView, dateStr: String) {
        val key = p.uri.toString()
        val cached = thumbCache[key]
        if (cached != null) {
            iv.setImageBitmap(cached)
            val pc = pageCountCache[key] ?: 1
            meta.text = (if (pc == 1) "1 page" else "$pc pages") + " \u2022 " + dateStr
            return
        }
        thumbPool.execute {
            var bmp: Bitmap? = null
            var pc = 0
            try {
                val pfd = contentResolver.openFileDescriptor(p.uri, "r")
                if (pfd != null) {
                    val r = PdfRenderer(pfd)
                    pc = r.pageCount
                    if (pc > 0) {
                        val page = r.openPage(0)
                        val w = 200
                        val h = maxOf(1, (w.toFloat() * page.height / page.width).toInt())
                        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        b.eraseColor(Color.WHITE)
                        page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()
                        bmp = b
                    }
                    r.close()
                    pfd.close()
                }
            } catch (e: Throwable) {
            }
            val fb = bmp
            val fc = pc
            runOnUiThread {
                if (fb != null) {
                    thumbCache[key] = fb
                    iv.setImageBitmap(fb)
                }
                if (fc > 0) {
                    pageCountCache[key] = fc
                    meta.text = (if (fc == 1) "1 page" else "$fc pages") + " \u2022 " + dateStr
                }
            }
        }
    }

    private fun pdfCard(p: PdfItem): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.setPadding(dp(14), dp(14), dp(14), dp(14))
        card.background = rounded(Color.WHITE, 28)
        card.elevation = dp(4).toFloat()
        val cl = lp(MATCH, WRAP)
        cl.topMargin = dp(14)
        card.layoutParams = cl

        val iv = ImageView(this)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.background = rounded(Color.parseColor("#E2E8F0"), 16)
        iv.clipToOutline = true
        card.addView(iv, lp(dp(64), dp(64)))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), 0, dp(8), 0)
        val title = p.name.replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), "")
        val nm = boldLabel(title, 19f, NAVY)
        nm.maxLines = 1
        nm.ellipsize = TextUtils.TruncateAt.END
        col.addView(nm, lp(MATCH, WRAP))
        val dateStr = SimpleDateFormat("dd/MM/yyyy, HH:mm:ss", Locale.getDefault())
            .format(Date(p.dateSec * 1000L))
        val meta = label(dateStr, 14f, Color.parseColor("#6B7C93"))
        col.addView(meta, lp(MATCH, WRAP))
        card.addView(col, lp(0, WRAP, 1f))

        card.addView(
            squareBtn(6, Color.parseColor("#00A6F0"), Color.WHITE) { sharePdfUri(p.uri) },
            lp(dp(52), dp(52))
        )
        val tl = lp(dp(52), dp(52))
        tl.leftMargin = dp(10)
        card.addView(
            squareBtn(0, Color.parseColor("#EEF1F6"), Color.parseColor("#8A97AB")) { confirmDeletePdf(p) },
            tl
        )
        card.setOnClickListener { openPdf(p.uri) }
        loadThumb(p, iv, meta, dateStr)
        return card
    }

    private fun headerBg(): GradientDrawable {
        val g = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#00B8FF"), Color.parseColor("#2F7BFF"), Color.parseColor("#5B4BF2"))
        )
        val r = dp(48).toFloat()
        g.cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
        return g
    }

    private fun segItem(kind: Int, text: String, sel: Boolean, onClick: () -> Unit): View {
        val item = LinearLayout(this)
        item.orientation = LinearLayout.HORIZONTAL
        item.gravity = Gravity.CENTER
        if (sel) item.background = rounded(Color.parseColor("#00A6F0"), 24)
        val col = if (sel) Color.WHITE else Color.parseColor("#6B7C93")
        item.addView(IconView(this, kind, false, col), lp(dp(24), dp(24)))
        val t = boldLabel(text, 18f, col)
        t.setPadding(dp(10), 0, 0, 0)
        item.addView(t)
        item.setOnClickListener { onClick() }
        return item
    }

    private fun blueHeader(selected: Int): View {
        val head = LinearLayout(this)
        head.orientation = LinearLayout.VERTICAL
        head.background = headerBg()
        head.setPadding(dp(24), dp(28), dp(24), dp(28))

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val tile = FrameLayout(this)
        tile.background = rounded(Color.parseColor("#40FFFFFF"), 20)
        tile.addView(IconView(this, 3, false, Color.WHITE), FrameLayout.LayoutParams(dp(56), dp(56)))
        top.addView(tile, lp(dp(56), dp(56)))
        val tc = LinearLayout(this)
        tc.orientation = LinearLayout.VERTICAL
        tc.setPadding(dp(18), 0, 0, 0)
        tc.addView(boldLabel("Scan", 34f, Color.WHITE))
        tc.addView(label("Turn documents into PDF \u2013 instantly", 14f, Color.parseColor("#E6F0FF")))
        top.addView(tc, lp(0, WRAP, 1f))
        head.addView(top, lp(MATCH, WRAP))

        val seg = LinearLayout(this)
        seg.orientation = LinearLayout.HORIZONTAL
        seg.setPadding(dp(6), dp(6), dp(6), dp(6))
        seg.background = rounded(Color.WHITE, 32)
        seg.elevation = dp(8).toFloat()
        seg.addView(segItem(5, "Home", selected == 0) { if (screen != "home") showHome() }, lp(0, dp(48), 1f))
        seg.addView(segItem(6, "Import", selected == 1) { if (screen != "import") showImport() }, lp(0, dp(48), 1f))
        val sl = lp(MATCH, WRAP)
        sl.topMargin = dp(22)
        head.addView(seg, sl)
        return head
    }

    private fun bigCard(
        kind: Int, title: String, sub: String, c1: Int, c2: Int, shadow: Int, onClick: () -> Unit
    ): View {
        val card = FrameLayout(this)
        val bg = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2))
        bg.cornerRadius = dp(36).toFloat()
        card.background = bg
        card.clipToOutline = true
        card.elevation = dp(10).toFloat()
        card.outlineSpotShadowColor = shadow
        card.outlineAmbientShadowColor = shadow

        val deco = View(this)
        deco.background = oval(Color.parseColor("#22FFFFFF"))
        val dl = FrameLayout.LayoutParams(dp(150), dp(150), Gravity.BOTTOM or Gravity.END)
        dl.rightMargin = -dp(50)
        dl.bottomMargin = -dp(60)
        card.addView(deco, dl)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(20), dp(16), dp(22))
        val tile = FrameLayout(this)
        tile.background = rounded(Color.parseColor("#40FFFFFF"), 22)
        tile.addView(IconView(this, kind, false, Color.WHITE), FrameLayout.LayoutParams(dp(64), dp(64)))
        col.addView(tile, lp(dp(64), dp(64)))
        col.addView(View(this), lp(1, 0, 1f))
        col.addView(boldLabel(title, 28f, Color.WHITE))
        col.addView(label(sub, 16f, Color.parseColor("#F0F6FF")))
        card.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        card.setOnClickListener { onClick() }
        return card
    }

    private fun startScanMode(id: Boolean) {
        resetAll()
        idMode = id
        showCamera()
    }

    private fun showHome() {
        screen = "home"
        val pdfs = loadPdfs()
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.addView(blueHeader(0), lp(MATCH, WRAP))

        val cards = LinearLayout(this)
        cards.orientation = LinearLayout.HORIZONTAL
        cards.setPadding(dp(24), dp(28), dp(24), dp(10))
        cards.clipChildren = false
        cards.clipToPadding = false
        val c1 = lp(0, dp(220), 1f)
        c1.rightMargin = dp(8)
        cards.addView(
            bigCard(
                3, "Scan", "Document from camera",
                Color.parseColor("#3C8CFF"), Color.parseColor("#1B5BFF"), Color.parseColor("#3C8CFF")
            ) { startScanMode(false) }, c1
        )
        val c2 = lp(0, dp(220), 1f)
        c2.leftMargin = dp(8)
        cards.addView(
            bigCard(
                4, "ID Card", "Front + Back on one page",
                Color.parseColor("#FFB400"), Color.parseColor("#FF7A00"), Color.parseColor("#FF9A00")
            ) { startScanMode(true) }, c2
        )
        content.addView(cards, lp(MATCH, WRAP))

        val sec = LinearLayout(this)
        sec.orientation = LinearLayout.VERTICAL
        sec.setPadding(dp(24), dp(20), dp(24), dp(24))
        val hr = LinearLayout(this)
        hr.orientation = LinearLayout.HORIZONTAL
        hr.gravity = Gravity.CENTER_VERTICAL
        hr.addView(boldLabel("Recent PDFs", 28f, Color.parseColor("#1B2540")), lp(0, WRAP, 1f))
        val seeAll = boldLabel("See all", 17f, Color.parseColor("#1E88E5"))
        seeAll.setPadding(dp(8), dp(8), 0, dp(8))
        seeAll.setOnClickListener { showAll() }
        hr.addView(seeAll)
        sec.addView(hr, lp(MATCH, WRAP))
        if (pdfs.isEmpty()) {
            val e = label("No scans yet", 16f, Color.parseColor("#9CA3AF"))
            e.gravity = Gravity.CENTER
            e.setPadding(0, dp(36), 0, dp(36))
            sec.addView(e, lp(MATCH, WRAP))
        } else {
            for (p in pdfs.take(5)) sec.addView(pdfCard(p))
        }
        content.addView(sec, lp(MATCH, WRAP))

        val sv = ScrollView(this)
        sv.setBackgroundColor(Color.parseColor("#F6F8FD"))
        sv.addView(content)
        setScreen(sv, Color.parseColor("#07B3FF"), Color.parseColor("#F6F8FD"), false, true)
    }

    private fun showImport() {
        screen = "import"
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.addView(blueHeader(1), lp(MATCH, WRAP))

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.setPadding(dp(20), dp(28), dp(20), dp(28))
        card.background = rounded(Color.WHITE, 28)
        card.elevation = dp(4).toFloat()
        val tile = FrameLayout(this)
        tile.background = rounded(Color.parseColor("#E3F2FD"), 24)
        tile.addView(
            IconView(this, 7, false, Color.parseColor("#1E88E5")),
            FrameLayout.LayoutParams(dp(84), dp(84))
        )
        card.addView(tile, lp(dp(84), dp(84)))
        val t = boldLabel("Choose a PDF from your phone", 18f, NAVY)
        t.gravity = Gravity.CENTER
        t.setPadding(0, dp(16), 0, dp(6))
        card.addView(t, lp(MATCH, WRAP))
        val s = label("Imported files appear in Recent PDFs.", 14f, GRAY)
        s.gravity = Gravity.CENTER
        card.addView(s, lp(MATCH, WRAP))
        val b = pill("Choose PDF", Color.parseColor("#00A6F0")) { pdfPicker.launch(arrayOf("application/pdf")) }
        val bl = lp(MATCH, dp(56))
        bl.topMargin = dp(22)
        card.addView(b, bl)
        val cl = lp(MATCH, WRAP)
        cl.setMargins(dp(24), dp(28), dp(24), 0)
        content.addView(card, cl)

        val sv = ScrollView(this)
        sv.setBackgroundColor(Color.parseColor("#F6F8FD"))
        sv.addView(content)
        setScreen(sv, Color.parseColor("#07B3FF"), Color.parseColor("#F6F8FD"), false, true)
    }

    private fun showAll() {
        screen = "all"
        val pdfs = loadPdfs()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#F6F8FD"))

        val head = LinearLayout(this)
        head.orientation = LinearLayout.VERTICAL
        head.background = headerBg()
        head.setPadding(dp(24), dp(28), dp(24), dp(28))
        head.addView(boldLabel("All PDFs", 30f, Color.WHITE))
        head.addView(label("${pdfs.size} file(s)", 15f, Color.parseColor("#E6F0FF")))
        root.addView(head, lp(MATCH, WRAP))

        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(dp(24), dp(6), dp(24), dp(16))
        if (pdfs.isEmpty()) {
            val e = label("No scans yet", 16f, Color.parseColor("#9CA3AF"))
            e.gravity = Gravity.CENTER
            e.setPadding(0, dp(60), 0, dp(60))
            list.addView(e, lp(MATCH, WRAP))
        } else {
            for (p in pdfs) list.addView(pdfCard(p))
        }
        val sv = ScrollView(this)
        sv.addView(list)
        root.addView(sv, lp(MATCH, 0, 1f))

        val bar = LinearLayout(this)
        bar.setPadding(dp(16), dp(8), dp(16), dp(20))
        bar.addView(pill("Back", Color.parseColor("#78909C")) { showHome() }, lp(dp(110), dp(52)))
        root.addView(bar)
        setScreen(root, Color.parseColor("#07B3FF"), Color.parseColor("#F6F8FD"), false, true)
    }

    // ---------- Stage 2: camera screen ----------

    private fun leaveCamera() {
        stopCamera()
        resetAll()
        showHome()
    }

    private fun showCamera() {
        screen = "camera"
        resetAutoState()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(DARK)

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(dp(14), dp(12), dp(14), dp(10))
        top.addView(circleBtn(14) { leaveCamera() }, lp(dp(44), dp(44)))

        val mode = boldLabel(if (idMode) "ID Card Scan" else "Document Scan", 15f, Color.WHITE)
        mode.gravity = Gravity.CENTER
        mode.setPadding(dp(16), 0, dp(16), 0)
        mode.background = rounded(CIRCLE_BG, 22)
        val ml = lp(WRAP, dp(44))
        ml.leftMargin = dp(10)
        top.addView(mode, ml)
        top.addView(View(this), lp(0, 1, 1f))

        val autoPill = LinearLayout(this)
        autoPill.orientation = LinearLayout.HORIZONTAL
        autoPill.gravity = Gravity.CENTER
        autoPill.setPadding(dp(12), 0, dp(14), 0)
        autoPill.background = rounded(CIRCLE_BG, 22)
        val ai = IconView(this, 11, false, Color.WHITE)
        autoIcon = ai
        autoPill.addView(ai, lp(dp(22), dp(22)))
        val al = boldLabel("Auto", 15f, Color.WHITE)
        al.setPadding(dp(6), 0, 0, 0)
        autoLabel = al
        autoPill.addView(al)
        autoPill.setOnClickListener {
            autoMode = !autoMode
            updateAutoPill()
            if (!autoMode) detectView?.update(null, 0.75f, idleHint())
        }
        top.addView(autoPill, lp(WRAP, dp(44)))
        updateAutoPill()

        val flashBtn = FrameLayout(this)
        flashBtn.background = oval(CIRCLE_BG)
        val fi = IconView(this, 9, false, if (torchOn) Color.YELLOW else Color.WHITE)
        flashIcon = fi
        flashBtn.addView(fi, FrameLayout.LayoutParams(dp(44), dp(44)))
        flashBtn.setOnClickListener { toggleTorch() }
        val fl = lp(dp(44), dp(44))
        fl.leftMargin = dp(8)
        top.addView(flashBtn, fl)
        root.addView(top, lp(MATCH, WRAP))

        val container = FrameLayout(this)
        val pv = PreviewView(this)
        pv.scaleType = PreviewView.ScaleType.FIT_CENTER
        previewView = pv
        container.addView(pv, FrameLayout.LayoutParams(MATCH, MATCH))

        val dv = DetectView(this)
        dv.showFrame = idMode
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
        bar.setPadding(dp(20), dp(10), dp(20), dp(22))

        val galleryBox = FrameLayout(this)
        galleryBox.background = rounded(CIRCLE_BG, 20)
        galleryBox.addView(IconView(this, 2, false, Color.WHITE), FrameLayout.LayoutParams(dp(56), dp(56)))
        galleryBox.setOnClickListener { withMedia { galleryLauncher.launch("image/*") } }
        bar.addView(
            galleryBox,
            FrameLayout.LayoutParams(dp(56), dp(56), Gravity.START or Gravity.CENTER_VERTICAL)
        )

        val shutter = ShutterView(this)
        shutter.setOnClickListener { takePhoto(null) }
        bar.addView(shutter, FrameLayout.LayoutParams(dp(78), dp(78), Gravity.CENTER))

        counterView = null
        proceedBadge = null
        if (idMode) {
            val cb = TextView(this)
            cb.text = "0/2"
            cb.textSize = 18f
            cb.setTextColor(Color.WHITE)
            cb.setTypeface(null, Typeface.BOLD)
            cb.gravity = Gravity.CENTER
            cb.background = rounded(CIRCLE_BG, 20)
            cb.setOnClickListener { proceed() }
            counterView = cb
            bar.addView(
                cb,
                FrameLayout.LayoutParams(dp(56), dp(56), Gravity.END or Gravity.CENTER_VERTICAL)
            )
        } else {
            val pw = FrameLayout(this)
            val pbtn = TextView(this)
            pbtn.text = "Proceed"
            pbtn.textSize = 15f
            pbtn.setTextColor(Color.WHITE)
            pbtn.setTypeface(null, Typeface.BOLD)
            pbtn.gravity = Gravity.CENTER
            pbtn.background = rounded(GREEN, 32)
            pbtn.setOnClickListener { proceed() }
            pw.addView(pbtn, FrameLayout.LayoutParams(dp(104), dp(60), Gravity.CENTER))
            val pb = badge()
            proceedBadge = pb
            pw.addView(pb, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END))
            bar.addView(
                pw,
                FrameLayout.LayoutParams(dp(116), dp(76), Gravity.END or Gravity.CENTER_VERTICAL)
            )
        }

        root.addView(bar, lp(MATCH, dp(116)))
        setScreen(root, DARK, DARK, false, false)
        refreshOverlay()

        if (hasCamera()) {
            startCamera()
        } else {
            val miss = missingPerms()
            if (miss.isNotEmpty()) permAll.launch(miss)
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
        startAds()
        try {
            val mode = intent?.getStringExtra("mode")
            if (mode == "scan") {
                startScanMode(false)
            } else if (mode == "id") {
                startScanMode(true)
            } else {
                showHome()
                if (savedInstanceState == null) {
                    val miss = missingPerms()
                    if (miss.isNotEmpty()) permAll.launch(miss)
                }
            }
        } catch (e: Throwable) {
            showError(e)
        }
    }
}
