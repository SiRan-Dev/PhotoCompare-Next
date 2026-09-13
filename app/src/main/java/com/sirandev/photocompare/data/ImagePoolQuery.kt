package com.sirandev.photocompare.data

/**
 * Which images to load into an image pool: a folder (bucket), everything taken on a given day,
 * or the whole library (all folders, useful to compare photos taken in different folders).
 */
sealed interface ImagePoolQuery {

    /** @param folderPath absolute directory path, used as a `DATA like 'path%'` filter */
    data class ByFolder(val folderPath: String) : ImagePoolQuery

    /** @param dayStartMillis start of day in UTC millis (DATE_TAKEN basis) */
    data class ByDate(val dayStartMillis: Long) : ImagePoolQuery

    /** All images across every folder, ordered per the current sort settings. */
    data object ByAll : ImagePoolQuery
}
