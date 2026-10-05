package app.lawnchair.organizer.ui

import android.content.Context
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lawnchair.organizer.planning.CategoryId
import app.lawnchair.organizer.planning.CategoryIdentity
import app.lawnchair.organizer.planning.PackageName
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.rules.BuiltInOrganizerPolicyBundleSource
import app.lawnchair.organizer.rules.CategoryOverrideKey
import app.lawnchair.organizer.rules.CategoryOverrideMutation
import app.lawnchair.organizer.rules.CategoryOverrideSnapshot
import app.lawnchair.organizer.rules.CategoryOverrideStore
import app.lawnchair.organizer.rules.CategoryOverrideStoredIdentity
import app.lawnchair.organizer.rules.CategoryOverrideStoredReadResult
import app.lawnchair.organizer.rules.CategoryOverrideStoredSnapshot
import app.lawnchair.organizer.rules.CategoryOverrideWriteResult
import app.lawnchair.organizer.rules.OverrideSnapshotReadResult
import app.lawnchair.organizer.rules.PolicyInputIdentity
import app.lawnchair.organizer.rules.PolicySourceKind
import app.lawnchair.organizer.rules.sha256Canonical
import app.lawnchair.ui.preferences.destinations.CategoryOverridePreferences
import app.lawnchair.ui.theme.LawnchairTheme
import com.android.launcher3.R
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class CategoryOverridePreferencesInstrumentationTest {
    @get:Rule
    // Issue #490: queue IO continuations with composition instead of resuming on IO threads.
    // G5 (§6.14): anchorのManualOrganizationPreferences compose oracleと同一の既定
    // compose rule. StandardTestDispatcher effect context は本classでは未使用で、
    // CI(x86_64)上のcompose idle待ちが刺さる再現源のため外した。
    val composeRule = createComposeRule()

    /**
     * G5 (§6.13): bound the framework's implicit compose idle synchronization per test method.
     *
     * CI run 37299029278 hung this class for ~8 minutes inside that implicit sync: on the x86_64
     * CI emulator SurfaceFlinger stopped delivering the vsync the test process had requested
     * (failure-time capture shows the app EventThread connection stuck in `VSyncRequest::Single`
     * for 490+s while the "app" dispatch source kept running), so
     * `AndroidComposeUiTest.waitForNextChoreographerFrame` spins on `while (!frameHit)` forever.
     * Compose itself was idle, so neither Espresso's idling-resource timeout (26s) nor the
     * `waitUntil` deadlines (5s) could fire — `waitUntil`'s budget only applies between condition
     * evaluations, while each evaluation enters the same unbounded idle sync — and the per-class
     * 20m cap killed the lane with exit 124 and no per-test report.
     *
     * This watchdog does not wrap or replace the compose rule (the JUnit Timeout/RuleChain
     * approach was reverted in `47a76a33e4` because `createComposeRule` requires the test
     * thread): the test method still runs on the instrumentation thread. A daemon side thread
     * watches the method wall clock; on deadline it dumps the blocked stacks to logcat as
     * evidence and interrupts the test thread, which unblocks the interruptible
     * `Espresso.onIdle` future waits (or fails the in-flight wait) and turns the hang into a
     * bounded per-test failure while the failure-capture wrapper still owns the live emulator.
     * Assert contracts and every `waitUntil` timeout are unchanged. The per-class script cap and
     * the job timeout remain the outer backstops.
     */
    private val idleSyncWatchdogArmed = AtomicBoolean(false)
    private lateinit var idleSyncWatchdogTarget: Thread

    @Before
    fun armIdleSyncWatchdog() {
        idleSyncWatchdogTarget = Thread.currentThread()
        idleSyncWatchdogArmed.set(true)
        val deadlineNanos =
            System.nanoTime() + TimeUnit.SECONDS.toNanos(IDLE_SYNC_DEADLINE_SECONDS)
        Thread(
            {
                while (idleSyncWatchdogArmed.get()) {
                    if (System.nanoTime() >= deadlineNanos) break
                    Thread.sleep(IDLE_SYNC_WATCHDOG_POLL_MS)
                }
                if (idleSyncWatchdogArmed.compareAndSet(true, false)) {
                    failStuckIdleSync()
                }
            },
            IDLE_SYNC_WATCHDOG_THREAD_NAME,
        ).apply {
            isDaemon = true
            start()
        }
    }

    @After
    fun disarmIdleSyncWatchdog() {
        idleSyncWatchdogArmed.set(false)
    }

    private fun failStuckIdleSync() {
        Log.w(
            IDLE_SYNC_WATCHDOG_TAG,
            "no test progress for ${IDLE_SYNC_DEADLINE_SECONDS}s; " +
                "dumping stacks and interrupting the compose idle sync",
        )
        dumpThreadStack("test", idleSyncWatchdogTarget)
        dumpThreadStack("main", Looper.getMainLooper().thread)
        idleSyncWatchdogTarget.interrupt()
    }

    private fun dumpThreadStack(label: String, thread: Thread) {
        val frames = thread.stackTrace
        Log.w(IDLE_SYNC_WATCHDOG_TAG, "$label thread state=${thread.state} depth=${frames.size}")
        frames.take(IDLE_SYNC_STACK_DUMP_LIMIT).forEachIndexed { index, frame ->
            Log.w(IDLE_SYNC_WATCHDOG_TAG, "$label #$index $frame")
        }
    }

    private companion object {
        // Normal CI duration of a method in this class is seconds (run 37299029278: 3-5s each)
        // and every in-test wait is already bounded (5s waitUntil), so 180s leaves a wide margin
        // over the legitimate maximum while still failing far inside the 20m per-class cap.
        const val IDLE_SYNC_DEADLINE_SECONDS = 180L
        const val IDLE_SYNC_WATCHDOG_POLL_MS = 1_000L
        const val IDLE_SYNC_STACK_DUMP_LIMIT = 60
        const val IDLE_SYNC_WATCHDOG_THREAD_NAME = "compose-idle-sync-watchdog"
        const val IDLE_SYNC_WATCHDOG_TAG = "CatOverrideIdleSync"
    }

    @Test
    fun samePackageProfilesExposeTextStateAndIndependentAccessibleRows() {
        val coordinator = coordinator()
        composeRule.setContent {
            LawnchairTheme {
                CategoryOverridePreferences(coordinator = coordinator)
            }
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Example").fetchSemanticsNodes().size == 2
        }
        composeRule.onNodeWithText("${context.getString(R.string.organizer_category_override_profile_personal)} · ${context.getString(R.string.organizer_category_override_automatic)}")
            .assertIsDisplayed()
        composeRule.onNodeWithText("${context.getString(R.string.organizer_category_override_profile_work)} · ${context.getString(R.string.organizer_category_override_automatic)}")
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            appContentDescription(context, R.string.organizer_category_override_profile_personal),
        ).assertHasClickAction()
        composeRule.onNodeWithContentDescription(
            appContentDescription(context, R.string.organizer_category_override_profile_work),
        ).assertHasClickAction()
    }

    @Test
    fun cancelRestoresFocusAndLongAppLabelRemainsReachableAtTwoHundredPercentFontScale() {
        val longLabel = "A very long localized application label that must remain reachable in category overrides"
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                LawnchairTheme {
                    CategoryOverridePreferences(coordinator = coordinator(label = longLabel))
                }
            }
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(longLabel).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(
            appContentDescription(context, R.string.organizer_category_override_profile_personal, longLabel),
        ).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        // G5 (§6.13): at 200% font scale the cancel row can sit below the fold and
        // performScrollToNode's implicit idle-wait never settles on a slow CI
        // emulator (reproduced 2/3 runs). Drive the scroll explicitly with a
        // bounded wait; the reachable-node contract is unchanged.
        scrollToNodeBounded(hasText(context.getString(R.string.organizer_category_override_cancel)))
        composeRule.onNodeWithText(context.getString(R.string.organizer_category_override_cancel))
            .assertHasClickAction()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitUntil(5_000) {
            try {
                composeRule.onNodeWithText(context.getString(R.string.organizer_category_overrides_summary)).assertIsFocused()
                true
            } catch (_: AssertionError) {
                false
            }
        }
    }

    @Test
    fun editorIsReadableAtTwoHundredPercentFontScaleAndRestoresFocusAfterSave() {
        val coordinator = coordinator()
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                LawnchairTheme {
                    CategoryOverridePreferences(coordinator = coordinator)
                }
            }
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Example").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(
            appContentDescription(context, R.string.organizer_category_override_profile_personal),
        ).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNode(hasScrollAction()).performScrollToNode(
            hasText(context.getString(R.string.organizer_category_game)),
        )
        composeRule.onNodeWithText(context.getString(R.string.organizer_category_game))
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNode(hasScrollAction()).performScrollToNode(
            hasText(context.getString(R.string.organizer_category_override_save)),
        )
        composeRule.onNodeWithText(context.getString(R.string.organizer_category_override_save))
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitUntil(5_000) {
            try {
                composeRule.onNodeWithText(context.getString(R.string.organizer_category_override_saved)).assertIsFocused()
                true
            } catch (_: AssertionError) {
                false
            }
        }
    }

    @Test
    fun keyboardDpadNavigatesToProfileRowAndActivatesEditor() {
        val coordinator = coordinator()
        var inputModeManager: InputModeManager? = null
        composeRule.setContent {
            inputModeManager = LocalInputModeManager.current
            LawnchairTheme {
                CategoryOverridePreferences(coordinator = coordinator)
            }
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Example").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.runOnIdle {
            requireNotNull(inputModeManager).requestInputMode(InputMode.Keyboard)
        }
        val summary = composeRule.onNodeWithText(
            context.getString(R.string.organizer_category_overrides_summary),
        )
        summary.requestFocus().assertIsFocused()
        summary.performKeyInput {
            keyDown(Key.DirectionDown)
            keyUp(Key.DirectionDown)
        }
        composeRule.onNode(hasScrollAction()).printToLog("CategoryOverrideDpadFocus")
        composeRule.onNodeWithContentDescription(
            appContentDescription(context, R.string.organizer_category_override_profile_personal),
        ).assertIsFocused()
        composeRule.onNodeWithContentDescription(
            appContentDescription(context, R.string.organizer_category_override_profile_personal),
        ).performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        composeRule.onNode(hasScrollAction()).performScrollToNode(
            hasText(context.getString(R.string.organizer_category_override_use_automatic)),
        )
        composeRule.onNodeWithText(context.getString(R.string.organizer_category_override_use_automatic)).assertIsDisplayed()
    }

    @Test
    fun switchEquivalentSemanticsActivationOpensEditor() {
        val coordinator = coordinator()
        composeRule.setContent {
            LawnchairTheme {
                CategoryOverridePreferences(coordinator = coordinator)
            }
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Example").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(
            appContentDescription(context, R.string.organizer_category_override_profile_personal),
        ).assertHasClickAction().performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNode(hasScrollAction()).performScrollToNode(
            hasText(context.getString(R.string.organizer_category_override_use_automatic)),
        )
        composeRule.onNodeWithText(context.getString(R.string.organizer_category_override_use_automatic)).assertIsDisplayed()
    }

    @Test
    fun appRowMeetsMinimumFortyEightDpTouchTarget() {
        val coordinator = coordinator()
        composeRule.setContent {
            LawnchairTheme {
                CategoryOverridePreferences(coordinator = coordinator)
            }
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val appRow = composeRule.onNodeWithContentDescription(
            appContentDescription(context, R.string.organizer_category_override_profile_personal),
        )
        composeRule.waitUntil(5_000) {
            try {
                appRow.fetchSemanticsNode()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        val height = appRow.fetchSemanticsNode().boundsInRoot.height
        val minimumHeight = with(composeRule.density) { 48.dp.toPx() }
        assertTrue("Category override app row must provide a 48dp touch target", height >= minimumHeight)
    }

    @Test
    fun targetUnavailableReturnsToFreshDestinationAndRestoresFocus() {
        val personal = app("0", CategoryOverrideProfile.PERSONAL, "Example")
        var inventoryReads = 0
        val coordinator = coordinator(
            inventory = CategoryOverrideAppInventory {
                inventoryReads += 1
                if (inventoryReads == 1) listOf(personal) else emptyList()
            },
        )
        composeRule.setContent {
            LawnchairTheme {
                CategoryOverridePreferences(coordinator = coordinator)
            }
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Example").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(
            appContentDescription(context, R.string.organizer_category_override_profile_personal),
        ).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNode(hasScrollAction()).performScrollToNode(
            hasText(context.getString(R.string.organizer_category_override_save)),
        )
        composeRule.onNodeWithText(context.getString(R.string.organizer_category_override_save))
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitUntil(5_000) {
            try {
                composeRule.onNodeWithText(context.getString(R.string.organizer_category_override_unavailable)).assertIsFocused()
                true
            } catch (_: AssertionError) {
                false
            }
        }
    }

    @Test
    fun longestLocalizedCategoryPresentationLabelRemainsReachableAtTwoHundredPercentFontScale() {
        val coordinator = coordinator()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val longestLabel = requireNotNull(coordinator.categories())
            .filterIsInstance<CategoryIdentity.BuiltIn>()
            .map { CategoryOverrideCategoryPresentations.forCategory(it.id).labelRes }
            .map(context::getString)
            .maxBy(String::length)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                LawnchairTheme {
                    CategoryOverridePreferences(coordinator = coordinator)
                }
            }
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Example").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(
            appContentDescription(context, R.string.organizer_category_override_profile_personal),
        ).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(longestLabel))
        composeRule.onNodeWithText(longestLabel).assertIsDisplayed()
    }

    private fun appContentDescription(context: Context, profileResource: Int, label: String = "Example"): String =
        context.getString(
            R.string.organizer_category_override_app_description,
            label,
            context.getString(profileResource),
            context.getString(R.string.organizer_category_override_automatic),
        )

    /**
     * G5 (§6.13): performScrollToNode with a bounded settle — scroll by page
     * increments until the target node exists (or the bound expires, failing
     * like any other unreachable-node contract violation).
     */
    private fun scrollToNodeBounded(matcher: SemanticsMatcher) {
        // performScrollToNode remains the authoritative scroll mechanism (a raw
        // swipe can land on a nested scrollable); unreachability is retried
        // until the deadline instead of hanging on a single idle sync.
        composeRule.waitUntil(10_000) {
            try {
                composeRule.onNode(hasScrollAction()).performScrollToNode(matcher)
                composeRule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
            } catch (t: Throwable) {
                false
            }
        }
        composeRule.onNode(matcher).assertExists("not reachable after bounded scroll")
    }

    private fun coordinator(
        label: String = "Example",
        inventory: CategoryOverrideAppInventory? = null,
    ): CategoryOverrideAuthoringCoordinator {
        val personal = app("0", CategoryOverrideProfile.PERSONAL, label)
        val work = app("10", CategoryOverrideProfile.WORK, label)
        return CategoryOverrideAuthoringCoordinator(
            TestStore(),
            BuiltInOrganizerPolicyBundleSource,
            inventory ?: CategoryOverrideAppInventory { listOf(personal, work) },
        )
    }

    private fun app(profileId: String, profile: CategoryOverrideProfile, label: String) = CategoryOverrideApp(
        key = CategoryOverrideKey(PackageName("com.example.same"), ProfileId(profileId)),
        label = label,
        profile = profile,
        icon = null,
        assignedCategory = null,
    )

    private class TestStore : CategoryOverrideStore {
        private var snapshot = stored(0L, emptyMap())

        override fun readStored(): CategoryOverrideStoredReadResult = CategoryOverrideStoredReadResult.Ready(snapshot)

        override fun read(capturedProfiles: Set<ProfileId>): OverrideSnapshotReadResult = OverrideSnapshotReadResult.Ready(
            CategoryOverrideSnapshot(
                schemaVersion = 1,
                generation = snapshot.identity.generation,
                assignments = snapshot.assignments.filterKeys { it.profile in capturedProfiles },
                identity = visibleIdentity(snapshot.identity.generation),
            ),
        )

        override fun mutate(
            request: CategoryOverrideMutation,
            expected: CategoryOverrideStoredIdentity,
            verificationProfiles: Set<ProfileId>,
        ): CategoryOverrideWriteResult = mutateAll(listOf(request), expected, verificationProfiles)

        override fun mutateAll(
            requests: List<CategoryOverrideMutation>,
            expected: CategoryOverrideStoredIdentity,
            verificationProfiles: Set<ProfileId>,
        ): CategoryOverrideWriteResult {
            val entries = snapshot.assignments.toMutableMap()
            for (request in requests) {
                when (request) {
                    is CategoryOverrideMutation.Set -> entries[request.key] = request.category
                    is CategoryOverrideMutation.Remove -> entries.remove(request.key)
                }
            }
            snapshot = stored(snapshot.identity.generation + 1L, entries)
            return CategoryOverrideWriteResult.Committed(
                snapshot.identity,
                visibleIdentity(snapshot.identity.generation),
            )
        }

        private fun stored(
            generation: Long,
            assignments: Map<CategoryOverrideKey, CategoryIdentity>,
        ): CategoryOverrideStoredSnapshot = CategoryOverrideStoredSnapshot(
            CategoryOverrideStoredIdentity(1, generation, sha256Canonical("")),
            assignments,
        )

        private fun visibleIdentity(generation: Long) = PolicyInputIdentity(
            PolicySourceKind.CATEGORY_OVERRIDE_SNAPSHOT,
            "schema-1-generation-$generation",
            sha256Canonical(""),
        )
    }
}
