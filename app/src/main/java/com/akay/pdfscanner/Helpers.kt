package com.akay.pdfscanner

import android.graphics.BitmapFactory
import java.io.File
import java.io.OutputStream
import java.util.Locale
import kotlin.math.abs

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

// ---------- Simple document detector (works on a small gray grid) ----------
object DocDetector {

    // returns normalized corners TL, TR, BR, BL or null
    fun detect(gray: IntArray, gw: Int, gh: Int): FloatArray? {
        val n = gw * gh
        val hist = IntArray(256)
        for (v in gray) hist[v.coerceIn(0, 255)]++
        var total = 0L
        for (t in 0 until 256) total += t.toLong() * hist[t]
        var sumB = 0L
        var wB = 0
        var best = -1.0
        var thr = 128
        for (t in 0 until 256) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = n - wB
            if (wF == 0) break
            sumB += t.toLong() * hist[t]
            val mB = sumB.toDouble() / wB
            val mF = (total - sumB).toDouble() / wF
            val between = wB.toDouble() * wF.toDouble() * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                thr = t
            }
        }
        var c0 = 0
        var s0 = 0L
        var c1 = 0
        var s1 = 0L
        for (v in gray) {
            if (v <= thr) {
                c0++
                s0 += v
            } else {
                c1++
                s1 += v
            }
        }
        if (c0 == 0 || c1 == 0) return null
        if (s1.toDouble() / c1 - s0.toDouble() / c0 < 40.0) return null
        val a = findQuad(gray, gw, gh, thr, true)
        if (a != null) return a
        return findQuad(gray, gw, gh, thr, false)
    }

    private fun polyArea(q: FloatArray): Float {
        var a = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            a += q[2 * i] * q[2 * j + 1] - q[2 * j] * q[2 * i + 1]
        }
        return abs(a) / 2f
    }

    private fun findQuad(gray: IntArray, gw: Int, gh: Int, thr: Int, bright: Boolean): FloatArray? {
        val n = gw * gh
        val mask = BooleanArray(n)
        for (i in 0 until n) mask[i] = if (bright) gray[i] > thr else gray[i] <= thr
        val seen = BooleanArray(n)
        val stack = IntArray(n)
        var bestArea = 0
        var best: FloatArray? = null
        for (start in 0 until n) {
            if (!mask[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var area = 0
            var minS = Int.MAX_VALUE
            var maxS = Int.MIN_VALUE
            var minD = Int.MAX_VALUE
            var maxD = Int.MIN_VALUE
            var tl = start
            var br = start
            var tr = start
            var bl = start
            while (sp > 0) {
                sp--
                val p = stack[sp]
                area++
                val x = p % gw
                val y = p / gw
                val s = x + y
                val d = x - y
                if (s < minS) {
                    minS = s
                    tl = p
                }
                if (s > maxS) {
                    maxS = s
                    br = p
                }
                if (d > maxD) {
                    maxD = d
                    tr = p
                }
                if (d < minD) {
                    minD = d
                    bl = p
                }
                if (x > 0 && mask[p - 1] && !seen[p - 1]) {
                    seen[p - 1] = true
                    stack[sp++] = p - 1
                }
                if (x < gw - 1 && mask[p + 1] && !seen[p + 1]) {
                    seen[p + 1] = true
                    stack[sp++] = p + 1
                }
                if (y > 0 && mask[p - gw] && !seen[p - gw]) {
                    seen[p - gw] = true
                    stack[sp++] = p - gw
                }
                if (y < gh - 1 && mask[p + gw] && !seen[p + gw]) {
                    seen[p + gw] = true
                    stack[sp++] = p + gw
                }
            }
            if (area <= bestArea) continue
            if (area < n * 0.2 || area > n * 0.92) continue
            val q = floatArrayOf(
                (tl % gw).toFloat() / gw, (tl / gw).toFloat() / gh,
                ((tr % gw) + 1f) / gw, (tr / gw).toFloat() / gh,
                ((br % gw) + 1f) / gw, ((br / gw) + 1f) / gh,
                (bl % gw).toFloat() / gw, ((bl / gw) + 1f) / gh
            )
            val qa = polyArea(q)
            if (qa < 0.2f) continue
            val fill = (area.toFloat() / n) / qa
            if (fill < 0.72f) continue
            bestArea = area
            best = q
        }
        return best
    }
}
