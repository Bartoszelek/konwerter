package pl.konwerter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream

/** Animowany GIF z fragmentu filmu (paleta 252 kolorów z ditheringiem, kompresja LZW). */
object Gif {
    private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

    fun convert(ctx: Context, job: Job, out: File, progress: (Int) -> Unit) {
        val r = MediaMetadataRetriever()
        r.setDataSource(ctx, job.uri)
        try {
            val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val startMs = (job.gifStart * 1000L).coerceIn(0L, maxOf(0L, durMs - 500))
            val lenMs = minOf(job.gifSeconds * 1000L, durMs - startMs).coerceAtLeast(500L)
            val fps = job.gifFps
            val frames = (lenMs * fps / 1000).toInt().coerceIn(1, 900)
            val srcW = if (job.w > 0) job.w else 640
            val srcH = if (job.h > 0) job.h else 360
            val w = minOf(job.gifWidth, srcW) and 1.inv()
            val hgt = (srcH.toLong() * w / srcW).toInt().coerceAtLeast(2) and 1.inv()
            BufferedOutputStream(out.outputStream(), 1 shl 16).use { os ->
                header(os, w, hgt)
                val delay = Math.round(100f / fps)
                val idx = ByteArray(w * hgt)
                val px = IntArray(w * hgt)
                for (k in 0 until frames) {
                    if (Queue.cancel) throw InterruptedException("Anulowano")
                    val t = (startMs + k * 1000L / fps) * 1000L
                    var bm = r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST, w, hgt) ?: continue
                    // niektóre telefony zwracają klatkę bez obrotu – obracamy, gdy orientacja się nie zgadza
                    if ((bm.width > bm.height) != (w > hgt) && job.rot != 0) {
                        val m = Matrix().apply { postRotate(job.rot.toFloat()) }
                        bm = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, m, true)
                    }
                    if (bm.width != w || bm.height != hgt) bm = Bitmap.createScaledBitmap(bm, w, hgt, true)
                    bm.getPixels(px, 0, w, 0, 0, w, hgt)
                    quantize(px, idx, w, hgt)
                    frame(os, idx, w, hgt, delay)
                    progress(((k + 1) * 100 / frames).coerceAtMost(99))
                }
                os.write(0x3B)
            }
        } finally {
            try {
                r.release()
            } catch (e: Exception) {
            }
        }
    }

    private fun le16(os: OutputStream, v: Int) {
        os.write(v and 0xFF); os.write((v shr 8) and 0xFF)
    }

    private fun header(os: OutputStream, w: Int, h: Int) {
        os.write("GIF89a".toByteArray())
        le16(os, w); le16(os, h)
        os.write(0xF7); os.write(0); os.write(0)
        // paleta 6 × 7 × 6 = 252 kolory + 4 puste
        for (r in 0 until 6) for (g in 0 until 7) for (b in 0 until 6) {
            os.write(r * 255 / 5); os.write(g * 255 / 6); os.write(b * 255 / 5)
        }
        repeat(4) { os.write(0); os.write(0); os.write(0) }
        // zapętlenie w nieskończoność
        os.write(0x21); os.write(0xFF); os.write(11)
        os.write("NETSCAPE2.0".toByteArray())
        os.write(3); os.write(1); le16(os, 0); os.write(0)
    }

    private fun quantize(px: IntArray, idx: ByteArray, w: Int, h: Int) {
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val c = px[i]
            val d = BAYER[(y and 3) * 4 + (x and 3)] / 16f - 0.47f
            val r = ((((c shr 16) and 0xFF) / 51f) + d).toInt().coerceIn(0, 5)
            val g = ((((c shr 8) and 0xFF) / 42.5f) + d).toInt().coerceIn(0, 6)
            val b = (((c and 0xFF) / 51f) + d).toInt().coerceIn(0, 5)
            idx[i] = (r * 42 + g * 6 + b).toByte()
        }
    }

    private fun frame(os: OutputStream, idx: ByteArray, w: Int, h: Int, delay: Int) {
        os.write(0x21); os.write(0xF9); os.write(4); os.write(0x04); le16(os, delay); os.write(0); os.write(0)
        os.write(0x2C); le16(os, 0); le16(os, 0); le16(os, w); le16(os, h); os.write(0)
        os.write(8)
        Lzw(idx, os).encode()
        os.write(0)
    }

    /** Klasyczny koder LZW dla GIF (kody 9–12 bitów). */
    private class Lzw(private val data: ByteArray, private val os: OutputStream) {
        private val BITS = 12
        private val HSIZE = 5003
        private val maxMax = 1 shl BITS
        private val htab = IntArray(HSIZE)
        private val codetab = IntArray(HSIZE)
        private val initBits = 9
        private var nBits = initBits
        private var maxcode = (1 shl nBits) - 1
        private val clearCode = 256
        private val eofCode = 257
        private var freeEnt = 258
        private var clearFlg = false
        private var curAccum = 0
        private var curBits = 0
        private val accum = ByteArray(256)
        private var aCount = 0
        private var pos = 0
        private val masks = intArrayOf(0x0000, 0x0001, 0x0003, 0x0007, 0x000F, 0x001F, 0x003F, 0x007F, 0x00FF,
            0x01FF, 0x03FF, 0x07FF, 0x0FFF, 0x1FFF, 0x3FFF, 0x7FFF, 0xFFFF)

        private fun next(): Int = if (pos < data.size) data[pos++].toInt() and 0xFF else -1

        fun encode() {
            var hshift = 0
            var fc = HSIZE
            while (fc < 65536) { hshift++; fc *= 2 }
            hshift = 8 - hshift
            htab.fill(-1)
            output(clearCode)
            var ent = next()
            outer@ while (true) {
                val c = next()
                if (c < 0) break
                val fcode = (c shl BITS) + ent
                var i = (c shl hshift) xor ent
                if (htab[i] == fcode) {
                    ent = codetab[i]
                    continue
                } else if (htab[i] >= 0) {
                    var disp = HSIZE - i
                    if (i == 0) disp = 1
                    do {
                        i -= disp
                        if (i < 0) i += HSIZE
                        if (htab[i] == fcode) {
                            ent = codetab[i]
                            continue@outer
                        }
                    } while (htab[i] >= 0)
                }
                output(ent)
                ent = c
                if (freeEnt < maxMax) {
                    codetab[i] = freeEnt++
                    htab[i] = fcode
                } else {
                    htab.fill(-1)
                    freeEnt = clearCode + 2
                    clearFlg = true
                    output(clearCode)
                }
            }
            output(ent)
            output(eofCode)
        }

        private fun charOut(b: Int) {
            accum[aCount++] = b.toByte()
            if (aCount >= 254) flush()
        }

        private fun flush() {
            if (aCount > 0) {
                os.write(aCount)
                os.write(accum, 0, aCount)
                aCount = 0
            }
        }

        private fun output(code: Int) {
            curAccum = curAccum and masks[curBits]
            curAccum = if (curBits > 0) curAccum or (code shl curBits) else code
            curBits += nBits
            while (curBits >= 8) {
                charOut(curAccum and 0xFF)
                curAccum = curAccum shr 8
                curBits -= 8
            }
            if (freeEnt > maxcode || clearFlg) {
                if (clearFlg) {
                    nBits = initBits
                    maxcode = (1 shl nBits) - 1
                    clearFlg = false
                } else {
                    nBits++
                    maxcode = if (nBits == BITS) maxMax else (1 shl nBits) - 1
                }
            }
            if (code == eofCode) {
                while (curBits > 0) {
                    charOut(curAccum and 0xFF)
                    curAccum = curAccum shr 8
                    curBits -= 8
                }
                flush()
            }
        }
    }
}
