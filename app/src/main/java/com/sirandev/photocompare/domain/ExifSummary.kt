package com.sirandev.photocompare.domain

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * True source image metadata (bitmap bounds are independent of the decode size used for
 * rendering). [width]/[height] are raw pixel dimensions; [rotationSwapped] is true for
 * EXIF orientations 5/6/7/8 (display space swaps x/y).
 */
data class ImageMeta(
    val width: Float,
    val height: Float,
    val rotationSwapped: Boolean,
    val exifText: String?,
)

object ExifSummary {

    /**
     * Reads bitmap bounds + EXIF summary through the content resolver — the same channel
     * Coil uses — so it works even where direct file-path access is restricted.
     * Returns null when the image is unreadable. Must be called off the main thread.
     */
    suspend fun load(
        contentResolver: ContentResolver,
        contentUri: Uri,
        displayName: String,
        includeExifDetails: Boolean,
    ): ImageMeta? = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            contentResolver.openInputStream(contentUri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

        val exif = runCatching {
            contentResolver.openInputStream(contentUri)?.use { ExifInterface(it) }
        }.getOrNull()
        val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            ?: ExifInterface.ORIENTATION_NORMAL
        val swapped = orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_TRANSVERSE ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270

        val text = if (includeExifDetails) buildExifText(displayName, bounds, exif) else null
        ImageMeta(
            width = bounds.outWidth.toFloat(),
            height = bounds.outHeight.toFloat(),
            rotationSwapped = swapped,
            exifText = text,
        )
    }

    private fun buildExifText(displayName: String, bounds: BitmapFactory.Options, exif: ExifInterface?): String {
        val sb = StringBuilder()
        sb.append(displayName).append('\n')
        sb.append(bounds.outWidth).append(" × ").append(bounds.outHeight).append('\n')
        exif ?: return sb.toString()
        exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let { sb.append(it).append('\n') }
        exif.getAttribute(ExifInterface.TAG_MODEL)?.let { sb.append(it).append('\n') }
        exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.let { sb.append("f/").append(it).append(' ') }
        exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.let { sb.append(it).append("s ") }
        exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { sb.append("ISO ").append(it) }
        return sb.toString().trim()
    }
}
