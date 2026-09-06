package com.sirandev.photocompare.data

import android.net.Uri

/**
 * Single image within an image pool, keyed by its MediaStore [contentUri].
 *
 * @param selected           user-checked via checkbox (compare screen / selection list)
 * @param initialForCompare  "mark" set by long-press on the images grid; used as the initial
 *                           top image when entering the compare screen
 */
data class ImageBean(
    val displayName: String,
    val dateTaken: Long,
    val fileUri: Uri,
    val contentUri: Uri,
    val selected: Boolean = false,
    val initialForCompare: Boolean = false,
)
