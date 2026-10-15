package com.android.launcher3.model;

import java.util.function.BooleanSupplier;

interface GridMigrationRuntime {
    GridMigrationRuntime DIRECT = new GridMigrationRuntime() {
        @Override
        public boolean enableGridMigrationFix() {
            // Rebase Phase 2 adapt (S2/S3): the fork's `enable_grid_migration_fix`
            // aconfig flag (fixed read-only, FeatureFlagsImpl returned false) has no
            // counterpart in the anchor flag set, so the runtime gate keeps the
            // fork's effective production value. Test seams may still inject true.
            return false;
        }

        @Override
        public void execute(GridMigrationOperation operation, Runnable delegate) {
            delegate.run();
        }

        @Override
        public boolean writeGridPreferences(DeviceGridState state, BooleanSupplier writer) {
            return writer.getAsBoolean();
        }
    };

    boolean enableGridMigrationFix();

    void execute(GridMigrationOperation operation, Runnable delegate);

    boolean writeGridPreferences(DeviceGridState state, BooleanSupplier writer);
}
