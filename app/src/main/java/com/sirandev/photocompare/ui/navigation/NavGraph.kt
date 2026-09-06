package com.sirandev.photocompare.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sirandev.photocompare.ui.AppRoutes
import com.sirandev.photocompare.ui.compare.CompareScreen
import com.sirandev.photocompare.ui.images.ListImagesScreen
import com.sirandev.photocompare.ui.pool.SelectImagePoolScreen
import com.sirandev.photocompare.ui.selected.SelectedImagesScreen
import com.sirandev.photocompare.ui.session.SessionViewModel

@Composable
fun PhotoCompareNavHost() {
    val navController = rememberNavController()
    val sessionViewModel: SessionViewModel = viewModel()
    NavHost(navController = navController, startDestination = AppRoutes.POOL) {
        composable(AppRoutes.POOL) {
            SelectImagePoolScreen(
                onOpenFolder = { folderPath ->
                    sessionViewModel.openFolder(folderPath)
                    navController.navigate(AppRoutes.IMAGES)
                },
                onOpenDate = { dayStartMillis ->
                    sessionViewModel.openDate(dayStartMillis)
                    navController.navigate(AppRoutes.IMAGES)
                },
            )
        }
        composable(AppRoutes.IMAGES) {
            ListImagesScreen(
                onBack = { navController.popBackStack() },
                onOpenCompare = { topIndex, bottomIndex ->
                    navController.navigate("${AppRoutes.COMPARE}/$topIndex?bottom=$bottomIndex")
                },
                onShowSelection = { navController.navigate(AppRoutes.SELECTED) },
                sessionViewModel = sessionViewModel,
            )
        }
        composable(
            route = "${AppRoutes.COMPARE}/{topIndex}?bottom={bottomIndex}",
            arguments = listOf(
                navArgument("topIndex") { type = NavType.IntType },
                navArgument("bottomIndex") { type = NavType.IntType; defaultValue = -1 },
            ),
        ) { entry ->
            CompareScreen(
                navController = navController,
                topIndex = entry.arguments?.getInt("topIndex") ?: -1,
                bottomIndex = entry.arguments?.getInt("bottomIndex") ?: -1,
                sessionViewModel = sessionViewModel,
            )
        }
        composable(AppRoutes.SELECTED) {
            SelectedImagesScreen(
                onBack = { navController.popBackStack() },
                sessionViewModel = sessionViewModel,
            )
        }
    }
}
