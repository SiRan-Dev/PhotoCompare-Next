package com.sirandev.photocompare.ui.pool

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.sirandev.photocompare.R
import com.sirandev.photocompare.data.ImageBean
import com.sirandev.photocompare.util.Permissions
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Folder (bucket) selection screen, ported from SelectImagePoolActivity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectImagePoolScreen(
    onOpenFolder: (String) -> Unit,
    onOpenDate: (Long) -> Unit,
    onOpenAllPhotos: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: SelectImagePoolViewModel = viewModel(),
) {
    val folders by viewModel.folders.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    var hasMediaAccess by remember { mutableStateOf(Permissions.hasMediaAccess(viewModel.getApplication())) }
    var showDatePicker by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasMediaAccess = Permissions.hasMediaAccess(viewModel.getApplication())
        if (hasMediaAccess) viewModel.refresh()
    }

    // auto-load on first entry (the original app refreshed in onCreate); re-runs after
    // permission changes, so a cold start with granted permissions shows folders immediately
    LaunchedEffect(hasMediaAccess) {
        if (hasMediaAccess && folders.isEmpty() && !isRefreshing) {
            viewModel.refresh()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { showDatePicker = true }) {
                        Icon(Icons.Filled.CalendarMonth, contentDescription = stringResource(R.string.title_date_select))
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_loading_images))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.action_settings))
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.padding(padding),
        ) {
            when {
                !hasMediaAccess -> PermissionGate(
                    onRequest = { permissionLauncher.launch(buildPermissionRequestList()) },
                    onManagePartialAccess = { permissionLauncher.launch(arrayOf(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)) },
                    showManagePartialAccess = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                )

                folders.isEmpty() && !isRefreshing -> EmptyState()

                else -> FolderGrid(
                    folders = folders,
                    onFolderClick = onOpenFolder,
                    onOpenAllPhotos = onOpenAllPhotos,
                )
            }
        }
    }

    if (showDatePicker) {
        DatePickerWithApply(
            onDismiss = { showDatePicker = false },
            onApply = { millis ->
                showDatePicker = false
                onOpenDate(millis)
            },
        )
    }
}

private fun buildPermissionRequestList(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

@Composable
private fun PermissionGate(
    onRequest: () -> Unit,
    onManagePartialAccess: () -> Unit,
    showManagePartialAccess: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = stringResource(R.string.permissions_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            text = stringResource(R.string.permissions_explanation),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
        Button(onClick = onRequest) { Text(text = stringResource(R.string.action_select_imagepool)) }
        if (showManagePartialAccess) {
            TextButton(onClick = onManagePartialAccess) { Text(text = stringResource(R.string.update_media_access)) }
        }
    }
}

@Composable
private fun EmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = stringResource(R.string.action_select_image))
    }
}

@Composable
private fun FolderGrid(
    folders: List<ImageBean>,
    onFolderClick: (String) -> Unit,
    onOpenAllPhotos: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 108.dp),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "all-photos") {
            AllPhotosCard(onClick = onOpenAllPhotos)
        }
        items(items = folders, key = { it.fileUri.toString() }) { folder ->
            FolderCard(folder = folder, onClick = {
                // the folder query filters on the absolute DATA path, so pass the cover
                // image's parent directory rather than the bucket display name
                val p = folder.fileUri.path
                val folderPath: String? = if (p != null) File(p).parent else null
                if (folderPath != null) {
                    onFolderClick(folderPath)
                } else {
                    onFolderClick(folder.displayName)
                }
            })
        }
    }
}

/** First entry of the folder grid: opens every folder as one comparison pool. */
@Composable
private fun AllPhotosCard(onClick: () -> Unit) {
    Card(onClick = onClick) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .size(108.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Icon(
                    imageVector = Icons.Filled.PhotoLibrary,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp),
                )
            }
            Text(
                text = stringResource(R.string.all_photos),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                modifier = Modifier.padding(6.dp),
            )
        }
    }
}

@Composable
private fun FolderCard(folder: ImageBean, onClick: () -> Unit) {
    Card(onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            AsyncImage(
                model = folder.fileUri,
                contentDescription = folder.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().size(108.dp),
            )
            Text(
                text = folder.displayName,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                modifier = Modifier.padding(6.dp),
            )
        }
    }
}

@Composable
private fun DatePickerWithApply(
    onDismiss: () -> Unit,
    onApply: (Long) -> Unit,
) {
    val datePickerState = rememberDatePickerState()
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    datePickerState.selectedDateMillis?.let(onApply)
                },
                enabled = datePickerState.selectedDateMillis != null,
            ) { Text(text = stringResource(R.string.submit_date_selection)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(android.R.string.cancel)) }
        },
    ) {
        DatePicker(state = datePickerState)
        val selected = datePickerState.selectedDateMillis
        if (selected != null) {
            Text(
                text = dateFormat.format(Date(selected)),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}
