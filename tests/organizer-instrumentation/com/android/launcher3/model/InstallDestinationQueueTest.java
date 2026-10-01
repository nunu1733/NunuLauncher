// Issue #497: install-queue destination snapshot contract (ADR-0015 Decision
// 10, spec 497 AC-4). Covers the enqueue-time capture persistence (including
// first-enqueue-wins for duplicate enqueues) and the flush-time route
// attachment, which reads only the persisted snapshot — never the current
// policy. The real flush needs a launcher activity, so the route attachment
// runs through the package-visible production helper the flush itself uses.
package com.android.launcher3.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.util.Pair;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.launcher3.LauncherSettings.Favorites;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.WorkspaceItemInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class InstallDestinationQueueTest {

    private static final String TEST_PACKAGE = "com.test.destination.queue";
    private static final long TIMEOUT_MS = 10_000;

    private Context mContext;
    private DirectEditContract.DestinationResolver mProductionResolver;

    @Before
    public void setUp() {
        mContext = ApplicationProvider.getApplicationContext();
        // The real app may have registered the production resolver at process
        // init; restore it afterwards so other tests keep production wiring.
        mProductionResolver = DirectEditContract.getDestinationResolver();
    }

    @After
    public void tearDown() {
        ItemInstallQueue.INSTANCE.get(mContext).removeFromInstallQueue(
                new HashSet<>(Collections.singletonList(TEST_PACKAGE)),
                Process.myUserHandle());
        DirectEditContract.setDestinationResolver(mProductionResolver);
    }

    private Intent queueIntent() {
        return new Intent().setPackage(TEST_PACKAGE);
    }

    private String queueFileContents() throws Exception {
        java.io.File file = mContext.getFileStreamPath("apps_to_install.xml");
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (file.exists() && file.length() > 0) {
                byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
                String contents = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                if (contents.contains("destination_policy")) {
                    return contents;
                }
            }
            Thread.sleep(50);
        }
        return file.exists()
                ? new String(java.nio.file.Files.readAllBytes(file.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8)
                : "";
    }

    /**
     * ADR-0015 Decision 10: the snapshot captured at enqueue time persists
     * with the queue, and a duplicate enqueue keeps the FIRST persisted
     * snapshot (addToQueue's dedup skips the re-write — first enqueue wins).
     */
    @Test
    public void enqueuePersistsSnapshotAndDuplicateKeepsFirst() throws Exception {
        AtomicInteger captures = new AtomicInteger(0);
        DirectEditContract.setDestinationResolver(new DirectEditContract.DestinationResolver() {
            @Override
            public String captureDestination(Context context, String packageName,
                    android.os.UserHandle user) {
                return captures.incrementAndGet() == 1
                        ? DirectEditContract.serializeDestinationSnapshot(
                                DirectEditContract.DEST_SNAPSHOT_KIND_FOLDER, 77,
                                10L, TEST_PACKAGE)
                        : DirectEditContract.serializeDestinationSnapshot(
                                DirectEditContract.DEST_SNAPSHOT_KIND_UPSTREAM, 0,
                                10L, TEST_PACKAGE);
            }

            @Override
            public DirectEditContract.DestinationRoute route(String snapshot, long userSerial,
                    String packageName) {
                return null;
            }
        });

        ItemInstallQueue queue = ItemInstallQueue.INSTANCE.get(mContext);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                queue.queueItem(TEST_PACKAGE, Process.myUserHandle()));

        String contents = queueFileContents();
        assertTrue("snapshot A must be persisted with the queue",
                contents.contains("destination_policy=\"folder|77|"));
        assertFalse("the up-to-date user serial must be captured",
                contents.contains("folder|77|0|"));

        // Policy changed between enqueues: the duplicate keeps the first
        // snapshot (addToQueue skips add and write for a known entry).
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                queue.queueItem(TEST_PACKAGE, Process.myUserHandle()));
        Thread.sleep(300);
        String after = queueFileContents();
        assertTrue("first snapshot must survive a duplicate enqueue",
                after.contains("destination_policy=\"folder|77|"));
        assertFalse("second capture must not overwrite the persisted snapshot",
                after.contains("destination_policy=\"upstream|"));
    }

    /**
     * Flush-time routing (ADR-0015 Decision 10, spec AC-6): only application
     * entries are policy candidates — the manual-placement overloads queue
     * deep shortcuts and widgets, which keep the stock path even if a
     * snapshot attribute were present. Among application entries the
     * persisted snapshot alone decides.
     */
    @Test
    public void attachRouteReadsOnlyApplicationEntriesAndTheirSnapshot() {
        AtomicReference<String> routedSnapshot = new AtomicReference<>();
        DirectEditContract.setDestinationResolver(new DirectEditContract.DestinationResolver() {
            @Override
            public String captureDestination(Context context, String packageName,
                    android.os.UserHandle user) {
                throw new UnsupportedOperationException("not used at flush");
            }

            @Override
            public DirectEditContract.DestinationRoute route(String snapshot, long userSerial,
                    String packageName) {
                routedSnapshot.set(snapshot);
                return new DirectEditContract.DestinationRoute(true, current ->
                        DirectEditContract.DestinationDecision.folder(42),
                        (success, container, screenId, cellX, cellY, rank, newScreenId,
                                reason) -> { });
            }
        });

        ItemInfo item = new WorkspaceItemInfo();
        Pair<ItemInfo, Object> pair = Pair.create(item, null);
        long serial = Process.myUserHandle().hashCode();
        String snapshotA = DirectEditContract.serializeDestinationSnapshot(
                DirectEditContract.DEST_SNAPSHOT_KIND_FOLDER, 42, serial, TEST_PACKAGE);

        // Manual placements (deep shortcut / widget) never route, snapshot or
        // not — even a stray snapshot attribute cannot pull them in.
        Pair<ItemInfo, Object> deepShortcut = ItemInstallQueue.attachDestinationRoute(mContext,
                pair, snapshotA, Process.myUserHandle(), queueIntent(),
                Favorites.ITEM_TYPE_DEEP_SHORTCUT);
        assertNull(deepShortcut.second);
        assertNull(routedSnapshot.get());
        Pair<ItemInfo, Object> widget = ItemInstallQueue.attachDestinationRoute(mContext,
                pair, snapshotA, Process.myUserHandle(), queueIntent(),
                Favorites.ITEM_TYPE_APPWIDGET);
        assertNull(widget.second);

        // Application entry: routed, validator reads snapshot A.
        Pair<ItemInfo, Object> routed = ItemInstallQueue.attachDestinationRoute(mContext, pair,
                snapshotA, Process.myUserHandle(), queueIntent(),
                Favorites.ITEM_TYPE_APPLICATION);
        assertTrue(routed.second instanceof DirectEditContract.DestinationRoute);
        DirectEditContract.DestinationRoute route =
                (DirectEditContract.DestinationRoute) routed.second;
        assertTrue(route.usePolicyWrite);
        DirectEditContract.DestinationDecision decision =
                route.validator.validate(new DirectEditContract.Snapshot(4, 5, new int[0],
                        new DirectEditContract.Row[0], 4));
        assertEquals(DirectEditContract.DEST_ACTION_FOLDER, decision.action);
        assertEquals(42, decision.folderId);
        assertEquals(snapshotA, routedSnapshot.get());

        // Missing snapshot on an application entry: still routed (the
        // validator replans to the typed SNAPSHOT_INVALID upstream default) —
        // the old-format auto app case from AC-4.
        Pair<ItemInfo, Object> missing = ItemInstallQueue.attachDestinationRoute(mContext, pair,
                null, Process.myUserHandle(), queueIntent(),
                Favorites.ITEM_TYPE_APPLICATION);
        assertTrue(missing.second instanceof DirectEditContract.DestinationRoute);

        // No resolver registered: stock path untouched (same pair, no route).
        DirectEditContract.setDestinationResolver(null);
        Pair<ItemInfo, Object> unregistered = ItemInstallQueue.attachDestinationRoute(mContext,
                pair, snapshotA, Process.myUserHandle(), queueIntent(),
                Favorites.ITEM_TYPE_APPLICATION);
        assertSame(pair, unregistered);
        assertNull(unregistered.second);
    }

    /**
     * Production resolver routing (Phase 2 review round 1, mid): the stock
     * bypass requires a fully decoded, identity-matching UPSTREAM snapshot.
     * A corrupt upstream snapshot routes and replans to the typed
     * SNAPSHOT_INVALID default; an identity-mismatched one routes and rejects
     * without a write.
     */
    @Test
    public void productionRouteBypassesStockOnlyForValidUpstreamSnapshots() {
        app.lawnchair.homeedit.AppDestinationBridge.INSTANCE.install(mContext);
        DirectEditContract.DestinationResolver resolver =
                DirectEditContract.getDestinationResolver();
        assertNotNull(resolver);
        long serial = Process.myUserHandle().hashCode();
        android.os.UserHandle user = Process.myUserHandle();

        // Valid upstream snapshot: stock path.
        assertNull(resolver.route(
                DirectEditContract.serializeDestinationSnapshot(
                        DirectEditContract.DEST_SNAPSHOT_KIND_UPSTREAM, 0, serial, TEST_PACKAGE),
                serial, TEST_PACKAGE));

        // Corrupt upstream snapshot: routed, stage-2 replans to the typed
        // SNAPSHOT_INVALID default.
        DirectEditContract.DestinationRoute corrupt = resolver.route(
                "upstream|not-a-number|" + serial + "|" + TEST_PACKAGE, serial, TEST_PACKAGE);
        assertNotNull(corrupt);
        assertTrue(corrupt.usePolicyWrite);
        DirectEditContract.DestinationDecision corruptDecision = corrupt.validator.validate(
                new DirectEditContract.Snapshot(4, 5, new int[0],
                        new DirectEditContract.Row[0], 4));
        assertEquals(DirectEditContract.DEST_ACTION_DEFAULT, corruptDecision.action);
        assertEquals(DirectEditContract.DEST_SNAPSHOT_INVALID, corruptDecision.reason);

        // Identity-mismatched upstream snapshot: routed, stage-2 rejects
        // without a write.
        DirectEditContract.DestinationRoute mismatch = resolver.route(
                DirectEditContract.serializeDestinationSnapshot(
                        DirectEditContract.DEST_SNAPSHOT_KIND_UPSTREAM, 0, 99L, "com.other.app"),
                serial, TEST_PACKAGE);
        assertNotNull(mismatch);
        DirectEditContract.DestinationDecision mismatchDecision = mismatch.validator.validate(
                new DirectEditContract.Snapshot(4, 5, new int[0],
                        new DirectEditContract.Row[0], 4));
        assertEquals(DirectEditContract.DEST_ACTION_REJECT, mismatchDecision.action);
        assertEquals(DirectEditContract.DEST_SNAPSHOT_INVALID, mismatchDecision.reason);
    }
}
