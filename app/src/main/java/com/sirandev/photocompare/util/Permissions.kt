package com.sirandev.photocompare.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Storage/media permission logic, ported from ScopedPermissionChecker / LegacyPermissionChecker.
 */
object Permissions {

    /** Permissions to request for full media access on this device's API level. */
    fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    /** Partial (user-selected) photo access introduced in API 34. */
    fun partialAccessPermission(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED else null

    fun hasMediaAccess(context: Context): Boolean {
        val required = requiredPermissions()
        var fullAccess = true
        for (permission in required) {
            if (!isGranted(context, permission)) {
                fullAccess = false
            }
        }
        if (fullAccess) return true
        val partial = partialAccessPermission()
        return partial != null && isGranted(context, partial)
    }

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
