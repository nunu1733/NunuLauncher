package com.android.launcher3.organizer;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.launcher3.model.LayoutWriteCoordinator;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class LayoutWriteCoordinatorTest {
    @Test public void organizerLeaseIsExclusiveAndReentrantOnlyForOwner() {
        LayoutWriteCoordinator c = LayoutWriteCoordinator.getInstance();
        LayoutWriteCoordinator.Lease outer = c.tryAcquire(LayoutWriteCoordinator.OwnerKind.ORGANIZER);
        assertNotNull(outer);
        assertNull(c.tryAcquire(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER));
        LayoutWriteCoordinator.Lease inner = c.tryReenter(
                LayoutWriteCoordinator.OwnerKind.ORGANIZER, outer.token());
        assertNotNull(inner); inner.close(); outer.close();
    }

    @Test public void exactCorrelatedLoaderGetsScopedCapabilityAndTokenlessWorkDefers()
            throws Exception {
        LayoutWriteCoordinator c = LayoutWriteCoordinator.getInstance();
        LayoutWriteCoordinator.Lease outer = c.tryAcquire(LayoutWriteCoordinator.OwnerKind.ORGANIZER);
        assertNotNull(outer);
        AtomicBoolean exactCapability = new AtomicBoolean(false);
        AtomicBoolean tokenlessRan = new AtomicBoolean(false);
        try {
            Thread exactLoader = new Thread(() -> c.runOrDefer(
                    LayoutWriteCoordinator.OwnerKind.MODEL_WRITER,
                    outer.token(),
                    true,
                    () -> {
                        LayoutWriteCoordinator.Lease capability =
                                c.tryAcquireOrganizerCapability(outer.token());
                        exactCapability.set(capability != null);
                        if (capability != null) capability.close();
                    }));
            exactLoader.start();
            exactLoader.join();
            assertTrue(exactCapability.get());

            c.runOrDefer(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER, 0L, false,
                    () -> tokenlessRan.set(true));
            assertTrue(!tokenlessRan.get());
        } finally {
            outer.close();
        }
        assertTrue(tokenlessRan.get());
    }
}
