package com.sirandev.photocompare.data

/**
 * Which images to load into an image pool: a folder (bucket) or everything taken on a given day.
 */
sealed interface ImagePoolQuery {

    /** @param folderPath absolute directory path, used as a `DATA like 'path%'` filter */
    data class ByFolder(val folderPath: String) : ImagePoolQuery

    /** @param dayStartMillis start of day in UTC millis (DATE_TAKEN basis) */
    data class ByDate(val dayStartMillis: Long) : ImagePoolQuery
}
