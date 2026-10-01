package com.akay.pdfscanner

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

class MainActivity : ComponentActivity() {

    private val BLUE = Color.parseColor("#0288D1")
    private val LIGHT = Color.parseColor("#D6EEFF")
    private val BG = Color.parseColor("#F3F9FF")
    private val GREEN = Color.parseColor("#2E7D32")
    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

    private val pages = ArrayList<File>()
    private var screen = "home"
    private var savedUri: Uri? = null
    private var savedName = ""

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        try {
            if (result.resultCode == Activity.RESULT_OK) {
                val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
                val list = scan?.pages
                if (list != null) {
                    for (p in list) {
                        val f = importPage(p.imageUri)
                        if (f != null) pages.add(f)
                    }
                    showEditor()
                }
            }
        } catch (e: Throwable) {
            toast("Error: $e")
        }
    }

    private fun toast(m: String) {
        Toast.makeText(this, m, Toast.LENGTH_LONG).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

    private fun rounded(color: Int, radius: Int, stroke: Int? = null): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = dp(radius).toFloat()
        if (stroke != null) d.setStroke(dp(2), stroke)
        return d
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight)

    private fun makeButton(
        text: String, bg: Int, fg: Int, stroke: Int? = null, onClick: () -> Unit
    ): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.textSize = 16f
        b.setTextColor(fg)
        b.background = rounded(bg, 14, stroke)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun header(title: String, sub: String): View {
        val h = LinearLayout(this)
        h.orientation = LinearLayout.VERTICAL
        h.setBackgroundColor(BLUE)
        h.setPadding(dp(24), dp(44), dp(24), dp(20))
        val t = TextView(this)
        t.text = title
        t.textSize = 26f
        t.setTextColor(Color.WHITE)
        t.setTypeface(null, Typeface.BOLD)
        h.addView(t)
        val s = TextView(this)
        s.text = sub
        s.textSize = 14f
        s.setTextColor(LIGHT)
        h.addView(s)
        return h
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.setTypeface(null, Typeface.BOLD)
        return t
    }

    private fun pagesDir(): File {
        val d = File(filesDir, "pages")
        d.mkdirs()
        return d
    }

    private fun clearPages() {
        for (f in pages) f.delete()
        pages.clear()
    }

    private fun importPage(uri: Uri): File? {
        return try {
            val opts = BitmapFactory.Options()
            opts.inJustDecodeBounds = true
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            var sample = 1
            val maxSide = maxOf(opts.outWidth, opts.outHeight)
            while (maxSide / sample > 2400) sample *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = sample
            val bmp = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, o2)
            } ?: return null
            val f = File(pagesDir(), "p_${System.nanoTime()}.jpg")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            bmp.recycle()
            f
        } catch (e: Throwable) {
            null
        }
    }

    private fun thumb(f: File): Bitmap? {
        return try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeFile(f.absolutePath, o)
            var s = 1
            while (maxOf(o.outWidth, o.outHeight) / s > 500) s *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = s
            BitmapFactory.decodeFile(f.absolutePath, o2)
        } catch (e: Throwable) {
            null
        }
    }

    private fun startScan(limit: Int) {
        try {
            val options = GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setPageLimit(limit)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build()
            GmsDocumentScanning.getClient(options)
                .getStartScanIntent(this)
                .addOnSuccessListener { sender ->
                    launcher.launch(IntentSenderRequest.Builder(sender).build())
                }
                .addOnFailureListener { toast("Scanner error: ${it.message}") }
        } catch (e: Throwable) {
            toast("Error: $e")
        }
    }

    private fun cleanName(raw: String): String {
        var n = raw.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (n.isEmpty()) n = "Scan_" + stamp()
        if (!n.lowercase(Locale.ROOT).endsWith(".pdf")) n += ".pdf"
        return n
    }

    private fun savePdfToDownloads(name: String, files: List<File>): Uri? {
        return try {
            val values = ContentValues()
            values.put(MediaStore.Downloads.DISPLAY_NAME, name)
            values.put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            values.put(
                MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/PDFScanner"
            )
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            contentResolver.openOutputStream(uri)?.buffered()?.use { buildPdf(files, it) }
            uri
        } catch (e: Throwable) {
            null
        }
    }

    private fun renameSaved(uri: Uri, newName: String): Boolean {
        return try {
            val v = ContentValues()
            v.put(MediaStore.Downloads.DISPLAY_NAME, newName)
            contentResolver.update(uri, v, null, null) > 0
        } catch (e: Throwable) {
            false
        }
    }

    private fun rotatePage(i: Int) {
        try {
            val f = pages[i]
            val bmp = BitmapFactory.decodeFile(f.absolutePath)
            if (bmp != null) {
                val m = Matrix()
                m.postRotate(90f)
                val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                FileOutputStream(f).use { r.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                bmp.recycle()
                r.recycle()
            }
        } catch (e: Throwable) {
            toast("Rotate error: $e")
        }
        showEditor()
    }

    private fun deletePage(i: Int) {
        pages[i].delete()
        pages.removeAt(i)
        showEditor()
    }

    private fun askNameAndSave() {
        val et = EditText(this)
        et.setText("Scan_" + stamp())
        et.setSelectAllOnFocus(true)
        val box = FrameLayout(this)
        box.setPadding(dp(20), dp(8), dp(20), 0)
        box.addView(et)
        AlertDialog.Builder(this)
            .setTitle("PDF ka naam")
            .setView(box)
            .setPositiveButton("Save") { _, _ -> doSave(et.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doSave(raw: String) {
        val name = cleanName(raw)
        val snapshot = pages.toList()
        toast("Save ho rahi hai...")
        Thread {
            val uri = try {
                savePdfToDownloads(name, snapshot)
            } catch (e: Throwable) {
                null
            }
            runOnUiThread {
                if (uri != null) showResult(uri, name) else toast("Save nahi ho paya")
            }
        }.start()
    }

    private fun askRename() {
        val uri = savedUri ?: return
        val et = EditText(this)
        et.setText(savedName.removeSuffix(".pdf"))
        et.setSelectAllOnFocus(true)
        val box = FrameLayout(this)
        box.setPadding(dp(20), dp(8), dp(20), 0)
        box.addView(et)
        AlertDialog.Builder(this)
            .setTitle("Naya naam")
            .setView(box)
            .setPositiveButton("Rename") { _, _ ->
                val newName = cleanName(et.text.toString())
                if (renameSaved(uri, newName)) {
                    showResult(uri, newName)
                } else {
                    toast("Rename nahi ho paya")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sharePdf() {
        try {
            val uri = savedUri ?: return
            val i = Intent(Intent.ACTION_SEND)
            i.type = "application/pdf"
            i.putExtra(Intent.EXTRA_STREAM, uri)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "Share PDF"))
        } catch (e: Throwable) {
            toast("Share error: $e")
        }
    }

    private fun viewPdf() {
        try {
            val uri = savedUri ?: return
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uri, "application/pdf")
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        } catch (e: Throwable) {
            toast("PDF viewer nahi mila. Files app se Downloads/PDFScanner kholo.")
        }
    }

    private fun showHome() {
        screen = "home"
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)
        root.addView(header("PDF Scanner v3", "Scan. Edit. Save as PDF."))
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(20), dp(24), dp(20), dp(20))
        body.addView(
            makeButton("Naya Scan Karo", BLUE, Color.WHITE) {
                clearPages()
                startScan(50)
            },
            lp(MATCH, dp(60))
        )
        if (pages.isNotEmpty()) {
            val b = makeButton("Pages edit karo (${pages.size})", Color.WHITE, BLUE, BLUE) {
                showEditor()
            }
            body.addView(b, lp(MATCH, dp(56)).apply { topMargin = dp(12) })
        }
        val note = label(
            "Camera, flash, gallery, crop aur filters scanner screen mein milenge.",
            13f, Color.parseColor("#455A64")
        )
        note.setPadding(0, dp(16), 0, 0)
        body.addView(note)
        root.addView(body)
        setContentView(root)
    }

    private fun showEditor() {
        if (pages.isEmpty()) {
            showHome()
            return
        }
        screen = "editor"
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)
        root.addView(header("Pages (${pages.size})", "Rotate ya delete karo, phir Proceed dabao"))

        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(dp(16), dp(8), dp(16), dp(8))
        for (i in pages.indices) list.addView(pageCard(i))
        val sv = ScrollView(this)
        sv.addView(list)
        root.addView(sv, lp(MATCH, 0, 1f))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setBackgroundColor(Color.WHITE)
        bar.setPadding(dp(16), dp(12), dp(16), dp(16))
        val add = makeButton("Add Page", Color.WHITE, BLUE, BLUE) {
            if (pages.size >= 50) toast("Maximum 50 pages") else startScan(50 - pages.size)
        }
        val go = makeButton("Proceed", GREEN, Color.WHITE) { askNameAndSave() }
        bar.addView(add, lp(0, dp(56), 1f).apply { rightMargin = dp(8) })
        bar.addView(go, lp(0, dp(56), 1f).apply { leftMargin = dp(8) })
        root.addView(bar)
        setContentView(root)
    }

    private fun pageCard(i: Int): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.setPadding(dp(12), dp(12), dp(12), dp(12))
        card.background = rounded(Color.WHITE, 14)
        card.layoutParams = lp(MATCH, WRAP).apply { topMargin = dp(10) }

        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.FIT_CENTER
        val tb = thumb(pages[i])
        if (tb != null) img.setImageBitmap(tb)
        card.addView(img, lp(dp(90), dp(120)))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), 0, 0, 0)
        col.addView(label("Page ${i + 1}", 17f, Color.parseColor("#01579B"), true))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, dp(8), 0, 0)
        row.addView(makeButton("Rotate", LIGHT, BLUE) { rotatePage(i) }, lp(WRAP, dp(44)))
        row.addView(
            makeButton("Delete", Color.parseColor("#FDECEA"), Color.parseColor("#C62828")) {
                deletePage(i)
            },
            lp(WRAP, dp(44)).apply { leftMargin = dp(8) }
        )
        col.addView(row)
        card.addView(col)
        return card
    }

    private fun showResult(uri: Uri, name: String) {
        screen = "result"
        savedUri = uri
        savedName = name
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)
        root.addView(header("PDF save ho gayi", "Downloads/PDFScanner"))
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(20), dp(20), dp(20), dp(20))
        body.addView(label(name, 18f, Color.parseColor("#01579B"), true))
        val cnt = label("${pages.size} pages", 14f, Color.parseColor("#455A64"))
        cnt.setPadding(0, dp(4), 0, dp(16))
        body.addView(cnt)

        fun add(b: Button) {
            body.addView(b, lp(MATCH, dp(54)).apply { topMargin = dp(10) })
        }
        add(makeButton("Share", BLUE, Color.WHITE) { sharePdf() })
        add(makeButton("View PDF", Color.WHITE, BLUE, BLUE) { viewPdf() })
        add(makeButton("Rename", Color.WHITE, BLUE, BLUE) { askRename() })
        add(makeButton("Pages edit karo", Color.WHITE, BLUE, BLUE) { showEditor() })
        add(makeButton("Done", GREEN, Color.WHITE) {
            clearPages()
            showHome()
        })
        root.addView(body)
        setContentView(root)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (screen == "editor" || screen == "result") showHome() else finish()
            }
        })
        try {
            showHome()
        } catch (e: Throwable) {
            val tv = TextView(this)
            tv.text = "ERROR (screenshot bhejo):\n\n" + android.util.Log.getStackTraceString(e)
            tv.textSize = 11f
            tv.setPadding(24, 90, 24, 24)
            val sv = ScrollView(this)
            sv.addView(tv)
            setContentView(sv)
        }
    }
}
