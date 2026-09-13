package com.sirandev.photocompare.data.mediastore

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import com.sirandev.photocompare.data.ImageBean
import com.sirandev.photocompare.data.ImagePoolQuery
import java.io.File
import java.util.Calendar
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MediaStore access, ported from the original ImageMediaQueryStore / FolderImageMediaResolver /
 * FileImageMediaResolver classes.
 */
class MediaStoreRepository(private val contentResolver: ContentResolver) {

    suspend fun queryImageFolders(sortNewToOld: Boolean): List<ImageBean> = withContext(Dispatchers.IO) {
        val firstImagePerBucket = LinkedHashMap<String, ImageBean>()
        runQuery(orderBy = if (sortNewToOld) ORDER_BY_DATE_TAKEN_DESC else ORDER_BY_DATE_TAKEN_ASC) { bucket, imagePath, contentUri ->
            if (!firstImagePerBucket.containsKey(bucket)) {
                val file = File(imagePath)
                if (file.exists()) {
                    firstImagePerBucket[bucket] = ImageBean(bucket, file.lastModified(), Uri.fromFile(file), contentUri)
                }
            }
        }
        firstImagePerBucket.values
            .sortedWith(compareBy({ it.displayName.lowercase() }))
    }

    suspend fun queryImages(
        query: ImagePoolQuery,
        sortNewToOld: Boolean,
        useFilenamesForSort: Boolean,
    ): List<ImageBean> = withContext(Dispatchers.IO) {
        val result = ArrayList<ImageBean>()
        val (selection, selectionArgs) = selectExpression(query)
        runQuery(selection, selectionArgs, if (sortNewToOld) ORDER_BY_DATE_TAKEN_DESC else ORDER_BY_DATE_TAKEN_ASC) { _, imagePath, contentUri ->
            val file = File(imagePath)
            if (file.exists()) {
                result.add(ImageBean(file.name, file.lastModified(), Uri.fromFile(file), contentUri))
            }
        }
        if (useFilenamesForSort) {
            // Some apps such as "Canon Connect" completely mess up the "date taken" timestamp when
            // copying data from the camera to the phone. For such cases, sorting by filename is
            // much more reliable.
            result.sortWith(compareBy({ it.displayName.lowercase() }))
            if (sortNewToOld) result.reverse()
        }
        result
    }

    private fun selectExpression(query: ImagePoolQuery): Pair<String?, Array<String>?> = when (query) {
        is ImagePoolQuery.ByDate -> {
            val cal = Calendar.getInstance().apply { time = Date(query.dayStartMillis) }
            truncateToStartOfDay(cal)
            val endOfDay = endOfDayMillis(cal)
            Pair(
                "${MediaStore.MediaColumns.DATE_TAKEN} >= ? and ${MediaStore.Images.ImageColumns.DATE_TAKEN} <= ?",
                arrayOf(cal.timeInMillis.toString(), endOfDay.toString()),
            )
        }

        is ImagePoolQuery.ByFolder ->
            Pair("${MediaStore.Images.ImageColumns.DATA} like ?", arrayOf("${query.folderPath}%"))

        is ImagePoolQuery.ByAll -> Pair(null, null)
    }

    private fun truncateToStartOfDay(cal: Calendar) {
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
    }

    private fun endOfDayMillis(cal: Calendar): Long {
        val end = cal.clone() as Calendar
        end.add(Calendar.DAY_OF_MONTH, 1)
        return end.timeInMillis
    }

    private fun runQuery(
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        orderBy: String,
        onRow: (bucketName: String, imagePath: String, contentUri: Uri) -> Unit,
    ) {
        contentResolver.query(
            MEDIA_CONTENT_URI,
            PROJECTION,
            selection,
            selectionArgs,
            orderBy,
        )?.use { cursor ->
            val bucketIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val dataIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                onRow(
                    cursor.getString(bucketIdx),
                    cursor.getString(dataIdx),
                    ContentUris.withAppendedId(MEDIA_CONTENT_URI, cursor.getLong(idIdx)),
                )
            }
        }
    }

    companion object {
        private val MEDIA_CONTENT_URI: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        private val PROJECTION = arrayOf(
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media._ID,
        )
        private const val ORDER_BY_DATE_TAKEN_ASC = MediaStore.Images.ImageColumns.DATE_TAKEN + ", " + MediaStore.Images.ImageColumns.DATE_ADDED
        private const val ORDER_BY_DATE_TAKEN_DESC = MediaStore.Images.ImageColumns.DATE_TAKEN + " DESC, " + MediaStore.Images.ImageColumns.DATE_ADDED + " DESC"
    }
}
