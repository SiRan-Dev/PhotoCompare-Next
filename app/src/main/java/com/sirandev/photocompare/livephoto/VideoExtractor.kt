package com.sirandev.photocompare.livephoto

import android.content.Context
import com.sirandev.photocompare.data.ImageBean
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts the embedded video part of a motion photo into the app cache, keyed by a stable
 * hash so repeated long-presses reuse the same file within the session.
 */
class VideoExtractor(context: Context) {

    private val contentResolver = context.applicationContext.contentResolver
    private val cacheDir = File(context.applicationContext.cacheDir, "livephoto")

    /**
     * @return the cached/extracted video file, or null on failure
     */
    suspend fun extract(bean: ImageBean, info: LivePhotoInfo): File? {
        val fileSize = queryFileSize(bean)
        return extractInternal(bean, info, fileSize)
    }

    private fun queryFileSize(bean: ImageBean): Long {
        bean.fileUri.path?.let { path ->
            val file = File(path)
            if (file.exists()) return file.length()
        }
        return 0L
    }

    private suspend fun extractInternal(bean: ImageBean, info: LivePhotoInfo, fileSize: Long): File? = withContext(Dispatchers.IO) {
        if (info is LivePhotoInfo.PairedFile) {
            val file = File(info.videoPath)
            return@withContext if (file.exists()) file else null
        }
        val (startOffset, length) = videoRange(info, fileSize) ?: return@withContext null
        cacheDir.mkdirs()
        val outFile = File(cacheDir, cacheKey(bean, startOffset, length) + ".mp4")
        if (outFile.exists() && outFile.length() == length) {
            return@withContext outFile
        }
        val tmp = File(cacheDir, outFile.name + ".tmp")
        val ok = runCatching {
            contentResolver.openInputStream(bean.contentUri)?.use { input ->
                skipFully(input, startOffset)
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var remaining = length
                    while (remaining > 0) {
                        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                        val n = input.read(buffer, 0, toRead)
                        if (n == -1) break
                        output.write(buffer, 0, n)
                        remaining -= n
                    }
                    remaining == 0L
                } == true
            } == true
        }.getOrDefault(false)
        if (ok && tmp.renameTo(outFile)) {
            outFile
        } else {
            tmp.delete()
            null
        }
    }

    /** Byte range of the embedded video: [startOffset, length). */
    internal fun videoRange(info: LivePhotoInfo, fileSize: Long): Pair<Long, Long>? = when (info) {
        is LivePhotoInfo.MicroVideo -> {
            val start = fileSize - info.offsetFromEnd
            if (start in 0 until fileSize) start to (fileSize - start) else null
        }

        is LivePhotoInfo.MotionPhotoV2 -> {
            val start = fileSize - info.videoLength
            if (start in 0 until fileSize) start to info.videoLength else null
        }

        is LivePhotoInfo.TailVideo -> {
            val start = info.videoStartOffset
            if (start in 0 until fileSize) start to (fileSize - start) else null
        }

        is LivePhotoInfo.PairedFile -> null
        LivePhotoInfo.NotLivePhoto -> null
    }

    private fun skipFully(input: java.io.InputStream, amount: Long) {
        var remaining = amount
        while (remaining > 0) {
            val n = input.skip(remaining)
            if (n > 0) {
                remaining -= n
            } else {
                if (input.read() == -1) throw java.io.EOFException()
                remaining--
            }
        }
    }

    private fun cacheKey(bean: ImageBean, start: Long, length: Long): String =
        (bean.contentUri.toString() + "|$start|$length").fold(0) { acc, c -> acc * 31 + c.code }.toUInt().toString(16)
}
