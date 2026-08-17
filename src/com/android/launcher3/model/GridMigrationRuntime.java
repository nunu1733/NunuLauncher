package com.android.launcher3.model;

import com.android.launcher3.Flags;
import java.util.function.BooleanSupplier;

interface GridMigrationRuntime {
    GridMigrationRuntime DIRECT = new GridMigrationRuntime() {
        @Override
        public boolean enableGridMigrationFix() {
            return Flags.enableGridMigrationFix();
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
