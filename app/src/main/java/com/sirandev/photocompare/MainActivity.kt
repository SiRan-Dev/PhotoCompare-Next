package com.sirandev.photocompare

import android.util.Log
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
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
             * enabled-callback set changes, winning the system-side precedence again.
             *
             * Instead we keep a PLAIN dispatcher callback — no predictive handlers, so no
             * predictive animation renders — as the NEWEST callback in the queue: the
             * dispatcher always executes the most recently added enabled callback. NavHost
             * registers a fresh predictive callback per destination, so we re-add on every
             * destination change to stay on top. Flipping [prefs.predictiveBack] on
             * disables the blocker, handing gestures back to NavHost's animated handling.
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
            LaunchedEffect(prefs.predictiveBack) {
                backBlocker.isEnabled = !prefs.predictiveBack
                // every destination change re-adds the blocker → always top precedence
                navController.currentBackStackEntryFlow.collect {
                    backBlocker.remove()
                    onBackPressedDispatcher.addCallback(this@MainActivity, backBlocker)
                }
            }

            PhotoCompareTheme(darkTheme = darkTheme, dynamicColor = prefs.dynamicColor) {
                PhotoCompareNavHost(navController = navController)
            }
        }
    }
}
