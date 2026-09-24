package app.lawnchair.organizer.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lawnchair.organizer.integration.UsageAccess
import com.android.launcher3.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Issue #371 (spec 371, D-07): process-scoped just-in-time Usage Access
 * request gate. The gate owns the process's single **request opportunity**
 * as an atomic state machine
 * `Available -> Reserved(owner) -> Presented(owner) -> Resolved`:
 *
 * - [evaluate][UsageAccessJitGate.evaluate] returns the caller's decision.
 *   Only the caller whose atomic `Available -> Reserved` transition succeeds
 *   gets [UsageAccessJitDecision.Present] and may present the dialog; a
 *   second concurrent caller gets [UsageAccessJitDecision.Wait] and must not
 *   advance its composition until the first request is resolved.
 * - [markPresented][UsageAccessJitGate.markPresented] consumes the
 *   **re-presentation right** (`Reserved -> Presented`). It never unblocks
 *   waiters: unblocking happens only on
 *   [resolve][UsageAccessJitGate.resolve] (`Presented -> Resolved`), i.e.
 *   when the user's answer is decided. There is no `Presented -> Available`
 *   transition (once shown, never shown again this process).
 * - [release][UsageAccessJitGate.release] is valid only before presentation
 *   (`Reserved -> Available`): the opportunity stays unconsumed and the next
 *   trigger can request again.
 *
 * Owner destruction (run cancel/dismiss, exchange close/navigation teardown)
 * applies the spec's state-specific rules — `Reserved` -> release,
 * `Presented` -> resolve exactly once (abandon resolution: waiters are
 * unblocked while the destroyed owner's pending action is never resumed),
 * `Resolved` -> no-op — so the barrier can never be orphaned.
 *
 * Waiters observe resolution through [snapshot], a revision-tagged
 * [StateFlow]: deterministic observation, never polling or incidental
 * recomposition. Owners are compared with equality and every gate operation
 * binds to one attempt's identity, so a stale owner's operation never acts on
 * a newer attempt.
 */
class UsageAccessJitGate(private val isGranted: () -> Boolean) {

    enum class Phase { Available, Reserved, Presented, Resolved }

    /**
     * Immutable observation snapshot for waiters ([snapshot]). [revision]
     * increases on every state transition so collectors can distinguish a
     * real change from an equal re-publish.
     */
    data class Snapshot(val phase: Phase, val revision: Long)

    sealed interface Decision {
        /** Composition may proceed immediately (resolved, or granted at evaluation time). */
        data object Proceed : Decision

        /** The caller now owns the reservation and is the only one that may present. */
        data object Present : Decision

        /** Another owner is in flight; hold the action and await resolution. */
        data object Wait : Decision
    }

    private val lock = Any()
    private var phase = Phase.Available
    private var currentOwner: Any? = null
    private var revision = 0L
    private val snapshotFlow = MutableStateFlow(Snapshot(Phase.Available, 0L))

    val snapshot: StateFlow<Snapshot> = snapshotFlow.asStateFlow()

    fun evaluate(owner: Any): Decision = synchronized(lock) {
        when (phase) {
            Phase.Reserved, Phase.Presented -> Decision.Wait

            Phase.Resolved -> Decision.Proceed

            Phase.Available ->
                if (isGranted()) {
                    // The first signal read of the process is about to happen
                    // granted: consume the opportunity (and release waiters)
                    // without ever presenting (spec scenario "初回trigger時に付与済み").
                    transitionLocked(Phase.Resolved, owner)
                    Decision.Proceed
                } else {
                    transitionLocked(Phase.Reserved, owner)
                    Decision.Present
                }
        }
    }

    /** `Reserved -> Presented`: consumes the re-presentation right; waiters stay blocked. */
    fun markPresented(owner: Any) {
        synchronized(lock) {
            if (phase == Phase.Reserved && currentOwner == owner) {
                transitionLocked(Phase.Presented, owner)
            }
        }
    }

    /** `Presented -> Resolved`: the user's answer is decided; waiters are unblocked. */
    fun resolve(owner: Any) {
        synchronized(lock) {
            if (phase == Phase.Presented && currentOwner == owner) {
                transitionLocked(Phase.Resolved, owner)
            }
        }
    }

    /** `Reserved -> Available`: only valid before presentation; the opportunity is untouched. */
    fun release(owner: Any) {
        synchronized(lock) {
            if (phase == Phase.Reserved && currentOwner == owner) {
                transitionLocked(Phase.Available, null)
            }
        }
    }

    /**
     * Atomic owner-destruction application (spec 371 review round 3): the
     * read-and-act completes under one monitor, so a racing
     * [markPresented] can never leave this owner's request orphaned in
     * `Presented`. State specific — `Reserved` releases (the opportunity
     * stays unconsumed for the next trigger), `Presented` resolves exactly
     * once as an abandon resolution (waiters unblock; the destroyed owner's
     * pending action is never resumed); everything else is a no-op. Owner
     * teardown paths (run cancel/dismiss, exchange close/teardown) call THIS —
     * never an [ownedPhase] read followed by release/resolve.
     */
    fun abandon(owner: Any) {
        synchronized(lock) {
            if (currentOwner != owner) return
            when (phase) {
                Phase.Reserved -> transitionLocked(Phase.Available, null)
                Phase.Presented -> transitionLocked(Phase.Resolved, owner)
                Phase.Available, Phase.Resolved -> Unit
            }
        }
    }

    /**
     * The phase this owner currently holds, or `null` when the owner holds
     * nothing (owner-destruction rules key off this).
     */
    fun ownedPhase(owner: Any): Phase? = synchronized(lock) {
        if (currentOwner == owner) phase else null
    }

    private fun transitionLocked(next: Phase, owner: Any?) {
        phase = next
        currentOwner = owner
        revision += 1
        snapshotFlow.value = Snapshot(phase, revision)
    }
}

/**
 * Process-wide singleton for the JIT gate ([ManualOrganizationModule] get
 * pattern). The run machine and the exchange holder share one instance — the
 * request opportunity is process-scoped, not per-surface.
 */
internal object UsageAccessJitGateProvider {
    @Volatile private var instance: UsageAccessJitGate? = null

    /**
     * Process-wide generation for JIT request attempt tokens (spec 371
     * JIT-AC-05 identity binding). Holder instances must mint tokens from
     * here, never from an instance-local counter: a recreated holder starting
     * at 0 again could collide with a stale owner of a disposed holder and
     * let old operations act on the new attempt (ABA).
     */
    private val attemptTokens = java.util.concurrent.atomic.AtomicLong(0L)

    fun nextAttemptToken(): Long = attemptTokens.incrementAndGet()

    fun get(context: Context): UsageAccessJitGate = instance ?: synchronized(this) {
        instance ?: UsageAccessJitGate(isGranted = { UsageAccess.isGranted(context.applicationContext) })
            .also { instance = it }
    }

    /**
     * Test-only isolation seam: hands out a fresh gate on the next [get], so
     * instrumentation tests that exercise the process singleton can each
     * start from an unconsumed opportunity (the production one-chance-per-
     * process semantics stay untouched — see the instrumentation settings-
     * return tests, the only callers).
     */
    fun resetForTests() {
        synchronized(this) {
            instance = null
            attemptTokens.set(0L)
        }
    }
}

/**
 * Issue #371 (spec JIT-AC-03): bounded re-read of the grant predicate after
 * returning from the system usage-access settings. The app-op change
 * propagates asynchronously, so a single `ON_RESUME` read can miss the grant
 * (UsageAccessTransitionProbeTest waits 1s for the same reason). The wait is
 * bounded by [limitMs] — fixed in the accepted spec to the 0.5s..2s range,
 * concrete value chosen at the implementation contract commit — and returns
 * as soon as the predicate observes the grant. [sleep] is injected so unit
 * tests drive a virtual clock (`limit - ε` keeps waiting, reaching `limit`
 * falls back, an early grant exits before the limit).
 */
internal const val USAGE_ACCESS_JIT_GRANT_WAIT_LIMIT_MS = 1500L

internal const val USAGE_ACCESS_JIT_GRANT_POLL_INTERVAL_MS = 250L

internal suspend fun awaitUsageAccessGrant(
    isGranted: () -> Boolean,
    limitMs: Long = USAGE_ACCESS_JIT_GRANT_WAIT_LIMIT_MS,
    pollIntervalMs: Long = USAGE_ACCESS_JIT_GRANT_POLL_INTERVAL_MS,
    sleep: suspend (Long) -> Unit = { delay(it) },
): Boolean {
    var waitedMs = 0L
    while (!isGranted()) {
        if (waitedMs >= limitMs) return false
        val stepMs = minOf(pollIntervalMs, limitMs - waitedMs)
        sleep(stepMs)
        waitedMs += stepMs
    }
    return true
}

/**
 * Issue #371: the JIT request dialog — a contextual, optional permission
 * request satisfying TO-BE §7.3's one-sentence standard (what for / why now /
 * what changes when declined), with optionality stated in text. The decline
 * path ("続行", system Back, dismiss) is always equivalent to continuing
 * without the grant. A failed settings launch keeps the dialog open with a
 * textual failure note and the continue action (the single normative failure
 * path; spec scenario "設定画面が開けない環境でも壊れない").
 */
@Composable
fun UsageAccessJitRequestDialog(
    settingsLaunchFailed: Boolean,
    onOpenSettings: () -> Unit,
    onContinue: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onContinue,
        modifier = Modifier.testTag("usage_access_jit_dialog"),
        title = {
            Text(
                text = stringResource(id = R.string.organizer_usage_access_jit_title),
                modifier = Modifier.testTag("usage_access_jit_title"),
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(id = R.string.organizer_usage_access_jit_body),
                    modifier = Modifier.testTag("usage_access_jit_body"),
                )
                if (settingsLaunchFailed) {
                    Text(
                        text = stringResource(id = R.string.organizer_usage_access_jit_settings_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .testTag("usage_access_jit_settings_unavailable"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onOpenSettings,
                modifier = Modifier.testTag("usage_access_jit_open_settings"),
            ) {
                Text(text = stringResource(id = R.string.organizer_usage_access_jit_open_settings))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onContinue,
                modifier = Modifier.testTag("usage_access_jit_continue"),
            ) {
                Text(text = stringResource(id = R.string.organizer_usage_access_jit_continue))
            }
        },
    )
}

/**
 * Opens the system usage-access settings without crashing on devices where
 * the target activity cannot be resolved (spec: the JIT request must handle
 * the unsupported case; the permanent row predates this contract and is
 * unchanged). Returns whether the launch was attempted successfully.
 */
internal fun openUsageAccessSettings(context: Context): Boolean = try {
    context.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
    true
} catch (ignored: ActivityNotFoundException) {
    false
}

/**
 * Issue #371: run-surface host of the JIT request dialog. Presented when the
 * run owns the gate reservation; a waiter (whose request lost the atomic
 * acquisition) stays dialog-free and resumes from the gate's resolution, or
 * re-acquires the presentation right when the previous owner released before
 * presenting. Returning from the system settings triggers the bounded grant
 * re-read, and the paused run resumes granted or not — the decline is never a
 * failure.
 */
@Composable
internal fun RunUsageAccessJitDialogHost(
    run: ManualOrganizationRun,
    // Injectable for instrumentation of the unsupported-settings path (the
    // system settings resolution itself is device-dependent, spec 371).
    settingsOpener: (Context) -> Boolean = ::openUsageAccessSettings,
    // Injectable for the deterministic settings-return instrumentation: the
    // bounded re-read is driven by ON_RESUME (spec 371 JIT-AC-03).
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
) {
    val context = LocalContext.current
    val gateSnapshot by run.usageAccessGate.snapshot.collectAsStateWithLifecycle()
    val runState by run.stateFlow.collectAsStateWithLifecycle()
    val awaiting = runState as? ManualOrganizationRun.State.AwaitingUsageAccessJit
    val awaitingRunId = awaiting?.runId

    // Per-pause host state. NOT keyed on awaitingRunId: the lifecycle observer
    // below captures these State objects once, and key-based re-initialization
    // would replace them under a still-registered observer (stale reads).
    // Instead, the reset effect below re-derives them per pause identity.
    var presenter by remember { mutableStateOf(false) }
    var settingsRequested by remember { mutableStateOf(false) }
    var settingsLaunchFailed by remember { mutableStateOf(false) }
    var grantCheckTick by remember { mutableIntStateOf(0) }
    DisposableEffect(awaitingRunId) {
        presenter = awaiting?.isOwner == true
        settingsRequested = false
        settingsLaunchFailed = false
        grantCheckTick = 0
        onDispose { }
    }

    // Waiter wakeup: deterministic observation of the gate snapshot. A
    // resolved barrier unblocks the paused composition; a released
    // reservation lets this waiter re-acquire the presentation right.
    LaunchedEffect(gateSnapshot, awaitingRunId) {
        if (awaitingRunId == null || presenter) return@LaunchedEffect
        when (gateSnapshot.phase) {
            UsageAccessJitGate.Phase.Resolved -> run.continueAfterUsageAccessGate()

            UsageAccessJitGate.Phase.Available -> when (run.usageAccessGate.evaluate(awaitingRunId)) {
                UsageAccessJitGate.Decision.Present -> presenter = true
                UsageAccessJitGate.Decision.Proceed -> run.continueAfterUsageAccessGate()
                UsageAccessJitGate.Decision.Wait -> Unit
            }

            else -> Unit
        }
    }
    // Presenting consumes the re-presentation right exactly once.
    LaunchedEffect(presenter, awaitingRunId) {
        if (presenter && awaitingRunId != null) {
            run.usageAccessGate.markPresented(awaitingRunId)
        }
    }
    // Returning from the system settings: observe ON_RESUME, run the bounded
    // grant re-read, then resume the paused composition (granted or not).
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && settingsRequested) {
                settingsRequested = false
                grantCheckTick += 1
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(grantCheckTick, awaitingRunId) {
        if (grantCheckTick == 0 || awaitingRunId == null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            awaitUsageAccessGrant(isGranted = { UsageAccess.isGranted(context) })
        }
        run.continueAfterUsageAccessGate()
    }

    if (presenter && awaiting != null) {
        UsageAccessJitRequestDialog(
            settingsLaunchFailed = settingsLaunchFailed,
            onOpenSettings = {
                if (settingsOpener(context)) {
                    settingsRequested = true
                } else {
                    settingsLaunchFailed = true
                }
            },
            onContinue = { run.continueAfterUsageAccessGate() },
        )
    }
}
