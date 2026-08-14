package com.android.launcher3.organizer;

import static org.junit.Assert.assertFalse;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import app.lawnchair.organizer.application.store.RecoveryDbSchema;
import com.android.launcher3.LauncherFiles;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class BackupExclusionTest {
    @Test public void recoveryDatabaseIsAbsentFromLauncherBackupAllowlist() {
        assertFalse(LauncherFiles.ALL_FILES.contains(RecoveryDbSchema.FILE_NAME));
    }
}
