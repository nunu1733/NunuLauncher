package app.lawnchair.organizer.ui

/**
 * One process-local admission domain for organization runs, recovery, and
 * category-override authoring. The lease is intentionally a UI/coordinator
 * seam; Rule Management never depends on run state.
 */
internal object OrganizationOperationLease {
    enum class Kind { RUN, RECOVERY, AUTHORING }

    private val lock = Any()
    private var active: Token? = null

    fun tryAcquire(kind: Kind): Token? = synchronized(lock) {
        if (active != null) return@synchronized null
        Token(kind).also { active = it }
    }

    internal class Token internal constructor(
        val kind: Kind,
    ) : AutoCloseable {
        private var closed = false

        override fun close() {
            synchronized(lock) {
                if (!closed && active === this) active = null
                closed = true
            }
        }
    }
}
