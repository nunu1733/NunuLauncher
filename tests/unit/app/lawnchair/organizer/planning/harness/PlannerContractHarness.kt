package app.lawnchair.organizer.planning.harness

import app.lawnchair.organizer.planning.ItemKind
import app.lawnchair.organizer.planning.OrganizationInput
import app.lawnchair.organizer.planning.OrganizationPlanner
import app.lawnchair.organizer.planning.Planned
import app.lawnchair.organizer.planning.PlanningResult
import app.lawnchair.organizer.planning.RunMode

internal class PlannerContractHarness(
    private val planner: OrganizationPlanner,
) {
    fun verify(fixture: PlannerFixture): VerificationReport {
        if (fixture.checks.isEmpty()) return VerificationReport(emptyList())

        val findings = mutableListOf<OracleFinding>()
        val first = planner.plan(fixture.input)

        if (ContractCheck.EXPECTATION in fixture.checks) {
            findings += Oracle.checkExpectation(fixture.input, first, fixture.expectation)
        }

        val familyAndEchoMatch = Oracle.matchesExpectedFamilyAndEcho(fixture.input, first, fixture.expectation)
        val planned = first.outcome as? Planned
        if (familyAndEchoMatch && planned != null) {
            if (ContractCheck.CONSERVATION in fixture.checks) findings += Oracle.checkConservation(fixture.input, planned)
            if (ContractCheck.BOUNDS in fixture.checks) findings += Oracle.checkBounds(fixture.input, planned)
            if (ContractCheck.NO_OVERLAP in fixture.checks) findings += Oracle.checkNoOverlap(planned)
            if (ContractCheck.CONTAINER_INTEGRITY in fixture.checks) {
                findings += Oracle.checkContainerIntegrity(fixture.input, planned)
            }
            if (ContractCheck.LOCK_PRESERVATION in fixture.checks) {
                findings += Oracle.checkLockPreservation(fixture.input, planned)
            }
            if (ContractCheck.PROFILE_ISOLATION in fixture.checks) {
                findings += Oracle.checkProfileIsolation(fixture.input, planned)
            }
            if (ContractCheck.IDEMPOTENCE in fixture.checks && fixture.input.runMode == RunMode.FullOrganization) {
                when (val materialized = PostPlanMaterializer.materialize(fixture.input, planned)) {
                    is MaterializationResult.Failed -> findings += materialized.findings

                    is MaterializationResult.Success -> {
                        val replan = planner.plan(materialized.input)
                        findings += Oracle.checkIdempotence(materialized.input, replan)
                    }
                }
            }
            if (ContractCheck.DETERMINISM in fixture.checks) {
                findings += Oracle.checkDeterminism(first, planner.plan(fixture.input))
            }
            if (ContractCheck.INPUT_PERMUTATION in fixture.checks) {
                for (variant in permutationVariants(fixture.input)) {
                    val variantResult = planner.plan(variant)
                    if (variantResult != first) {
                        findings += OracleFinding(
                            ContractCheck.INPUT_PERMUTATION,
                            FindingSubject.None,
                            "Permutation-sensitive complete PlanningResult",
                        )
                    }
                }
            }
        }

        val violations = findings
            .sortedWith(findingComparator)
            .map { finding ->
                ContractViolation(
                    fixtureId = fixture.id,
                    check = finding.check,
                    reproduction = fixture.reproduction,
                    message = finding.message,
                )
            }
        return VerificationReport(violations)
    }

    private fun permutationVariants(input: OrganizationInput): List<OrganizationInput> {
        val variants = mutableListOf<OrganizationInput>()
        fun <T> List<T>.rotate(): List<T> = drop(1) + take(1)

        if (input.snapshot.pages.size > 1) {
            variants += input.copy(snapshot = input.snapshot.copy(pages = input.snapshot.pages.rotate()))
        }
        if (input.snapshot.items.size > 1) {
            variants += input.copy(snapshot = input.snapshot.copy(items = input.snapshot.items.rotate()))
        }
        if (input.signals.entries.size > 1) {
            variants += input.copy(signals = input.signals.copy(entries = input.signals.entries.rotate()))
        }
        if (input.targets.existing.size > 1) {
            variants += input.copy(targets = input.targets.copy(existing = input.targets.existing.rotate()))
        }
        if (input.targets.additions.size > 1) {
            variants += input.copy(targets = input.targets.copy(additions = input.targets.additions.rotate()))
        }
        if (input.taxonomy.allowedCategories.size > 1) {
            variants += input.copy(taxonomy = input.taxonomy.copy(allowedCategories = input.taxonomy.allowedCategories.rotate()))
        }
        input.snapshot.items.forEach { container ->
            if (container.kind == ItemKind.FOLDER && container.members.size > 1) {
                variants += input.copy(
                    snapshot = input.snapshot.copy(
                        items = input.snapshot.items.map {
                            if (it.id == container.id) it.copy(members = container.members.rotate()) else it
                        },
                    ),
                )
            }
            val metadata = container.appPair
            if (container.kind == ItemKind.APP_PAIR && metadata != null && metadata.members.size > 1) {
                variants += input.copy(
                    snapshot = input.snapshot.copy(
                        items = input.snapshot.items.map {
                            if (it.id == container.id) it.copy(appPair = metadata.copy(members = metadata.members.rotate())) else it
                        },
                    ),
                )
            }
        }
        return variants.filterNot { it == input }.distinct()
    }

    private val findingComparator = Comparator<OracleFinding> { left, right ->
        compareValues(left.check.ordinal, right.check.ordinal)
            .takeIf { it != 0 }
            ?: compareSubjects(left.subject, right.subject).takeIf { it != 0 }
            ?: left.message.compareTo(right.message)
    }

    private fun compareSubjects(left: FindingSubject, right: FindingSubject): Int {
        val leftRank = subjectRank(left)
        val rightRank = subjectRank(right)
        if (leftRank != rightRank) return leftRank.compareTo(rightRank)
        return when {
            left is FindingSubject.Item && right is FindingSubject.Item -> left.id.compareTo(right.id)
            left is FindingSubject.Page && right is FindingSubject.Page -> left.id.compareTo(right.id)
            left is FindingSubject.Folder && right is FindingSubject.Folder -> left.id.compareTo(right.id)
            left is FindingSubject.AppPair && right is FindingSubject.AppPair -> left.id.compareTo(right.id)
            left is FindingSubject.NewPage && right is FindingSubject.NewPage -> left.ordinal.compareTo(right.ordinal)
            left is FindingSubject.NewFolder && right is FindingSubject.NewFolder -> left.ordinal.compareTo(right.ordinal)
            else -> 0
        }
    }

    private fun subjectRank(subject: FindingSubject): Int = when (subject) {
        is FindingSubject.Item -> 0
        is FindingSubject.Page -> 1
        is FindingSubject.Folder -> 2
        is FindingSubject.AppPair -> 3
        is FindingSubject.NewPage -> 4
        is FindingSubject.NewFolder -> 5
        FindingSubject.None -> 6
    }
}
