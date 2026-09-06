package com.sirandev.photocompare

import android.os.Build
import android.os.Bundle
import android.window.OnBackInvokedCallback
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
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

            // Runtime predictive-back kill switch. The manifest opts the app in so predictive
            // animations are available; registering a plain (non-animating) default-priority
            // OnBackInvokedCallback withdraws that permission, so the edge gesture completes
            // back navigation without any preview. Forwarding to the dispatcher keeps normal
            // back handling (nav pop, top bars) intact.
            DisposableEffect(prefs.predictiveBack) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !prefs.predictiveBack) {
                    val interceptor = object : OnBackInvokedCallback {
                        override fun onBackInvoked() {
                            onBackPressedDispatcher.onBackPressed()
                        }
                    }
                    // OnBackInvokedCallback.PRIORITY_DEFAULT (== 0); the SDK constant is not
                    // exposed to the Kotlin compiler, so reference the documented value
                    onBackInvokedDispatcher.registerOnBackInvokedCallback(0, interceptor)
                    onDispose { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(interceptor) }
                } else {
                    onDispose { }
                }
            }

            PhotoCompareTheme(darkTheme = darkTheme, dynamicColor = prefs.dynamicColor) {
                PhotoCompareNavHost()
            }
        }
    }
}
