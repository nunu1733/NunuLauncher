package app.lawnchair.organizer.personalization

/**
 * Issue #204: the injected randomness seam of the pure export builder. Supplies
 * fresh random identifiers for BOTH the export instance id (`exportId`) and
 * each export item `ref` — one seam, one entropy contract (spec 204 生成規則).
 *
 * Monotonic counters, timestamps, and any derivation from canonical inputs are
 * contractually prohibited for both identifier kinds. Tests inject a
 * deterministic allocator; the production implementation lives at the
 * integration boundary with crypto-strength randomness.
 */
fun interface RandomIdAllocator {
    fun newId(): String
}

/** Deterministic allocator for tests (repeating or sequence-based). */
class SequentialIdAllocator(
    private var counter: Int = 0,
    private val format: (Int) -> String = { "id-$it" },
) : RandomIdAllocator {
    override fun newId(): String = format(counter++)
}
