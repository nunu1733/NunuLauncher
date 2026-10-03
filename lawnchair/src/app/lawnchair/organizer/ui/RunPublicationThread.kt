package app.lawnchair.organizer.ui

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicReference

/**
 * Issue #418: the thread that owns organizer run UI state publication.
 *
 * The run state machine executes on worker threads (its callers dispatch it
 * through `Dispatchers.IO`), while every UI-facing state write must land on
 * the app main thread (spec 418; Android's single-thread UI rule). This seam
 * is the single hop point: write helpers call [run] with a lock-free,
 * prompt-completing block while the machine keeps holding its own lock, so
 * section atomicity and publication instants are unchanged (spec 375
 * linearization and spec 369 RD-7 orderings preserved).
 *
 * Invariants the production implementation must keep (spec 375 amendment,
 * "gate下のUI待機禁止の例外"):
 * - the block given to [run] only writes the state bus — it never takes the
 *   run lock, the exchange mutation gate, the usage access gate, the journal,
 *   or any durable store;
 * - the main thread never waits on the run lock (the machine never runs on
 *   the publication thread — [assertNotPublicationThread] fails fast on
 *   violations), so a worker holding the run lock while joining [run] can
 *   never form a lock cycle.
 */
interface RunPublicationThread {
    /** True when the calling thread is the publication thread. */
    val isCurrent: Boolean

    /** Execute [block] on the publication thread and resume the caller. */
    fun <T> run(block: () -> T): T

    /**
     * Fail fast when called on the publication thread. Machine entry points
     * invoke this so a future UI caller cannot start executing the run state
     * machine on main.
     */
    fun assertNotPublicationThread()
}

/**
 * Same-thread implementation used by JVM tests and instrumentation tests:
 * hops are no-ops and the guard accepts any thread, which keeps today's
 * single-threaded behavior exactly.
 */
class DirectRunPublicationThread : RunPublicationThread {
    override val isCurrent: Boolean get() = true
    override fun <T> run(block: () -> T): T = block()
    override fun assertNotPublicationThread() = Unit
}

/**
 * Issue #418 (Phase2 review round 2): the shared uninterruptible latch join
 * behind BOTH [RunPublicationThread] implementations. Once a publication task
 * is queued, its caller — possibly holding the run lock and the exchange
 * mutation gate (spec 375) — must not unwind before the publication
 * completes; a late publication would invert the gate-release linearization.
 * Returns whether the wait observed an interrupt so the caller can re-assert
 * the interrupt status after completion. The JVM join oracle exercises THIS
 * primitive through [DedicatedThreadPublication], so production and test
 * cannot drift apart on this contract.
 */
internal fun CountDownLatch.awaitPublicationCompletion(): Boolean {
    var interrupted = false
    while (true) {
        try {
            await()
            return interrupted
        } catch (_: InterruptedException) {
            interrupted = true
        }
    }
}

/**
 * Production implementation: the Android main thread via [Handler]. FIFO,
 * block-join semantics; [assertNotPublicationThread] rejects main-thread
 * execution of the run state machine.
 */
class HandlerRunPublicationThread(
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : RunPublicationThread {

    override val isCurrent: Boolean get() = Looper.myLooper() == handler.looper

    override fun <T> run(block: () -> T): T {
        if (isCurrent) return block()
        val latch = CountDownLatch(1)
        val outcome = AtomicReference<Result<T>?>(null)
        val posted = handler.post {
            outcome.set(runCatching(block))
            latch.countDown()
        }
        check(posted) { "organizer run publication task could not be posted to the main looper" }
        val interrupted = latch.awaitPublicationCompletion()
        if (interrupted) Thread.currentThread().interrupt()
        return outcome.get()!!.getOrThrow()
    }

    override fun assertNotPublicationThread() {
        check(!isCurrent) {
            "organizer run state machine must not run on the publication (main) thread"
        }
    }
}

/**
 * Test double: a dedicated daemon thread as the publication thread. Same
 * FIFO block-join semantics as [HandlerRunPublicationThread] without Android
 * dependencies, so JVM oracles can observe real cross-thread publication.
 */
class DedicatedThreadPublication(
    threadName: String = "run-publication-test",
) : RunPublicationThread {
    private val queue = LinkedBlockingQueue<Runnable>()
    val thread: Thread

    init {
        var started: Thread? = null
        Thread({
            while (true) {
                queue.take().run()
            }
        }, threadName).apply {
            isDaemon = true
            start()
            started = this
        }
        thread = started!!
    }

    override val isCurrent: Boolean get() = Thread.currentThread() === thread

    override fun <T> run(block: () -> T): T {
        if (isCurrent) return block()
        val latch = CountDownLatch(1)
        val outcome = AtomicReference<Result<T>?>(null)
        queue.put {
            outcome.set(runCatching(block))
            latch.countDown()
        }
        val interrupted = latch.awaitPublicationCompletion()
        if (interrupted) Thread.currentThread().interrupt()
        return outcome.get()!!.getOrThrow()
    }

    override fun assertNotPublicationThread() {
        check(!isCurrent) {
            "organizer run state machine must not run on the publication thread"
        }
    }
}
