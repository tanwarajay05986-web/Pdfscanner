package com.akay.pdfscanner

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun savePdf(context: Context, source: Uri, name: String): Boolean = try {
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PDFScanner")
    }
    val target = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
    context.contentResolver.openInputStream(source)!!.use { input ->
        context.contentResolver.openOutputStream(target)!!.use { out -> input.copyTo(out) }
    }
    true
} catch (e: Exception) {
    false
}

class MainActivity : ComponentActivity() {

    private var statusView: TextView? = null
    private var listBox: LinearLayout? = null

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        try {
            if (result.resultCode == Activity.RESULT_OK) {
                val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
                val pdfUri = scan?.pdf?.uri
                if (pdfUri != null) {
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    val name = "Scan_$stamp.pdf"
                    if (savePdf(this, pdfUri, name)) {
                        statusView?.text = "Saved: Downloads/PDFScanner"
                        listBox?.addView(card(name), 0)
                    } else {
                        statusView?.text = "Save nahi ho paya"
                    }
                }
            }
        } catch (e: Throwable) {
            statusView?.text = "Error: $e"
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = dp(radius).toFloat()
        return d
    }

    private fun card(text: String): View {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 15f
        tv.setTextColor(Color.parseColor("#01579B"))
        tv.setPadding(dp(16), dp(16), dp(16), dp(16))
        tv.background = rounded(Color.parseColor("#D6EEFF"), 12)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(8)
        tv.layoutParams = lp
        return tv
    }

    private fun startScan() {
        try {
            val options = GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setPageLimit(50)
                .setResultFormats(
                    GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                    GmsDocumentScannerOptions.RESULT_FORMAT_PDF
                )
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build()
            GmsDocumentScanning.getClient(options)
                .getStartScanIntent(this)
                .addOnSuccessListener { sender ->
                    launcher.launch(IntentSenderRequest.Builder(sender).build())
                }
                .addOnFailureListener { statusView?.text = "Error: ${it.message}" }
        } catch (e: Throwable) {
            statusView?.text = "Error: $e"
        }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#F3F9FF"))

        val header = LinearLayout(this)
        header.orientation = LinearLayout.VERTICAL
        header.setBackgroundColor(Color.parseColor("#0288D1"))
        header.setPadding(dp(24), dp(48), dp(24), dp(24))

        val title = TextView(this)
        title.text = "PDF Scanner v2"
        title.textSize = 28f
        title.setTextColor(Color.WHITE)
        title.setTypeface(null, Typeface.BOLD)
        header.addView(title)

        val sub = TextView(this)
        sub.text = "Scan. Crop. Save as PDF."
        sub.textSize = 14f
        sub.setTextColor(Color.parseColor("#D6EEFF"))
        header.addView(sub)
        root.addView(header)

        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(20), dp(20), dp(20), dp(20))

        val btn = Button(this)
        btn.text = "Naya Scan Karo"
        btn.textSize = 18f
        btn.isAllCaps = false
        btn.setTextColor(Color.WHITE)
        btn.background = rounded(Color.parseColor("#0288D1"), 16)
        btn.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(60)
        )
        btn.setOnClickListener { startScan() }
        body.addView(btn)

        val status = TextView(this)
        status.text = "Scan shuru karne ke liye button dabao"
        status.textSize = 13f
        status.setTextColor(Color.parseColor("#455A64"))
        status.setPadding(0, dp(12), 0, dp(12))
        statusView = status
        body.addView(status)

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        listBox = box
        val sv = ScrollView(this)
        sv.addView(box)
        body.addView(
            sv,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )

        root.addView(
            body,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        return root
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(buildUi())
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
