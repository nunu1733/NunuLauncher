package app.lawnchair.organizer.ui

/** Admission seam shared by production organization runs, recovery, and authoring. */
internal interface OrganizationOperationGate {
    fun tryAcquire(kind: OrganizationOperationLease.Kind): AutoCloseable?
}

/**
 * One process-local admission domain for production organization runs, recovery,
 * and category-override authoring. Rule Management never depends on run state.
 */
internal object OrganizationOperationLease : OrganizationOperationGate {
    enum class Kind { RUN, RECOVERY, AUTHORING }

    private val lock = Any()
    private var active: Token? = null

    override fun tryAcquire(kind: Kind): Token? = synchronized(lock) {
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

/** Keeps deterministic, directly constructed state-machine tests independent. */
internal object NoopOrganizationOperationGate : OrganizationOperationGate {
    override fun tryAcquire(kind: OrganizationOperationLease.Kind): AutoCloseable = Token

    private object Token : AutoCloseable {
        override fun close() = Unit
    }
}
