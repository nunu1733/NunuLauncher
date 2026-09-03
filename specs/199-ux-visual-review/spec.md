---
issue: "#199"
status: draft
requirements: []
updated: 2026-09-03
---

# Portable vision-based UX visual review contract

## Problem

Functional, accessibility, and specification verification do not independently assess first impression, visible hierarchy, scanability, affordance, trust, or state-to-state continuity. When the implementing agent also judges those qualities with full knowledge of the intended result, expectation leakage can hide user-perceived UX problems.

## Outcome

Codex and ZCode can run the same evidence-bound UX visual review through a repository-owned Agent Skill. A perception-only Observer is separated from a rubric-driven Critic, runtime adapters remain thin, insufficient evidence never becomes a pass, and deterministic checks remain authoritative for measurable behavior.

## Scope

- A repository-scoped `ux-visual-review` Skill owning evidence policy, rubric, role contracts, severity/confidence, and report schema.
- Thin, read-only Observer and Critic adapters for Codex and ZCode where supported by primary-source evidence.
- Screenshot-sequence review for stateful UI.
- Calibration against 5–10 representative UI cases and an independently recorded human baseline.
- A documented adoption recommendation and residual risks.

## Non-goals

- Replacing unit, instrumentation, accessibility, localization, contrast, touch-target, clipping, or functional-state tests.
- Automatically modifying the reviewed UI.
- Creating a subjective aggregate UX score.
- Making the initial review a merge blocker.
- Implementing Issue #195 UI changes.
- Permanently pinning a model vendor or version.

## Domain language

No NunuLauncher product-domain term is added. Observer, Critic, visual evidence, and finding are quality-process terms owned by this contract.

## Behavior scenarios

### Scenario: Stateful visual review with isolated perception

Given a minimal user goal and an ordered screenshot sequence with capture provenance,
When an Observer reviews only that scenario and evidence and a Critic reviews its report against the shared rubric,
Then the result separates visual observation, likely user impact, and recommendation,
And every finding cites evidence, severity, confidence, and rubric category without a single aggregate score.

### Scenario: Evidence cannot support the requested judgment

Given missing, unreadable, unordered, or materially incomplete visual evidence,
When the review cannot defensibly exclude a critical or major UX problem,
Then the result is `insufficient-evidence`, not pass or no-findings,
And it names the smallest additional capture needed.

### Scenario: Deterministic quality claim

Given a visible symptom that may involve contrast, target size, clipping, font scaling, semantics, focus order, traversal, functional state, or spec compliance,
When the Critic prepares the report,
Then it may describe the visible symptom but defers the pass/fail claim to the owning deterministic test or measurement.

### Scenario: Runtime adapter lacks complete isolation

Given a runtime that automatically supplies repository or parent-session context,
When its Observer adapter runs,
Then the adapter instructs the Observer not to inspect or use implementation context,
And the report records the isolation limitation rather than claiming full isolation.

## Data and state

- Inputs are synthetic or privacy-reviewed screenshots, ordered evidence metadata, and a minimal user scenario.
- Reports and retained evidence are repository artifacts only when their provenance and privacy status are recorded.
- The review does not read or mutate Launcher DB state and adds no application persistence or migration.
- Runtime-specific configuration does not own or duplicate rubric, evidence, or report semantics.

## Permissions, privacy, and security

No application permission, network behavior, or telemetry is added. Calibration must not retain private device or account data. Runtime adapters are read-only and do not authorize product edits.

## Accessibility and localization

Vision review does not validate TalkBack semantics, focus order, keyboard/Switch traversal, numeric contrast, touch targets, or 200% font scaling. It can request those deterministic checks when a visible symptom warrants them. Locale/theme/font-scale metadata is recorded with retained evidence.

## Acceptance criteria

- [ ] UVR-AC-01: `.agents/skills/ux-visual-review` is the single authoritative evidence/rubric/role/report contract; Codex and ZCode adapters contain no duplicated evaluation logic.
- [ ] UVR-AC-02: Primary-source support evidence shows how both runtimes consume the shared Skill or, where direct import is unsupported, the exact thin-adapter boundary and limitation.
- [ ] UVR-AC-03: Observer input excludes acceptance criteria, spec, plan, source, intended hierarchy, known defects, and fixes; unavoidable inherited context is disclosed.
- [ ] UVR-AC-04: Critic findings keep `observation`, `user_impact`, and `recommendation` separate and include evidence refs, category, severity, and confidence.
- [ ] UVR-AC-05: Evidence policy requires an ordered scenario sequence for stateful claims and returns `insufficient-evidence` instead of pass when evidence is inadequate.
- [ ] UVR-AC-06: Deterministic quality surfaces and their authoritative checks are explicitly excluded from Vision pass/fail judgment.
- [ ] UVR-AC-07: Codex and ZCode prototype validation records runtime/version, invocation, result, and isolation limitations using the same rubric and report schema.
- [ ] UVR-AC-08: Calibration covers 5–10 representative cases and records agreement, false-positive candidates, missed human-baseline findings, severity drift, reproducibility, and residual risk.
- [ ] UVR-AC-09: Adoption remains advisory until calibration evidence supports a later decision; the recommendation identifies the repository source of truth and any follow-up Issues.

## Test oracle

| AC | Evidence |
|---|---|
| UVR-AC-01, UVR-AC-04–06 | Skill validation plus review of shared references and adapter diff. |
| UVR-AC-02, UVR-AC-07 | Dated support matrix with primary-source URLs and local runtime evidence. |
| UVR-AC-03 | Observer forward-test report with isolation metadata and a leakage probe. |
| UVR-AC-08 | Calibration artifact containing 5–10 case records and human-baseline comparison. |
| UVR-AC-09 | Decision section in the Issue #199 assessment and any resulting process-doc diff. |

## Open questions

- Can a plugin-provided ZCode subagent activate the repository `.agents/skills/ux-visual-review` source without copying it in the target client?
- Which exact ZCode model/provider/protocol and read-only tool identifiers satisfy image input plus Skill activation in the validation runtime?
- Does the initial calibration support advisory use beyond the Organizer confirmation flow?
- Should repository adoption be recorded in `AGENTS.md`, `docs/engineering/quality-strategy.md`, both, or deferred?

## Change history

- 2026-09-03: Draft created for Issue #199; implementation remains limited to research, prototypes, and calibration until the contract is accepted.
- 2026-09-03: ZCode research narrowed the adapter to plugin distribution with `injectAgentsMd: false`; no-copy Skill activation and exact model/tool configuration remain runtime-validation gates.
- 2026-09-03: Codex 0.151.0 fresh-session validation discovered and spawned `ux_observer`; the no-image path returned `insufficient-evidence` with disclosed limited isolation.
