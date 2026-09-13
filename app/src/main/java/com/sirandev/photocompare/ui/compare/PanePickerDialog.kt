package com.sirandev.photocompare.ui.compare

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.sirandev.photocompare.data.ImageBean
import com.sirandev.photocompare.data.ImagePoolQuery
import com.sirandev.photocompare.data.mediastore.MediaStoreRepository
import com.sirandev.photocompare.R
import kotlinx.coroutines.launch
import java.io.File

/**
 * Modal picker used by "replace this pane's photo": browse folders, then tap one photo.
 * The chosen photo may live in any folder — the compare screen re-anchors accordingly.
 */
@Composable
fun PanePickerDialog(
    title: String,
    sortNewestFirst: Boolean,
    filenamesForSort: Boolean,
    onDismiss: () -> Unit,
    onPicked: (ImageBean) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { MediaStoreRepository(context.contentResolver) }
    val scope = rememberCoroutineScope()

    var folders by remember { mutableStateOf<List<ImageBean>>(emptyList()) }
    var foldersLoading by remember { mutableStateOf(true) }
    var currentFolderName by remember { mutableStateOf<String?>(null) }
    var currentFolderPath by remember { mutableStateOf<String?>(null) }
    var photos by remember { mutableStateOf<List<ImageBean>>(emptyList()) }
    var photosLoading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        foldersLoading = true
        folders = runCatching { repository.queryImageFolders(sortNewestFirst) }.getOrDefault(emptyList())
        foldersLoading = false
    }

    fun openFolder(folderName: String, folderPath: String) {
        currentFolderName = folderName
        currentFolderPath = folderPath
        photosLoading = true
        photos = emptyList()
        scope.launch {
            val list = runCatching {
                repository.queryImages(ImagePoolQuery.ByFolder(folderPath), sortNewestFirst, filenamesForSort)
            }.getOrDefault(emptyList())
            photos = list
            photosLoading = false
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                ) {
                    IconButton(onClick = {
                        if (currentFolderPath != null) {
                            currentFolderPath = null
                            currentFolderName = null
                            photos = emptyList()
                        } else {
                            onDismiss()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                    Text(
                        text = currentFolderName ?: title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                HorizontalDivider()
                Box(modifier = Modifier.fillMaxSize()) {
                    if (foldersLoading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    } else if (currentFolderPath == null) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Text(
                                text = stringResource(R.string.choose_folder),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(minSize = 120.dp),
                                contentPadding = PaddingValues(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                items(items = folders, key = { it.fileUri.toString() }) { folder ->
                                    val p = folder.fileUri.path
                                    val folderPath = if (p != null) File(p).parent else null
                                    FolderPickCell(
                                        bean = folder,
                                        onClick = {
                                            val path = folderPath ?: folder.displayName
                                            openFolder(folder.displayName, path)
                                        },
                                    )
                                }
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 110.dp),
                            contentPadding = PaddingValues(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(items = photos, key = { it.contentUri.toString() }) { bean ->
                                PhotoPickCell(bean = bean, onClick = { onPicked(bean) })
                            }
                        }
                        if (photosLoading) {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderPickCell(bean: ImageBean, onClick: () -> Unit) {
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Box(modifier = Modifier.fillMaxWidth()) {
            AsyncImage(
                model = bean.fileUri,
                contentDescription = bean.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Text(
                text = bean.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                    .padding(4.dp),
            )
        }
    }
}

@Composable
private fun PhotoPickCell(bean: ImageBean, onClick: () -> Unit) {
    Box(modifier = Modifier.clickable(onClick = onClick).padding(2.dp)) {
        AsyncImage(
            model = bean.fileUri,
            contentDescription = bean.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        )
    }
}
