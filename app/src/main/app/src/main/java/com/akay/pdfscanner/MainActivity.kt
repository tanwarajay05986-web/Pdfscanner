package com.akay.pdfscanner

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("crash", Context.MODE_PRIVATE)
        val oldCrash = prefs.getString("log", null)
        if (oldCrash != null) {
            prefs.edit().remove("log").apply()
            val tv = TextView(this)
            tv.text = "CRASH LOG (screenshot bhejo):\n\n" + oldCrash
            tv.textSize = 11f
            tv.setPadding(24, 90, 24, 24)
            val sv = ScrollView(this)
            sv.addView(tv)
            setContentView(sv)
            return
        }

        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            prefs.edit().putString("log", android.util.Log.getStackTraceString(e)).commit()
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(1)
        }

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF0288D1),
                    primaryContainer = Color(0xFFD6EEFF),
                    background = Color(0xFFF3F9FF),
                    surface = Color.White
                )
            ) {
                PdfScannerApp()
            }
        }
    }
}

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

@Composable
fun PdfScannerApp() {
    val context = LocalContext.current
    var status by remember { mutableStateOf("Scan shuru karne ke liye button dabao") }
    val saved = remember { mutableStateListOf<String>() }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val pdfUri = scan?.pdf?.uri
            if (pdfUri != null) {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val name = "Scan_$stamp.pdf"
                if (savePdf(context, pdfUri, name)) {
                    saved.add(0, name)
                    status = "Saved: Downloads/PDFScanner"
                } else {
                    status = "Save nahi ho paya"
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFF3F9FF))) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0288D1))
                .padding(top = 48.dp, bottom = 24.dp, start = 24.dp, end = 24.dp)
        ) {
            Column {
                Text("PDF Scanner", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("Scan. Crop. Save as PDF.", color = Color(0xFFD6EEFF), fontSize = 14.sp)
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = {
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
                            .getStartScanIntent(context as Activity)
                            .addOnSuccessListener { sender ->
                                launcher.launch(IntentSenderRequest.Builder(sender).build())
                            }
                            .addOnFailureListener { status = "Error: ${it.message}" }
                    } catch (e: Exception) {
                        status = "Error: ${e.message}"
                    }
                },
                modifier = Modifier.fillMaxWidth().height(60.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Naya Scan Karo", fontSize = 18.sp)
            }

            Spacer(Modifier.height(12.dp))
            Text(status, color = Color(0xFF455A64), fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))

            if (saved.isNotEmpty()) {
                Text(
                    "Is session ke scans",
                    modifier = Modifier.fillMaxWidth(),
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn {
                    items(saved) { item ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFD6EEFF))
                        ) {
                            Text(item, modifier = Modifier.padding(16.dp))
                        }
                    }
                }
            }
        }
    }
}
