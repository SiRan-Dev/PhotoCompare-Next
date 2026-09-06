package com.sirandev.photocompare.ui.pool

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sirandev.photocompare.data.ImageBean
import com.sirandev.photocompare.data.mediastore.MediaStoreRepository
import com.sirandev.photocompare.data.prefs.PhotoComparePrefs
import com.sirandev.photocompare.data.prefs.PreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SelectImagePoolViewModel(application: Application) : AndroidViewModel(application) {

    val repository = MediaStoreRepository(application.contentResolver)
    val prefsRepository = PreferencesRepository(application)

    val prefs: StateFlow<PhotoComparePrefs> = prefsRepository.prefs
        .stateIn(viewModelScope, SharingStarted.Eagerly, PhotoComparePrefs())

    private val _folders = MutableStateFlow<List<ImageBean>>(emptyList())
    val folders: StateFlow<List<ImageBean>> = _folders

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    fun refresh() {
        _isRefreshing.value = true
        viewModelScope.launch {
            try {
                _folders.value = repository.queryImageFolders(prefs.value.sortNewestFirst)
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun setSortNewestFirst(value: Boolean) {
        viewModelScope.launch {
            prefsRepository.setSortNewestFirst(value)
            refresh()
        }
    }
}
