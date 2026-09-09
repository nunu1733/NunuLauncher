package app.lawnchair.bugreport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the Issue #242 crash pre-handler isolation: however the
 * pre-handler work or the failure logger itself fail, the original throwable is
 * delegated to the platform default handler exactly once, and pre-handler
 * failures never escape the dispatcher.
 */
class CrashPreHandlerIsolationTest {

    private class RecordingHandler : Thread.UncaughtExceptionHandler {

        val calls = mutableListOf<Pair<Thread, Throwable>>()

        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            calls.add(thread to throwable)
        }
    }

    private val thread = Thread("crash-test")

    @Test
    fun `successful pre-handler work still delegates exactly once`() {
        val handler = RecordingHandler()
        val original = IllegalStateException("original crash")

        dispatchUncaughtException(
            handler,
            thread,
            original,
            preHandler = {},
        )

        assertEquals(listOf(thread to original), handler.calls)
    }

    @Test
    fun `pre-handler failure is handed to the failure logger and delegation wins`() {
        val handler = RecordingHandler()
        val original = IllegalStateException("original crash")
        val failure = java.io.IOException("reporter blew up")
        val logged = mutableListOf<Throwable>()

        dispatchUncaughtException(
            handler,
            thread,
            original,
            preHandler = { throw failure },
            onPreHandlerFailure = { logged.add(it) },
        )

        assertEquals(listOf(failure), logged)
        assertEquals(listOf(thread to original), handler.calls)
    }

    @Test
    fun `failure logger throwing does not skip or duplicate delegation`() {
        val handler = RecordingHandler()
        val original = IllegalStateException("original crash")
        val failure = java.io.IOException("reporter blew up")

        dispatchUncaughtException(
            handler,
            thread,
            original,
            preHandler = { throw failure },
            onPreHandlerFailure = { throw AssertionError("logger blew up too") },
        )

        assertEquals(listOf(thread to original), handler.calls)
    }

    @Test
    fun `null default handler swallows pre-handler failures without delegating`() {
        val original = IllegalStateException("original crash")
        val failure = java.io.IOException("reporter blew up")

        var escaped: Throwable? = null
        try {
            dispatchUncaughtException(
                null,
                thread,
                original,
                preHandler = { throw failure },
            )
        } catch (t: Throwable) {
            escaped = t
        }

        assertNull(escaped)
    }

    @Test
    fun `original throwable identity is preserved through delegation`() {
        val handler = RecordingHandler()
        val original = IllegalStateException("original crash")

        dispatchUncaughtException(
            handler,
            thread,
            original,
            preHandler = { throw RuntimeException("different failure") },
        )

        assertTrue(handler.calls.single().second === original)
    }
}
