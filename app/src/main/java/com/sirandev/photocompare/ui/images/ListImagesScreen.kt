package com.sirandev.photocompare.ui.images

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.sirandev.photocompare.R
import com.sirandev.photocompare.data.ImageBean
import com.sirandev.photocompare.ui.session.SessionViewModel

/**
 * Image grid of the current pool, ported from ListImagesActivity.
 *
 * Interactions:
 * - tap a thumbnail → enter compare screen (a long-pressed "mark" becomes the top image)
 * - long-press a thumbnail → exclusively mark it for compare (rendered at half alpha)
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ListImagesScreen(
    onBack: () -> Unit,
    onOpenCompare: (topIndex: Int, bottomIndex: Int) -> Unit,
    onShowSelection: () -> Unit,
    sessionViewModel: SessionViewModel = viewModel(),
) {
    val images by sessionViewModel.images.collectAsState()
    val isLoading by sessionViewModel.isLoading.collectAsState()
    val prefs by sessionViewModel.prefs.collectAsState()
    val lastComparedIndex by sessionViewModel.lastComparedIndex.collectAsState()
    val markedImage by sessionViewModel.markedImage.collectAsState()

    val scope = rememberCoroutineScope()
    // mark set in ANOTHER folder: the marked photo is not part of this pool's grid
    val foreignMark = markedImage?.takeIf { mark -> images.none { it.contentUri == mark.contentUri } }

    // when a cross-folder compare was exited with the system back button, bring the pool back
    LaunchedEffect(Unit) {
        sessionViewModel.restoreLibraryCompareIfActive()
    }

    var showMenu by remember { mutableStateOf(false) }
    var showLoseSelectionDialog by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()

    LaunchedEffect(Unit) {
        // openFolder() already started the load before navigation; only load on re-entry
        // with a query but no images and no load in flight (prevents duplicate queries)
        if (sessionViewModel.currentQuery.value != null &&
            images.isEmpty() &&
            !sessionViewModel.isLoading.value
        ) {
            sessionViewModel.reloadImages()
        }
    }
    // scroll to the last compared image once loaded
    LaunchedEffect(lastComparedIndex, images) {
        val idx = lastComparedIndex
        if (idx in images.indices) {
            gridState.scrollToItem(idx)
            sessionViewModel.lastComparedIndex.value = -1
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(text = stringResource(if (isLoading) R.string.action_loading_images else R.string.action_select_image))
                },
                navigationIcon = {
                    IconButton(onClick = { if (sessionViewModel.hasSelection) showLoseSelectionDialog = true else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(android.R.string.cancel))
                    }
                },
                actions = {
                    IconButton(onClick = onShowSelection, enabled = images.any { it.selected }) {
                        Icon(Icons.Filled.Compare, contentDescription = stringResource(R.string.show_selection))
                    }
                    // Box anchors the dropdown exactly to the ⋮ icon
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = null)
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(text = stringResource(R.string.show_newest_first)) },
                                trailingIcon = { Checkbox(checked = prefs.sortNewestFirst, onCheckedChange = null) },
                                onClick = { sessionViewModel.setSortNewestFirst(!prefs.sortNewestFirst) },
                            )
                            DropdownMenuItem(
                                text = { Text(text = stringResource(R.string.use_filenames_for_sort)) },
                                trailingIcon = { Checkbox(checked = prefs.filenamesForSort, onCheckedChange = null) },
                                onClick = { sessionViewModel.setFilenamesForSort(!prefs.filenamesForSort) },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 116.dp),
            contentPadding = PaddingValues(4.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            foreignMark?.let { mark ->
                item(key = "foreign-mark-banner", span = { GridItemSpan(maxLineSpan) }) {
                    CrossFolderMarkBanner(
                        displayName = mark.displayName,
                        onClear = { sessionViewModel.clearMarkForCompare() },
                    )
                }
            }
            itemsIndexed(items = images, key = { _, bean -> bean.contentUri.toString() }) { index, bean ->
                ImageThumbnail(
                    bean = bean,
                    onClick = {
                        if (sessionViewModel.isForeignMarkPresent()) {
                            // cross-folder compare: re-anchor the pair on the whole library
                            scope.launch {
                                val (top, bottom) = sessionViewModel.startCrossPoolCompare(index)
                                if (top >= 0 && bottom >= 0) onOpenCompare(top, bottom)
                            }
                        } else {
                            val (top, bottom) = sessionViewModel.compareIndexesFor(index)
                            onOpenCompare(top, bottom)
                        }
                    },
                    onLongClick = { sessionViewModel.toggleMarkForCompare(index) },
                )
            }
        }
    }

    if (showLoseSelectionDialog) {
        AlertDialog(
            onDismissRequest = { showLoseSelectionDialog = false },
            title = { Text(text = stringResource(R.string.navigation_will_lose_selection_title)) },
            text = { Text(text = stringResource(R.string.navigation_will_lose_selection)) },
            confirmButton = {
                TextButton(onClick = {
                    showLoseSelectionDialog = false
                    onBack()
                }) { Text(text = stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showLoseSelectionDialog = false }) { Text(text = stringResource(android.R.string.cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageThumbnail(
    bean: ImageBean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    // aspectRatio gives the grid cell a bounded height, so Coil decodes thumbnails at cell
    // size instead of resolving against unbounded lazy-grid main-axis constraints
    Box(modifier = Modifier.padding(4.dp)) {
        AsyncImage(
            model = bean.fileUri,
            contentDescription = bean.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .alpha(if (bean.initialForCompare) 0.5f else 1f)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        )
        if (bean.initialForCompare) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .background(MaterialTheme.colorScheme.primary, shape = CircleShape)
                    .padding(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Compare,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

/**
 * Shown at the top of a pool's grid when the session-wide mark lives in ANOTHER folder:
 * tapping any thumbnail compares it with the marked photo across folders.
 */
@Composable
private fun CrossFolderMarkBanner(displayName: String, onClear: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = stringResource(R.string.cross_folder_mark_hint, displayName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClear) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.clear_cross_folder_mark),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}
