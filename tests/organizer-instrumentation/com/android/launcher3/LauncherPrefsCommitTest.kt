package com.android.launcher3

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherPrefsCommitTest {

    @Test
    fun putSync_commitsEveryEditorAcrossPrefFiles() {
        // Issue #532 rebase: the anchor LauncherPrefs.putSync() returns Unit
        // (S2/S3 production adapt: callers verify grid prefs through readback,
        // see ModelDbController.writeGridPreferences). The pre-rebase #59
        // aggregated-commit boolean therefore has no seam here anymore; this
        // keeps the surviving half of the contract — every editor is committed
        // exactly once, across both pref files, even when one commit fails.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val failedCommit = CommitResultPreferences(
            context.getSharedPreferences("issue-59-failed-commit", Context.MODE_PRIVATE), false
        )
        val successfulCommit = CommitResultPreferences(
            context.getSharedPreferences("issue-59-successful-commit", Context.MODE_PRIVATE), true
        )
        val preferences = LauncherPrefs(
            PreferenceContext(
                context,
                mapOf(
                    LauncherFiles.DEVICE_PREFERENCES_KEY to failedCommit,
                    LauncherFiles.SHARED_PREFERENCES_KEY to successfulCommit
                )
            )
        )

        preferences.putSync(
            LauncherPrefs.nonRestorableItem("failed", false).to(true),
            LauncherPrefs.backedUpItem("successful", false).to(true)
        )

        assertEquals(1, failedCommit.commitCount)
        assertEquals(1, successfulCommit.commitCount)
    }

    private class PreferenceContext(
        base: Context,
        private val preferences: Map<String, SharedPreferences>
    ) : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            preferences.getValue(name)
    }

    private class CommitResultPreferences(
        private val delegate: SharedPreferences,
        private val commitResult: Boolean
    ) : SharedPreferences by delegate {
        var commitCount = 0
            private set

        override fun edit(): SharedPreferences.Editor =
            object : SharedPreferences.Editor by delegate.edit() {
                override fun commit(): Boolean {
                    commitCount++
                    return commitResult
                }
            }
    }
}
