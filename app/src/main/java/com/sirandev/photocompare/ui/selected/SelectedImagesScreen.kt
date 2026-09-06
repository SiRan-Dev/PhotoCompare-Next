package com.sirandev.photocompare.ui.selected

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.sirandev.photocompare.R
import com.sirandev.photocompare.data.ImageBean
import com.sirandev.photocompare.data.ImagePoolQuery
import com.sirandev.photocompare.data.mediastore.DeleteController
import com.sirandev.photocompare.ui.session.SessionViewModel
import kotlinx.coroutines.launch

private const val LARGE_SHARE_THRESHOLD = 50

/**
 * Management of the selected images, ported from SelectedImagesActivity:
 * invert selection, delete selected / unselected, share.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectedImagesScreen(
    onBack: () -> Unit,
    sessionViewModel: SessionViewModel = viewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val images by sessionViewModel.images.collectAsState()
    val selected = images.filter { it.selected }
    val unselected = images.filter { !it.selected }

    var showMenu by remember { mutableStateOf(false) }
    var pendingDeleteSelected by remember { mutableStateOf(false) }
    var pendingDeleteUnselected by remember { mutableStateOf(false) }
    var pendingShare by remember { mutableStateOf(false) }
    var showDcimWarning by remember { mutableStateOf(false) }

    val deleteController = remember { DeleteController(context) }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            sessionViewModel.reloadImages()
        }
    }

    fun shareImages(targets: List<ImageBean>) {
        val uris = ArrayList(targets.map { it.contentUri })
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, uris[0])
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_images_intent)))
    }

    fun delete(uris: List<Uri>) {
        scope.launch {
            val sender = deleteController.createBatchDeleteIntent(uris)
            if (sender != null) {
                deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
            } else {
                deleteController.deleteDirect(uris) { }
                sessionViewModel.reloadImages()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.current_selection)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(android.R.string.cancel))
                    }
                },
                actions = {
                    IconButton(onClick = { showMenu = true }, enabled = images.isNotEmpty()) {
                        Icon(Icons.Filled.MoreVert, contentDescription = null)
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.invert_selection)) },
                            leadingIcon = { Icon(Icons.Filled.Shuffle, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                sessionViewModel.invertSelection()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.delete_selected)) },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                if (selected.isNotEmpty()) pendingDeleteSelected = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.delete_unselected)) },
                            leadingIcon = { Icon(Icons.Filled.DeleteSweep, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                if (unselected.isNotEmpty()) {
                                    if (isDcimPool(sessionViewModel)) {
                                        showDcimWarning = true
                                    } else {
                                        pendingDeleteUnselected = true
                                    }
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.share_images)) },
                            leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                if (selected.isNotEmpty()) {
                                    if (selected.size > LARGE_SHARE_THRESHOLD) {
                                        pendingShare = true
                                    } else {
                                        shareImages(selected)
                                    }
                                }
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (selected.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = stringResource(R.string.action_select_image))
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 116.dp),
                contentPadding = PaddingValues(4.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                items(items = selected, key = { it.contentUri.toString() }) { bean ->
                    SelectedThumbnail(
                        bean = bean,
                        onCheckedChange = { checked ->
                            val index = images.indexOfFirst { it.contentUri == bean.contentUri }
                            if (index >= 0) sessionViewModel.setSelected(index, checked)
                        },
                    )
                }
            }
        }
    }

    if (pendingDeleteSelected) {
        ConfirmDialog(
            message = stringResource(R.string.delete_confirmation_count, selected.size),
            onConfirm = {
                pendingDeleteSelected = false
                delete(selected.map { it.contentUri })
            },
            onDismiss = { pendingDeleteSelected = false },
        )
    }
    if (pendingDeleteUnselected) {
        ConfirmDialog(
            message = stringResource(R.string.delete_unselected_confirmation_count, unselected.size),
            onConfirm = {
                pendingDeleteUnselected = false
                delete(unselected.map { it.contentUri })
            },
            onDismiss = { pendingDeleteUnselected = false },
        )
    }
    if (pendingShare) {
        AlertDialog(
            onDismissRequest = { pendingShare = false },
            title = { Text(text = stringResource(R.string.shareselected_largetx_confirmation_title)) },
            text = { Text(text = stringResource(R.string.shareselected_largetx_confirmation, selected.size)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingShare = false
                    shareImages(selected)
                }) { Text(text = stringResource(R.string.share_images)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingShare = false }) { Text(text = stringResource(android.R.string.cancel)) }
            },
        )
    }
    if (showDcimWarning) {
        AlertDialog(
            onDismissRequest = { showDcimWarning = false },
            title = { Text(text = stringResource(R.string.delete_unselected_dcim_title)) },
            text = { Text(text = stringResource(R.string.delete_unselected_dcim_message)) },
            confirmButton = {
                TextButton(onClick = { showDcimWarning = false }) { Text(text = stringResource(android.R.string.ok)) }
            },
        )
    }
}

private fun isDcimPool(sessionViewModel: SessionViewModel): Boolean {
    val query = sessionViewModel.currentQuery.value ?: return false
    return query is ImagePoolQuery.ByFolder &&
        query.folderPath.contains("/DCIM", ignoreCase = true)
}

@Composable
private fun ConfirmDialog(message: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.delete_confirmation_title)) },
        text = { Text(text = message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(text = stringResource(R.string.action_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun SelectedThumbnail(bean: ImageBean, onCheckedChange: (Boolean) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
        AsyncImage(
            model = bean.fileUri,
            contentDescription = bean.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )
        Text(
            text = bean.displayName,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
        Checkbox(checked = bean.selected, onCheckedChange = onCheckedChange)
    }
}
