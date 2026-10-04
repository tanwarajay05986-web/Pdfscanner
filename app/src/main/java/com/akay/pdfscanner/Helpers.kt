package com.akay.pdfscanner

import android.graphics.BitmapFactory
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.Locale

// ---------- PDF writer: JPEG goes straight into the PDF (small file) ----------
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

// ---------- Document detector (OpenCV) ----------
object DocDetector {
    private var ready = false
    private var failed = false

    fun available(): Boolean {
        if (ready) return true
        if (failed) return false
        try {
            ready = OpenCVLoader.initLocal()
            if (!ready) failed = true
            Log.i("PDFScanner", "OpenCV init: $ready")
        } catch (e: Throwable) {
            failed = true
            ready = false
            Log.e("PDFScanner", "OpenCV init failed", e)
        }
        return ready
    }

    // returns normalized corners TL, TR, BR, BL of the upright image, or null
    fun detect(buf: ByteBuffer, w: Int, h: Int, rowStride: Int, rot: Int): FloatArray? {
        val src = Mat(h, w, CvType.CV_8UC1)
        val up = Mat()
        val small = Mat()
        try {
            val row = ByteArray(w)
            for (y in 0 until h) {
                buf.position(y * rowStride)
                buf.get(row, 0, w)
                src.put(y, 0, row)
            }
            when (rot) {
                90 -> Core.rotate(src, up, Core.ROTATE_90_CLOCKWISE)
                180 -> Core.rotate(src, up, Core.ROTATE_180)
                270 -> Core.rotate(src, up, Core.ROTATE_90_COUNTERCLOCKWISE)
                else -> src.copyTo(up)
            }
            val scale = 240.0 / maxOf(up.cols(), up.rows())
            Imgproc.resize(up, small, Size(up.cols() * scale, up.rows() * scale))
            return findDoc(small)
        } finally {
            src.release()
            up.release()
            small.release()
        }
    }

    private fun findDoc(gray: Mat): FloatArray? {
        val w = gray.cols()
        val h = gray.rows()
        val blur = Mat()
        val edges = Mat()
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        try {
            Imgproc.GaussianBlur(gray, blur, Size(5.0, 5.0), 0.0)
            val mean = Core.mean(blur).`val`[0]
            val low = (0.4 * mean).coerceIn(25.0, 70.0)
            Imgproc.Canny(blur, edges, low, low * 2.5)
            Imgproc.dilate(edges, edges, k)
            var best = bestQuad(edges, w, h)
            if (best == null) {
                val bin = Mat()
                Imgproc.threshold(blur, bin, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
                best = bestQuad(bin, w, h)
                if (best == null) {
                    Core.bitwise_not(bin, bin)
                    best = bestQuad(bin, w, h)
                }
                bin.release()
            }
            return best
        } finally {
            blur.release()
            edges.release()
            k.release()
        }
    }

    private fun bestQuad(bin: Mat, w: Int, h: Int): FloatArray? {
        val contours = ArrayList<MatOfPoint>()
        val hier = Mat()
        Imgproc.findContours(bin, contours, hier, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        hier.release()
        val frame = (w * h).toDouble()
        var bestArea = 0.0
        var best: FloatArray? = null
        for (c in contours) {
            val area = Imgproc.contourArea(c)
            if (area < frame * 0.18 || area > frame * 0.97 || area <= bestArea) {
                c.release()
                continue
            }
            val c2f = MatOfPoint2f(*c.toArray())
            val peri = Imgproc.arcLength(c2f, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true)
            if (approx.total() == 4L) {
                val pts = approx.toArray()
                if (Imgproc.isContourConvex(MatOfPoint(*pts))) {
                    bestArea = area
                    best = orderQuad(pts, w, h)
                }
            }
            c2f.release()
            approx.release()
            c.release()
        }
        return best
    }

    private fun orderQuad(p: Array<Point>, w: Int, h: Int): FloatArray {
        var tl = p[0]
        var br = p[0]
        var tr = p[0]
        var bl = p[0]
        for (q in p) {
            if (q.x + q.y < tl.x + tl.y) tl = q
            if (q.x + q.y > br.x + br.y) br = q
            if (q.x - q.y > tr.x - tr.y) tr = q
            if (q.x - q.y < bl.x - bl.y) bl = q
        }
        return floatArrayOf(
            (tl.x / w).toFloat(), (tl.y / h).toFloat(),
            (tr.x / w).toFloat(), (tr.y / h).toFloat(),
            (br.x / w).toFloat(), (br.y / h).toFloat(),
            (bl.x / w).toFloat(), (bl.y / h).toFloat()
        )
    }
}
