package dev.bookscanner.app

import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The privacy claim in AGENTS.md §11 — scanned pages never leave the device —
 * is enforced by the *merged* manifest declaring no network permission at all,
 * not by anyone remembering not to add a network call.
 *
 * The merged manifest is the right thing to assert: a dependency can
 * contribute `INTERNET` without this module's own manifest mentioning it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PrivacyManifestTest {
    private fun declaredPermissions(): List<String> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info =
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_PERMISSIONS,
            )
        return info.requestedPermissions?.toList().orEmpty()
    }

    @Test
    fun `the app cannot reach the network`() {
        val permissions = declaredPermissions()

        assertFalse(
            permissions.any { it == "android.permission.INTERNET" },
            "INTERNET must never be declared; found: $permissions",
        )
        assertFalse(
            permissions.any { it == "android.permission.ACCESS_NETWORK_STATE" },
            "no network permission of any kind should be needed; found: $permissions",
        )
    }

    @Test
    fun `the app asks only for the camera`() {
        val permissions = declaredPermissions()

        assertTrue(
            permissions.contains("android.permission.CAMERA"),
            "capture needs the camera; found: $permissions",
        )
        // Imports go through the Photo Picker, which needs no storage
        // permission — asking for one would be an unnecessary privacy cost.
        assertFalse(
            permissions.any { it.startsWith("android.permission.READ_MEDIA") },
            "the Photo Picker makes media permissions unnecessary; found: $permissions",
        )
        assertFalse(
            permissions.any { it == "android.permission.READ_EXTERNAL_STORAGE" },
            "found: $permissions",
        )
    }
}
