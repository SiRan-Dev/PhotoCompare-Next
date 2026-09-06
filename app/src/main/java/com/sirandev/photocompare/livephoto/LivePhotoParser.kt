package com.sirandev.photocompare.livephoto

import java.io.BufferedInputStream
import java.io.InputStream

/**
 * Result of inspecting a photo for embedded motion video.
 */
sealed interface LivePhotoInfo {

    /**
     * Google MicroVideo v1 spec (Xiaomi HyperOS and older Google cameras): the video is
     * appended to the JPEG; [offsetFromEnd] (GCamera:MicroVideoOffset) counts from EOF.
     */
    data class MicroVideo(val offsetFromEnd: Long, val presentationTimestampUs: Long) : LivePhotoInfo

    /**
     * Google Motion Photo v2 (Pixel, recent Samsung, OPPO): Container:Directory declares the
     * video item; the video is the last item, ending at EOF.
     */
    data class MotionPhotoV2(val videoLength: Long) : LivePhotoInfo

    /**
     * Huawei / Honor: no XMP markers; the video is appended and located by scanning the file
     * tail for an MP4 ftyp box.
     */
    data class TailVideo(val videoStartOffset: Long) : LivePhotoInfo

    /** vivo: separate same-name .mp4 file next to the JPEG. */
    data class PairedFile(val videoPath: String) : LivePhotoInfo

    data object NotLivePhoto : LivePhotoInfo
}

/**
 * Detects motion-photo structures in a photo file. Pure JVM, unit-testable; no Android
 * dependencies.
 */
object LivePhotoParser {

    private const val XMP_NAMESPACE = "http://ns.adobe.com/xap/1.0/"
    private const val XMP_NAMESPACE_BYTES = 29 // namespace + terminating NUL
    private const val MAX_SEGMENTS = 64
    private const val MAX_SKIPPED_BYTES = 2L * 1024 * 1024
    private const val TAIL_WINDOW_BYTES = 8L * 1024 * 1024

    /**
     * Parse a photo stream. [fileSize] must be the exact file size; the stream is consumed
     * freely (mark/reset not required).
     */
    fun parse(input: InputStream, fileSize: Long): LivePhotoInfo {
        if (fileSize <= 0) return LivePhotoInfo.NotLivePhoto
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input)
        val xmp = extractXmp(buffered)
        if (xmp != null) {
            parseMicroVideo(xmp, fileSize)?.let { return it }
            parseMotionPhotoV2(xmp, fileSize)?.let { return it }
        }
        // Huawei/Honor fallback: scan the file tail for an MP4 ftyp box
        findTailVideo(buffered, fileSize)?.let { return it }
        return LivePhotoInfo.NotLivePhoto
    }

    /**
     * Walk the JPEG segment chain and return the XMP packet (standard APP1, single-segment).
     * Returns null when the file is not a JPEG or carries no XMP.
     */
    fun extractXmp(input: InputStream): String? {
        val header = ByteArray(2)
        if (input.read(header) != 2) return null
        if (header[0] != 0xFF.toByte() || header[1] != 0xD8.toByte()) return null // SOI

        var segments = 0
        var skipped = 0L
        while (segments++ < MAX_SEGMENTS && skipped < MAX_SKIPPED_BYTES) {
            // segment marker: one or more 0xFF fill bytes, then the marker byte
            var b = input.read()
            if (b == -1) return null
            if (b != 0xFF) return null
            do {
                b = input.read()
                if (b == -1) return null
            } while (b == 0xFF)

            when (b) {
                0xD8, 0x01, 0xD0, 0xD1, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7 -> continue // no payload
                0xD9, 0xDA -> return null // EOI / start-of-scan: XMP must precede SOS
            }

            val lenHigh = input.read()
            val lenLow = input.read()
            if (lenHigh == -1 || lenLow == -1) return null
            val segmentLength = (lenHigh shl 8) or lenLow // includes the 2 length bytes
            if (segmentLength < 2) return null
            val payloadLength = segmentLength - 2
            skipped += payloadLength

            if (b == 0xE1 && payloadLength >= XMP_NAMESPACE_BYTES) {
                val payload = ByteArray(payloadLength)
                var read = 0
                while (read < payloadLength) {
                    val n = input.read(payload, read, payloadLength - read)
                    if (n == -1) return null
                    read += n
                }
                val ns = String(payload, 0, XMP_NAMESPACE_BYTES, Charsets.ISO_8859_1)
                if (ns == "$XMP_NAMESPACE\u0000") {
                    return String(payload, XMP_NAMESPACE_BYTES, payloadLength - XMP_NAMESPACE_BYTES, Charsets.UTF_8)
                }
            } else {
                var remaining = payloadLength
                while (remaining > 0) {
                    val n = input.skip(remaining.toLong())
                    if (n <= 0) {
                        if (input.read() == -1) return null
                        remaining--
                    } else {
                        remaining -= n.toInt()
                        skipped += n
                    }
                }
            }
        }
        return null
    }

    /** GCamera MicroVideo fields (Xiaomi HyperOS). */
    internal fun parseMicroVideo(xmp: String, fileSize: Long): LivePhotoInfo.MicroVideo? {
        if (!xmp.contains("MicroVideoEnabled")) return null
        Regex("""GCamera:MicroVideoEnabled\s*=\s*"(True|true|1)"""").find(xmp) ?: return null
        val offset = Regex("""GCamera:MicroVideoOffset\s*=\s*"(-?\d+)"""").find(xmp) ?: return null
        val ts = Regex("""GCamera:MicroVideoPresentationTimestampUs\s*=\s*"(-?\d+)"""").find(xmp)
        return LivePhotoInfo.MicroVideo(
            offsetFromEnd = offset.groupValues[1].toLong().coerceAtLeast(0),
            presentationTimestampUs = ts?.groupValues?.get(1)?.toLong() ?: -1L,
        ).takeIf { fileSize - it.offsetFromEnd > 0 }
    }

    /** GCamera MotionPhoto + Container:Directory (Google Motion Photo v2). */
    internal fun parseMotionPhotoV2(xmp: String, fileSize: Long): LivePhotoInfo.MotionPhotoV2? {
        if (!xmp.contains("MotionPhoto")) return null
        Regex("""GCamera:MotionPhoto\s*=\s*"(True|true|1)"""").find(xmp) ?: return null

        val itemRegex = Regex("""<Container:Item\s+([^>]*?)/?>""")
        val items = itemRegex.findAll(xmp).toList()
        // video is required to be the last item in the container directory
        for (item in items.reversed()) {
            val attrs = item.groupValues[1]
            val semantic = Regex("""Container:Semantic\s*=\s*"([^"]+)"""").find(attrs)?.groupValues?.get(1)
            if (semantic != null && (semantic.equals("MotionPhoto", true) || semantic.equals("Video", true))) {
                val length = Regex("""Container:Length\s*=\s*"(\d+)"""").find(attrs)?.groupValues?.get(1)?.toLong()
                if (length != null && length > 0 && fileSize - length > 0) {
                    return LivePhotoInfo.MotionPhotoV2(videoLength = length)
                }
                return null // declared motion photo but unusable length → fall through to tail scan
            }
        }
        return null
    }

    /**
     * Scan the file tail for an MP4 ftyp box (Huawei/Honor). The stream is read from its
     * current position; callers pass a fresh stream.
     */
    fun findTailVideo(input: InputStream, fileSize: Long): LivePhotoInfo.TailVideo? {
        val window = minOf(fileSize, TAIL_WINDOW_BYTES).toInt()
        val skipAmount = fileSize - window
        var skipped = 0L
        while (skipped < skipAmount) {
            val n = input.skip(skipAmount - skipped)
            if (n <= 0) {
                if (input.read() == -1) return null
                skipped++
            } else {
                skipped += n
            }
        }
        val tail = ByteArray(window)
        var read = 0
        while (read < window) {
            val n = input.read(tail, read, window - read)
            if (n == -1) break
            read += n
        }
        val tailLength = read

        val ftyp = byteArrayOf('f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())
        var idx = 0
        while (true) {
            idx = indexOf(tail, ftyp, idx)
            if (idx == -1) return null
            val boxStart = idx - 4
            if (boxStart >= 0) {
                val boxSize = ((tail[boxStart].toInt() and 0xFF) shl 24) or
                    ((tail[boxStart + 1].toInt() and 0xFF) shl 16) or
                    ((tail[boxStart + 2].toInt() and 0xFF) shl 8) or
                    (tail[boxStart + 3].toInt() and 0xFF)
                val distanceFromEof = fileSize - (fileSize - tailLength + boxStart)
                val validSize = boxSize > 8 && boxSize <= distanceFromEof
                val brandPrintable = (idx + 8 <= tailLength) &&
                    (idx until idx + 4).all { i ->
                        val c = tail[i + 4].toInt() and 0xFF
                        c in 0x20..0x7E
                    }
                if (validSize && brandPrintable) {
                    val absoluteBoxStart = fileSize - tailLength + boxStart
                    return LivePhotoInfo.TailVideo(videoStartOffset = absoluteBoxStart)
                }
            }
            idx++
        }
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray, from: Int): Int {
        if (from < 0 || from > data.size - pattern.size) return -1
        for (i in from..data.size - pattern.size) {
            var match = true
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }
}
