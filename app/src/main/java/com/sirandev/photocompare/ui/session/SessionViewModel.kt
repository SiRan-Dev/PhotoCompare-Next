package com.sirandev.photocompare.ui.session

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sirandev.photocompare.data.ImageBean
import com.sirandev.photocompare.data.ImagePoolQuery
import com.sirandev.photocompare.data.mediastore.MediaStoreRepository
import com.sirandev.photocompare.data.prefs.PhotoComparePrefs
import com.sirandev.photocompare.data.prefs.PreferencesRepository
import com.sirandev.photocompare.data.prefs.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Activity-scoped session state, replacing the original Intent/Bundle parcel chain between
 * SelectImagePool → ListImages → CompareImages → SelectedImages.
 */
class SessionViewModel(application: Application) : AndroidViewModel(application) {

    val repository = MediaStoreRepository(application.contentResolver)
    val prefsRepository = PreferencesRepository(application)

    val prefs: StateFlow<PhotoComparePrefs> = prefsRepository.prefs
        .stateIn(viewModelScope, SharingStarted.Eagerly, PhotoComparePrefs())

    /** Query defining the current image pool. */
    val currentQuery = MutableStateFlow<ImagePoolQuery?>(null)

    /** Images of the current pool, ordered per the query & sort settings. */
    val images = MutableStateFlow<List<ImageBean>>(emptyList())

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    /** Grid position to scroll to when re-entering the images screen (last compared image). */
    val lastComparedIndex = MutableStateFlow(-1)

    fun openFolder(folderPath: String) {
        currentQuery.value = ImagePoolQuery.ByFolder(folderPath)
        reloadImages()
    }

    fun openDate(dayStartMillis: Long) {
        currentQuery.value = ImagePoolQuery.ByDate(dayStartMillis)
        reloadImages()
    }

    private var loadJob: Job? = null

    /**
     * (Re)loads the image list for [currentQuery], preserving selection & mark state.
     * Re-entrant calls cancel the in-flight load first, so double entry points (folder tap +
     * screen LaunchedEffect) or rapid sort toggles never run concurrent duplicate queries.
     */
    fun reloadImages() {
        val query = currentQuery.value ?: return
        loadJob?.cancel()
        _isLoading.value = true
        loadJob = viewModelScope.launch {
            val p = prefs.value
            try {
                val previous = images.value.associateBy { it.contentUri }
                // map/copy a potentially large list off the main thread
                images.value = withContext(Dispatchers.IO) {
                    repository.queryImages(query, p.sortNewestFirst, p.filenamesForSort)
                        .map { previous[it.contentUri]?.let { prev -> it.copy(selected = prev.selected, initialForCompare = prev.initialForCompare) } ?: it }
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun setSortNewestFirst(value: Boolean) = launchPref { prefsRepository.setSortNewestFirst(value) }

    fun setFilenamesForSort(value: Boolean) = launchPref { prefsRepository.setFilenamesForSort(value) }

    fun setSyncZoomAndPan(value: Boolean) = launchPref { prefsRepository.setSyncZoomAndPan(value) }

    fun setShowExifDetails(value: Boolean) = launchPref { prefsRepository.setShowExifDetails(value) }

    fun setCheckboxStyleDark(value: Boolean) = launchPref { prefsRepository.setCheckboxStyleDark(value) }

    fun setThemeMode(value: ThemeMode) = launchPref { prefsRepository.setThemeMode(value) }

    fun setDynamicColor(value: Boolean) = launchPref { prefsRepository.setDynamicColor(value) }

    fun setPredictiveBack(value: Boolean) = launchPref { prefsRepository.setPredictiveBack(value) }

    private inline fun launchPref(crossinline block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    /** Long-press: exclusively mark one image as the initial (top) compare image. */
    fun toggleMarkForCompare(index: Int) {
        images.value = images.value.mapIndexed { i, bean ->
            if (bean.initialForCompare != (i == index)) bean.copy(initialForCompare = i == index) else bean
        }
    }

    fun setSelected(index: Int, selected: Boolean) {
        images.value = images.value.mapIndexed { i, bean ->
            if (i == index && bean.selected != selected) bean.copy(selected = selected) else bean
        }
    }

    fun invertSelection() {
        images.value = images.value.map { it.copy(selected = !it.selected) }
    }

    val selectedImages: List<ImageBean>
        get() = images.value.filter { it.selected }

    val hasSelection: Boolean
        get() = images.value.any { it.selected }

    /**
     * Determine top/bottom index for entering the compare screen from the images grid,
     * porting the original OpenCompareClickHandler + deriveInitialBottomIndex behavior:
     * - with a long-press mark: mark = top, clicked = bottom
     * - without a mark: clicked = top, bottom auto-selects the FOLLOWING image
     *   (or the previous one when the clicked image is the last)
     */
    fun compareIndexesFor(index: Int): Pair<Int, Int> {
        val list = images.value
        val marked = list.indexOfFirst { it.initialForCompare }
        if (marked >= 0 && marked != index) return marked to index
        val bottom = when {
            index + 1 < list.size -> index + 1
            index - 1 >= 0 -> index - 1
            else -> -1
        }
        return index to bottom
    }

    fun onReturnedFromCompare(topIndex: Int, bottomIndex: Int) {
        lastComparedIndex.value = if (topIndex < 0) bottomIndex else if (bottomIndex < 0) topIndex else minOf(topIndex, bottomIndex)
    }
}
