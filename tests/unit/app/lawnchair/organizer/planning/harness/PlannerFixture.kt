package app.lawnchair.organizer.planning.harness

import app.lawnchair.organizer.planning.CategoryDecision
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.OrganizationInput
import app.lawnchair.organizer.planning.PreserveReason
import app.lawnchair.organizer.planning.RejectionCode
import app.lawnchair.organizer.planning.RejectionReason
import app.lawnchair.organizer.planning.UnplacedItem
import app.lawnchair.organizer.planning.UnplacedReason
import app.lawnchair.organizer.planning.WarningCode

internal data class FixtureId(val value: String) {
    init {
        require(value.isNotEmpty())
    }
}

internal data class PlannerFixture(
    val id: FixtureId,
    val input: OrganizationInput,
    val expectation: FixtureExpectation,
    val checks: Set<ContractCheck>,
    val reproduction: Reproduction? = null,
)

internal data class FixtureExpectation(
    val outcome: ExpectedOutcome,
    val requiredWarningCodes: Set<WarningCode> = emptySet(),
)

internal sealed interface ExpectedOutcome {
    data class Planned(
        val requiredPreservations: Map<ItemId, PreserveReason> = emptyMap(),
        val requiredCategories: Set<CategoryDecision> = emptySet(),
        val expectedNewPageCount: Int? = null,
        val expectedNewFolderCount: Int? = null,
    ) : ExpectedOutcome {
        init {
            require(expectedNewPageCount == null || expectedNewPageCount >= 0)
            require(expectedNewFolderCount == null || expectedNewFolderCount >= 0)
        }
    }

    data class Invalid(
        val requiredCodes: Set<RejectionCode>,
        val requiredDetails: Set<RejectionReason> = emptySet(),
    ) : ExpectedOutcome {
        init {
            require(requiredCodes.isNotEmpty())
            require(requiredDetails.all { it.code in requiredCodes })
        }
    }

    data class Impossible(
        val requiredReasons: Set<UnplacedReason>,
        val requiredItems: Set<UnplacedItem> = emptySet(),
    ) : ExpectedOutcome {
        init {
            require(requiredReasons.isNotEmpty())
            require(requiredItems.all { it.reason in requiredReasons })
        }
    }
}

internal enum class ContractCheck {
    EXPECTATION,
    CONSERVATION,
    BOUNDS,
    NO_OVERLAP,
    CONTAINER_INTEGRITY,
    LOCK_PRESERVATION,
    PROFILE_ISOLATION,
    DETERMINISM,
    INPUT_PERMUTATION,
    IDEMPOTENCE,
}

internal data class ContractViolation(
    val fixtureId: FixtureId,
    val check: ContractCheck,
    val reproduction: Reproduction?,
    val message: String,
)

internal data class VerificationReport(
    val violations: List<ContractViolation>,
) {
    val isSuccess: Boolean get() = violations.isEmpty()
}

internal data class Reproduction(
    val seed: Long,
    val caseIndex: Int,
) {
    init {
        require(caseIndex >= 0)
    }

    override fun toString(): String = "./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests '*PlannerGeneratedPropertyTest*' -Dplanner.seed=$seed -Dplanner.case=$caseIndex"
}

internal data class ValidationRuleId(val value: String) {
    init {
        require(value.matches(Regex("V-(0[1-9]|1[0-9]|2[0-2])(?:-[a-z0-9-]+)?")))
    }

    val rule: String get() = value.take(4)
}

internal data class ScenarioId(val value: String) {
    init {
        require(value.matches(Regex("S-(0[1-9]|1[0-9]|20)")))
    }
}

internal sealed interface CoverageEvidence {
    data class Fixture(
        val fixture: FixtureId,
        val checks: Set<ContractCheck>,
    ) : CoverageEvidence {
        init {
            require(checks.isNotEmpty())
        }
    }

    data class Generated(val checks: Set<ContractCheck>) : CoverageEvidence {
        init {
            require(checks.isNotEmpty())
        }
    }

    data class Downstream(val issue: Int, val clause: String) : CoverageEvidence {
        init {
            require(issue > 0)
            require(clause.isNotBlank())
        }
    }
}

internal data class CoverageRow<K>(
    val id: K,
    val evidence: List<CoverageEvidence>,
) {
    init {
        require(evidence.isNotEmpty())
    }
}
