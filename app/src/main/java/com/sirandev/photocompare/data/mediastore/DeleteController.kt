package com.sirandev.photocompare.data.mediastore

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Image deletion with the two device paths from the original app:
 * - API 30+: batched [MediaStore.createDeleteRequest] confirmed by the user
 * - API < 30: direct contentResolver deletes with progress reporting
 */
class DeleteController(private val context: Context) {

    /**
     * Create a user-confirmation intent for deleting [uris] (API 30+). Returns null when the
     * device takes the legacy path or the request could not be created.
     */
    suspend fun createBatchDeleteIntent(uris: List<Uri>): IntentSender? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext null
        if (uris.isEmpty()) return@withContext null
        runCatching {
            MediaStore.createDeleteRequest(
                context.contentResolver,
                ArrayList(uris),
            ).intentSender
        }.getOrNull()
    }

    /** Legacy direct deletion (API < 30). Reports progress per image. */
    suspend fun deleteDirect(uris: List<Uri>, onProgress: (Int) -> Unit): Int = withContext(Dispatchers.IO) {
        var deleted = 0
        uris.forEachIndexed { index, uri ->
            runCatching {
                if (context.contentResolver.delete(uri, null, null) > 0) deleted++
            }
            onProgress(index + 1)
        }
        deleted
    }
}
