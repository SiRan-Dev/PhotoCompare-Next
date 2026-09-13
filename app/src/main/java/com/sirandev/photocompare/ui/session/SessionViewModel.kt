package com.sirandev.photocompare.ui.session

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sirandev.photocompare.data.ImageBean
import com.sirandev.photocompare.data.ImagePoolQuery
import com.sirandev.photocompare.data.mediastore.MediaStoreRepository
import com.sirandev.photocompare.data.prefs.PhotoCompareNextPrefs
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

    val prefs: StateFlow<PhotoCompareNextPrefs> = prefsRepository.prefs
        .stateIn(viewModelScope, SharingStarted.Eagerly, PhotoCompareNextPrefs())

    /** Query defining the current image pool. */
    val currentQuery = MutableStateFlow<ImagePoolQuery?>(null)

    /** Images of the current pool, ordered per the query & sort settings. */
    val images = MutableStateFlow<List<ImageBean>>(emptyList())

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    /** Grid position to scroll to when re-entering the images screen (last compared image). */
    val lastComparedIndex = MutableStateFlow(-1)

    /**
     * Session-wide "mark" photo (set by long-press on any pool's grid). Kept by contentUri so
     * it survives switching to another folder, enabling cross-folder comparison.
     */
    private val _markedImage = MutableStateFlow<ImageBean?>(null)
    val markedImage: StateFlow<ImageBean?> = _markedImage

    /**
     * Snapshot taken when a compare is re-anchored on the whole library because the marked
     * photo lives in a different folder than the tapped one. On leaving the compare screen the
     * pool below is restored from this snapshot (no reload flicker).
     */
    private data class PoolRestoreSnapshot(
        val query: ImagePoolQuery?,
        val images: List<ImageBean>,
        val tappedIndexInPool: Int,
    )

    private var poolRestoreSnapshot: PoolRestoreSnapshot? = null

    /** True while the compare screen shows the library list instead of the active pool. */
    val isLibraryCompareActive: Boolean
        get() = poolRestoreSnapshot != null

    /**
     * Safety net for leaving the compare screen via the SYSTEM back button (which pops the
     * navigation without the screen's own leave-compare callback): swap the library list used
     * by the cross-folder compare back to the underlying pool snapshot.
     */
    fun restoreLibraryCompareIfActive() {
        val snapshot = poolRestoreSnapshot ?: return
        poolRestoreSnapshot = null
        currentQuery.value = snapshot.query
        images.value = snapshot.images
    }

    fun openFolder(folderPath: String) {
        currentQuery.value = ImagePoolQuery.ByFolder(folderPath)
        reloadImages()
    }

    fun openDate(dayStartMillis: Long) {
        currentQuery.value = ImagePoolQuery.ByDate(dayStartMillis)
        reloadImages()
    }

    /** All photos across every folder — a single pool for cross-folder comparison. */
    fun openAllPhotos() {
        currentQuery.value = ImagePoolQuery.ByAll
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
                val markUri = _markedImage.value?.contentUri
                // map/copy a potentially large list off the main thread
                images.value = withContext(Dispatchers.IO) {
                    repository.queryImages(query, p.sortNewestFirst, p.filenamesForSort)
                        .map { previous[it.contentUri]?.let { prev -> it.copy(selected = prev.selected) } ?: it }
                        .map { if (it.contentUri == markUri) it.copy(initialForCompare = true) else it }
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

    /**
     * Long-press: exclusively mark one image for compare. The mark is session-wide (keyed by
     * contentUri), so it survives navigating into another folder.
     */
    fun toggleMarkForCompare(index: Int) {
        val bean = images.value.getOrNull(index) ?: return
        _markedImage.value = if (_markedImage.value?.contentUri == bean.contentUri) null else bean
        syncMarkFlags()
    }

    /** Remove the session-wide mark (its badge disappears from whatever pool shows it). */
    fun clearMarkForCompare() {
        _markedImage.value = null
        syncMarkFlags()
    }

    private fun syncMarkFlags() {
        val markUri = _markedImage.value?.contentUri
        images.value = images.value.map { bean ->
            if (bean.initialForCompare != (bean.contentUri == markUri)) {
                bean.copy(initialForCompare = bean.contentUri == markUri)
            } else {
                bean
            }
        }
    }

    fun setSelected(index: Int, selected: Boolean) {
        images.value = images.value.mapIndexed { i, bean ->
            if (i == index && bean.selected != selected) bean.copy(selected = selected) else bean
        }
        // a cross-folder compare is running on the library list; mirror selection onto the
        // snapshot so it survives the restore back to the underlying pool
        val snapshot = poolRestoreSnapshot ?: return
        val bean = images.value.getOrNull(index) ?: return
        if (snapshot.images.any { it.contentUri == bean.contentUri }) {
            poolRestoreSnapshot = snapshot.copy(
                images = snapshot.images.map {
                    if (it.contentUri == bean.contentUri) it.copy(selected = selected) else it
                },
            )
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

    /** True when the marked photo does not belong to the current pool (cross-folder compare). */
    fun isForeignMarkPresent(): Boolean {
        val mark = _markedImage.value ?: return false
        return images.value.none { it.contentUri == mark.contentUri }
    }

    /**
     * Cross-folder compare: the tapped photo lives in this pool while the marked photo lives in
     * another folder. Re-anchor the compare on the whole library (both photos present), keeping
     * a snapshot of this pool so it can be restored exactly on leaving the compare screen.
     *
     * @return library-list indexes (marked, tapped), or (-1, -1) when not applicable.
     */
    suspend fun startCrossPoolCompare(tappedIndex: Int): Pair<Int, Int> {
        val pool = images.value
        val tapped = pool.getOrNull(tappedIndex) ?: return -1 to -1
        val mark = _markedImage.value ?: return -1 to -1
        if (pool.any { it.contentUri == mark.contentUri }) return -1 to -1

        val p = prefs.value
        val library = withContext(Dispatchers.IO) {
            repository.queryImages(ImagePoolQuery.ByAll, p.sortNewestFirst, p.filenamesForSort)
        }
        val top = library.indexOfFirst { it.contentUri == mark.contentUri }
        val bottom = library.indexOfFirst { it.contentUri == tapped.contentUri }
        if (top < 0 || bottom < 0) return -1 to -1

        poolRestoreSnapshot = PoolRestoreSnapshot(
            query = currentQuery.value,
            images = pool,
            tappedIndexInPool = tappedIndex,
        )
        // selection flags are irrelevant on the fresh library beans except for the snapshot copy
        images.value = library.map { bean ->
            pool.firstOrNull { it.contentUri == bean.contentUri }
                ?.let { bean.copy(selected = it.selected, initialForCompare = false) }
                ?: bean
        }
        return top to bottom
    }

    /**
     * Compare-screen "replace this pane's photo": re-compute the two real indexes after one
     * pane is pointed at a new photo. When the photo belongs to the active list it is a pure
     * re-anchor; otherwise the compare is re-based on the whole library (the active pool is
     * snapshotted first so leaving the compare screen restores it exactly).
     *
     * @return (topIndex, bottomIndex) in the resulting active list, or (-1, -1).
     */
    suspend fun rebaseCompareForPaneReplace(
        currentTopReal: Int,
        currentBottomReal: Int,
        replaceTop: Boolean,
        picked: ImageBean,
    ): Pair<Int, Int> {
        val active = images.value
        if (active.isEmpty()) return -1 to -1
        if (active.none { it.contentUri == picked.contentUri }) {
            // the picked photo is not in the current list (another folder) → re-anchor on the
            // whole library so both panes stay inside one page-able list
            val otherReal = if (replaceTop) currentBottomReal else currentTopReal
            val otherUri = active.getOrNull(otherReal)?.contentUri ?: return -1 to -1
            if (poolRestoreSnapshot == null) {
                val restoreTo = if (otherReal in active.indices) otherReal else 0
                poolRestoreSnapshot = PoolRestoreSnapshot(
                    query = currentQuery.value,
                    images = active,
                    tappedIndexInPool = restoreTo,
                )
            }
            val p = prefs.value
            val library = withContext(Dispatchers.IO) {
                repository.queryImages(ImagePoolQuery.ByAll, p.sortNewestFirst, p.filenamesForSort)
            }
            val pickedIdx = library.indexOfFirst { it.contentUri == picked.contentUri }
            val otherIdx = library.indexOfFirst { it.contentUri == otherUri }
            if (pickedIdx < 0 || otherIdx < 0) return -1 to -1
            val top = if (replaceTop) pickedIdx else otherIdx
            val bottom = if (replaceTop) otherIdx else pickedIdx
            images.value = library
            return distinctPair(library.size, top, bottom)
        }
        val pickedIdx = active.indexOfFirst { it.contentUri == picked.contentUri }
        val top = if (replaceTop) pickedIdx else currentTopReal
        val bottom = if (replaceTop) currentBottomReal else pickedIdx
        return distinctPair(active.size, top, bottom)
    }

    private fun distinctPair(size: Int, top: Int, bottom: Int): Pair<Int, Int> {
        if (top != bottom) return top to bottom
        if (top !in 0 until size) return top to bottom
        val alt = if (top + 1 < size) top + 1 else if (top - 1 >= 0) top - 1 else -1
        return top to alt
    }

    fun onReturnedFromCompare(topIndex: Int, bottomIndex: Int) {
        val snapshot = poolRestoreSnapshot
        if (snapshot != null) {
            // cross-folder compare: bring back the pool grid exactly as it was
            poolRestoreSnapshot = null
            currentQuery.value = snapshot.query
            images.value = snapshot.images
            lastComparedIndex.value = snapshot.tappedIndexInPool
        } else {
            lastComparedIndex.value = if (topIndex < 0) bottomIndex else if (bottomIndex < 0) topIndex else minOf(topIndex, bottomIndex)
        }
    }
}
