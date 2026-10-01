package com.akay.pdfscanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
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

class MainActivity : ComponentActivity() {

    private val DARK = Color.parseColor("#1C1C1C")
    private val GREEN = Color.parseColor("#2E9E5B")
    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

    private val pages = ArrayList<File>()
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

    // photo/gallery image ko seedha karke (EXIF rotate) chhota JPEG bana deta hai
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
        showNext()
    }

    // ---------- screens ----------

    private fun showCamera() {
        screen = "camera"
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(DARK)

        // top bar: close (left) + flash (right)
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

        // preview + thumbnail overlay
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

        // bottom bar: gallery | capture | proceed
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

    // Stage 1 ka placeholder: Stage 2 mein yahan Crop + Filters aayenge
    private fun showNext() {
        screen = "next"
        stopCamera()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#F3F9FF"))
        root.setPadding(dp(24), dp(60), dp(24), dp(24))

        val t = TextView(this)
        t.text = "Stage 1 pass"
        t.textSize = 26f
        t.setTypeface(null, Typeface.BOLD)
        t.setTextColor(Color.parseColor("#01579B"))
        root.addView(t)

        val c = TextView(this)
        c.text = "${pages.size} page(s) ready. Crop aur Filters agle stage mein aayenge."
        c.textSize = 15f
        c.setTextColor(Color.parseColor("#455A64"))
        c.setPadding(0, dp(8), 0, dp(16))
        root.addView(c)

        val hs = HorizontalScrollView(this)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        for (p in pages) {
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
        back.text = "Camera par wapas"
        back.textSize = 16f
        back.setTextColor(Color.WHITE)
        back.gravity = Gravity.CENTER
        back.background = rounded(Color.parseColor("#0288D1"), 14)
        back.setOnClickListener { showCamera() }
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
                if (screen == "camera") finish() else showCamera()
            }
        })
        try {
            showCamera()
        } catch (e: Throwable) {
            showError(e)
        }
    }
}
