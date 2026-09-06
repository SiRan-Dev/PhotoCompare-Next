package com.sirandev.photocompare.livephoto

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.sirandev.photocompare.data.ImageBean
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Session-cached resolution of live-photo info for pool images. Combines the embedded-file
 * parse ([LivePhotoParser]) with the vivo paired-file convention.
 */
class ContentLivePhotoResolver(context: Context) {

    private val appContext = context.applicationContext
    private val cache = HashMap<Uri, LivePhotoInfo>()

    suspend fun resolve(bean: ImageBean): LivePhotoInfo = withContext(Dispatchers.IO) {
        synchronized(cache) {
            cache[bean.contentUri]
        } ?: resolveInternal(bean).also { info ->
            synchronized(cache) { cache[bean.contentUri] = info }
        }
    }

    private fun resolveInternal(bean: ImageBean): LivePhotoInfo {
        // vivo: same directory, same base name, .mp4 extension
        val filePath = bean.fileUri.path
        if (filePath != null) {
            val paired = File(filePath.substringBeforeLast('.') + ".mp4")
            if (paired.exists() && paired.length() > 0) {
                return LivePhotoInfo.PairedFile(paired.absolutePath)
            }
        }
        val size = queryFileSize(bean)
        if (size > 0) {
            runCatching {
                appContext.contentResolver.openInputStream(bean.contentUri)?.use { input ->
                    return LivePhotoParser.parse(input, size)
                }
            }
        }
        return LivePhotoInfo.NotLivePhoto
    }

    private fun queryFileSize(bean: ImageBean): Long {
        bean.fileUri.path?.let { path ->
            val file = File(path)
            if (file.exists()) return file.length()
        }
        return runCatching {
            appContext.contentResolver.query(
                bean.contentUri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
            } ?: 0L
        }.getOrDefault(0L)
    }
}
