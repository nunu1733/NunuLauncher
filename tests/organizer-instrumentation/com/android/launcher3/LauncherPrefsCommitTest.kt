package com.android.launcher3

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherPrefsCommitTest {

    @Test
    fun putSync_commitsEveryEditorAndReturnsFalseWhenAnyCommitFails() {
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

        val committed = preferences.putSync(
            LauncherPrefs.nonRestorableItem("failed", false).to(true),
            LauncherPrefs.backedUpItem("successful", false).to(true)
        )

        assertEquals(1, failedCommit.commitCount)
        assertEquals(1, successfulCommit.commitCount)
        assertFalse(committed)
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
