package com.sirandev.photocompare

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import android.util.Log
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
            val navController = rememberNavController()

            /**
             * Runtime predictive-back kill switch. Since targetSdk 36+, the system treats
             * the app as predictive-back opted-in (the manifest attribute defaults to true
             * and onBackPressed()/KEYCODE_BACK are gone), so back handling must go through
             * OnBackPressedDispatcher. Registering a SEPARATE system OnBackInvokedCallback
             * does not stick: androidx re-registers its own system callback whenever the
             * enabled-callback set changes (navigation, dialogs), winning the system-side
             * precedence again. Instead we put a PLAIN dispatcher callback on top of the
             * queue (added last): predictive events (started/progressed) are forwarded to
             * it but it does not act on them — so no predictive animation renders — and on
             * commit it performs the pop directly. Flipping [prefs.predictiveBack] on
             * disables it, handing gestures back to NavHost's animated predictive handling.
             */
            val backBlocker = remember {
                object : OnBackPressedCallback(enabled = false) {
                    override fun handleOnBackPressed() {
                        Log.d("PhotoCompare-Back", "predictive-back OFF: consuming back, popping nav")
                        if (!navController.popBackStack()) {
                            finish()
                        }
                    }
                }
            }
            DisposableEffect(prefs.predictiveBack) {
                backBlocker.isEnabled = !prefs.predictiveBack
                // added AFTER composition: newest dispatcher callback → top precedence
                onBackPressedDispatcher.addCallback(this@MainActivity, backBlocker)
                onDispose { backBlocker.remove() }
            }

            PhotoCompareTheme(darkTheme = darkTheme, dynamicColor = prefs.dynamicColor) {
                PhotoCompareNavHost(navController = navController)
            }
        }
    }
}
