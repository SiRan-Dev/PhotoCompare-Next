package com.sirandev.photocompare.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode {
    SYSTEM, LIGHT, DARK,
}

/**
 * User settings, ported from the original SharedPreferences-backed PhotoComparePreferences.
 */
data class PhotoComparePrefs(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val sortNewestFirst: Boolean = true,
    val filenamesForSort: Boolean = false,
    val showExifDetails: Boolean = true,
    val checkboxStyleDark: Boolean = true,
    val syncZoomAndPan: Boolean = true,
    val predictiveBack: Boolean = false,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "photo_compare_prefs")

class PreferencesRepository(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val SORT_NEWEST_FIRST = booleanPreferencesKey("sort_newest_first")
        val FILENAMES_FOR_SORT = booleanPreferencesKey("filenames_for_sort")
        val SHOW_EXIF_DETAILS = booleanPreferencesKey("show_exif_details")
        val CHECKBOX_STYLE_DARK = booleanPreferencesKey("checkbox_style_dark")
        val SYNC_ZOOM_AND_PAN = booleanPreferencesKey("sync_zoom_and_pan")
        val PREDICTIVE_BACK = booleanPreferencesKey("predictive_back")
    }

    val prefs: Flow<PhotoComparePrefs> = context.dataStore.data.map { p ->
        PhotoComparePrefs(
            themeMode = p[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            dynamicColor = p[Keys.DYNAMIC_COLOR] ?: true,
            sortNewestFirst = p[Keys.SORT_NEWEST_FIRST] ?: true,
            filenamesForSort = p[Keys.FILENAMES_FOR_SORT] ?: false,
            showExifDetails = p[Keys.SHOW_EXIF_DETAILS] ?: true,
            checkboxStyleDark = p[Keys.CHECKBOX_STYLE_DARK] ?: true,
            syncZoomAndPan = p[Keys.SYNC_ZOOM_AND_PAN] ?: true,
            predictiveBack = p[Keys.PREDICTIVE_BACK] ?: false,
        )
    }

    suspend fun setThemeMode(value: ThemeMode) = context.dataStore.edit { it[Keys.THEME_MODE] = value.name }
    suspend fun setDynamicColor(value: Boolean) = context.dataStore.edit { it[Keys.DYNAMIC_COLOR] = value }
    suspend fun setSortNewestFirst(value: Boolean) = context.dataStore.edit { it[Keys.SORT_NEWEST_FIRST] = value }
    suspend fun setFilenamesForSort(value: Boolean) = context.dataStore.edit { it[Keys.FILENAMES_FOR_SORT] = value }
    suspend fun setShowExifDetails(value: Boolean) = context.dataStore.edit { it[Keys.SHOW_EXIF_DETAILS] = value }
    suspend fun setCheckboxStyleDark(value: Boolean) = context.dataStore.edit { it[Keys.CHECKBOX_STYLE_DARK] = value }
    suspend fun setSyncZoomAndPan(value: Boolean) = context.dataStore.edit { it[Keys.SYNC_ZOOM_AND_PAN] = value }
    suspend fun setPredictiveBack(value: Boolean) = context.dataStore.edit { it[Keys.PREDICTIVE_BACK] = value }
}
