// Issue #565: cold-start deadlock regression at the LawnchairLayoutFactory seam.
// Oracle: docs/assessment/563-home-empty-overview-evidence/anr/cold-start-deadlock-anr-trace.txt
// Spec: specs/565-cold-start-font-lazy-deadlock/spec-lite.md
//
// Contract (hazard form, independent of the chosen fix implementation):
//   Resolving the font manager for a factory onCreateView call must never place
//   a caller behind a monitor that another in-flight factory caller holds
//   across its wait for main-thread initialization. The production cycle in the
//   ANR trace was: a ViewPool-init thread held the factory's Kotlin `by lazy`
//   monitor while parked in MainThreadInitializedObject.get() (which needs the
//   main thread), while main blocked on that same monitor inside inflation, so
//   main never drained the MAIN_EXECUTOR queue -> circular wait -> home
//   ANR/wedge on cold start.
//
// The hazard window requires main to be occupied without dispatching the main
// Handler queue, exactly as ActivityThread does while Launcher.onCreate
// inflates. The test therefore drives the production entry point
// (LawnchairLayoutFactory.onCreateView) from background threads and probes the
// contended state from a runOnMainSync body: the main thread is busy inside the
// probe for its whole duration, so a held-across-wait lock stays observable and
// an unfixed implementation fails (throws) instead of hanging the process.
// Background callers parked on their main-executor futures complete when the
// instrumentation looper resumes between test methods; bWarm... asserts those
// joins. Method order is fixed so the cold probe always runs first, and
// FontManager.INSTANCE starts unresolved in this process.
package app.lawnchair.startup

import android.content.res.XmlResourceParser
import android.os.Looper
import android.widget.TextView
import org.xmlpull.v1.XmlPullParser
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import app.lawnchair.LawnchairLayoutFactory
import app.lawnchair.font.FontManager
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@SmallTest
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ColdStartFontLazyInversionInstrumentationTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val caller1 = AtomicReference<Thread?>(null)
    private val caller2 = AtomicReference<Thread?>(null)
    private val caller1View = AtomicReference<Any?>(null)
    private val caller2View = AtomicReference<Any?>(null)
    private val caller1Error = AtomicReference<Throwable?>(null)
    private val caller2Error = AtomicReference<Throwable?>(null)

    private fun compiledTextViewAttrs(): XmlResourceParser {
        val parser = context.resources.getLayout(android.R.layout.simple_list_item_1)
        while (parser.eventType != XmlPullParser.START_TAG &&
            parser.next() != XmlPullParser.END_DOCUMENT
        ) {
        }
        return parser
    }

    private fun Thread.awaitState(target: Thread.State, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (state == target) return true
            Thread.sleep(20)
        }
        return state == target
    }

    private fun Thread.joinAndWait(ms: Long): Boolean {
        join(ms)
        return !isAlive
    }

    private fun stackOf(t: Thread) =
        t.stackTrace.take(10).joinToString("\n    ") { "${it.className}.${it.methodName}:${it.lineNumber}" }

    @Test
    fun a0FontManagerProviderIsConstructibleOnMain() {
        // Bare provider contract: FontManager must construct on main without
        // the factory's runCatching wrapper, which would otherwise hide a
        // construction failure and make the cold probe vacuous.
        FontManager.INSTANCE.initializeForTesting(null)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertNotNull(FontManager.INSTANCE.get(context))
        }
        assertNotNull(FontManager.INSTANCE.getNoCreate())
    }

    @Test
    fun a1ColdConcurrentFontAccessNeverBlocksASecondCallerOnAMonitor() {
        // Hermetic cold state: FontManager unresolved (process-static MTIO).
        FontManager.INSTANCE.initializeForTesting(null)
        assertNull("precondition: FontManager must be unresolved", FontManager.INSTANCE.getNoCreate())

        val factory = LawnchairLayoutFactory(context)
        caller1.set(
            Thread({
                try {
                    caller1View.set(factory.onCreateView(null, "TextView", context, compiledTextViewAttrs()))
                } catch (t: Throwable) {
                    caller1Error.set(t)
                }
            }, "i565-bg-inflate-1").apply { start() },
        )

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue(
                "probe body must execute on the main looper thread",
                Looper.myLooper() == Looper.getMainLooper(),
            )
            val t1 = caller1.get()!!
            val parked = t1.awaitState(Thread.State.WAITING, 3_000)
            if (!parked && t1.state == Thread.State.TERMINATED) {
                // Caller terminated without ever parking: either it errored
                // (must surface) or the implementation resolved without any
                // cross-thread wait (no hazard window can form).
                caller1Error.get()?.let { throw AssertionError("cold caller errored", it) }
                assertNotNull("cold caller must have returned a view", caller1View.get())
                return@runOnMainSync
            }
            assertTrue("first caller must be waiting mid-resolution, was ${t1.state}", parked)
            assertNull(
                "precondition: font manager still unresolved while first caller is inside onCreateView",
                FontManager.INSTANCE.getNoCreate(),
            )

            caller2.set(
                Thread({
                    try {
                        caller2View.set(factory.onCreateView(null, "TextView", context, compiledTextViewAttrs()))
                    } catch (t: Throwable) {
                        caller2Error.set(t)
                    }
                }, "i565-bg-inflate-2").apply { start() },
            )
            val t2 = caller2.get()!!
            var verdict: Thread.State? = null
            val deadline = System.currentTimeMillis() + 2_000
            while (verdict == null && System.currentTimeMillis() < deadline) {
                val s = t2.state
                if (s == Thread.State.BLOCKED || s == Thread.State.WAITING || s == Thread.State.TERMINATED) {
                    verdict = s
                } else {
                    Thread.sleep(10)
                }
            }
            if (verdict == Thread.State.BLOCKED) {
                throw AssertionError(
                    buildString {
                        appendLine("REGRESSION #565: second factory caller is BLOCKED on a monitor held across the main-thread init wait.")
                        appendLine("  caller-1 (${t1.state}):\n    ${stackOf(t1)}")
                        appendLine("  caller-2 (${t2.state}):\n    ${stackOf(t2)}")
                    },
                )
            }

            val mainView = factory.onCreateView(null, "TextView", context, compiledTextViewAttrs())
            assertTrue("main-thread factory call must return a TextView", mainView is TextView)
            assertNotNull("font manager resolved on main", FontManager.INSTANCE.getNoCreate())
        }
    }

    @Test
    fun bWarmBackgroundCallsCompleteUnaidedAfterInit() {
        // The cold callers must have finished once the instrumentation looper
        // resumed between test methods (production equivalent: main returning
        // to its loop after onCreate).
        caller1.get()?.let {
            assertTrue("cold caller-1 must complete after main resumed its loop", it.joinAndWait(10_000))
            assertNull("cold caller-1 must not error", caller1Error.get())
            assertNotNull("cold caller-1 must have returned a view", caller1View.get())
        }
        caller2.get()?.let {
            assertTrue("cold caller-2 must complete after main resumed its loop", it.joinAndWait(10_000))
            assertNull("cold caller-2 must not error", caller2Error.get())
            assertNotNull("cold caller-2 must have returned a view", caller2View.get())
        }

        assertNotNull(FontManager.INSTANCE.getNoCreate())
        val factory = LawnchairLayoutFactory(context)
        val done = CountDownLatch(2)
        val failures = AtomicBoolean(false)
        val threads = (1..2).map { i ->
            Thread({
                try {
                    if (factory.onCreateView(null, "TextView", context, compiledTextViewAttrs()) == null) {
                        failures.set(true)
                    }
                } catch (t: Throwable) {
                    failures.set(true)
                }
                done.countDown()
            }, "i565-warm-$i").apply { start() }
        }
        val ok = done.await(10, TimeUnit.SECONDS)
        threads.forEach { assertTrue("warm caller ${it.name} must finish unaided", it.joinAndWait(5_000)) }
        assertTrue("warm background factory calls must complete without main-thread involvement", ok)
        assertTrue("warm background factory calls must return views without error", !failures.get())
    }

    @Test
    fun cFactoryConstructorContractStaysOnMainThread() {
        val factory = LawnchairLayoutFactory(context)
        val tv = factory.onCreateView(null, "TextView", context, compiledTextViewAttrs())
        assertTrue(tv is TextView)
        assertNull(factory.onCreateView(null, "FrameLayout", context, compiledTextViewAttrs()))
    }
}
