package com.sirandev.photocompare

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sirandev.photocompare.data.prefs.ThemeMode
import com.sirandev.photocompare.ui.navigation.PhotoCompareNavHost
import com.sirandev.photocompare.ui.session.SessionViewModel
import com.sirandev.photocompare.ui.theme.PhotoCompareTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val sessionViewModel: SessionViewModel = viewModel()
            val prefs by sessionViewModel.prefs.collectAsStateWithLifecycle()
            val darkTheme = when (prefs.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            PhotoCompareTheme(darkTheme = darkTheme, dynamicColor = prefs.dynamicColor) {
                PhotoCompareNavHost()
            }
        }
    }
}
