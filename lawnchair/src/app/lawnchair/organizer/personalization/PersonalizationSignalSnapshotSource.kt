package app.lawnchair.organizer.personalization

/**
 * Issue #203: the optional personalization input source. Implementations are
 * total — they never throw; permission state, query failures, and per-profile
 * failures are all expressed inside the returned typed snapshot. The composer
 * performs exactly one read per composition attempt, outside the mandatory
 * dynamic cut; a personalization failure must never make the composition
 * `NotReady` (FR-013 / D-010).
 */
interface PersonalizationSignalSnapshotSource {
    fun read(request: UsageSignalRequest): PersonalizationSignalSnapshot
}
