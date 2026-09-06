package com.sirandev.photocompare.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sirandev.photocompare.R
import com.sirandev.photocompare.data.prefs.ThemeMode
import com.sirandev.photocompare.ui.session.SessionViewModel

/**
 * App settings: theme, predictive-back gesture, project link. All controls are official
 * Material 3 components (SegmentedButton / ListItem / Switch) driven by [SessionViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    sessionViewModel: SessionViewModel = viewModel(),
) {
    val prefs by sessionViewModel.prefs.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.action_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(android.R.string.cancel))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding),
        ) {
            SettingsHeader(text = stringResource(R.string.section_appearance))

            Text(
                text = stringResource(R.string.theme_mode),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            )
            // MD3E way to pick one of a few options: a single-choice segmented button row
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = prefs.themeMode == mode,
                        onClick = { sessionViewModel.setThemeMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeMode.entries.size),
                    ) {
                        Text(text = stringResource(mode.labelRes()))
                    }
                }
            }

            SwitchSettingRow(
                headline = stringResource(R.string.dynamic_color),
                checked = prefs.dynamicColor,
                onToggle = sessionViewModel::setDynamicColor,
            )

            SettingsHeader(text = stringResource(R.string.section_gestures))

            SwitchSettingRow(
                headline = stringResource(R.string.predictive_back),
                supporting = stringResource(R.string.predictive_back_summary),
                checked = prefs.predictiveBack,
                onToggle = sessionViewModel::setPredictiveBack,
            )

            SettingsHeader(text = stringResource(R.string.section_about))

            val githubUrl = stringResource(R.string.github_url)
            LinkSettingRow(
                headline = stringResource(R.string.github_project),
                supporting = githubUrl,
                icon = Icons.Filled.Code,
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl)))
                    }
                },
            )
        }
    }
}

private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.theme_mode_system
    ThemeMode.LIGHT -> R.string.theme_mode_light
    ThemeMode.DARK -> R.string.theme_mode_dark
}

@Composable
private fun SettingsHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

/**
 * Official settings-row pattern: [ListItem] made accessible as one toggle target via
 * `Modifier.toggleable(role = Switch)`; the [Switch] itself renders display-only
 * (`onCheckedChange = null`) so the row reports a single semantics action.
 */
@Composable
private fun SwitchSettingRow(
    headline: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    supporting: String? = null,
) {
    ListItem(
        headlineContent = { Text(text = headline) },
        supportingContent = supporting?.let { text -> { Text(text = text) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onToggle),
    )
}

@Composable
private fun LinkSettingRow(
    headline: String,
    supporting: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(text = headline) },
        supportingContent = { Text(text = supporting) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
