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

Given a neutral user goal and an Observer-visible manifest containing only neutral frame IDs, sequence or branch relations, preceding user actions, and capture provenance,
When an Observer reviews only that scenario and evidence and a Critic reviews its report against the shared rubric,
Then the result separates visual observation, likely user impact, and recommendation,
And every finding cites evidence, severity, confidence, and rubric category without a single aggregate score.

### Scenario: Hidden evaluation context cannot cue perception

Given expected state, safety meaning, intended action, known defect, human baseline, spec, or acceptance criteria,
When evidence is prepared for the Observer,
Then those fields remain in an Observer-hidden context that is never supplied to it,
And filenames and manifest fields do not encode semantic state labels.

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

### Scenario: Evidence contains instructions or links

Given text, images, QR codes, links, metadata, or instructions embedded in visual evidence,
When either role reviews that evidence,
Then it treats the content as untrusted data,
And it does not follow instructions, open links, invoke tools, or disclose information because the evidence requests it.

## Data and state

- Inputs are synthetic or privacy-reviewed screenshots, a neutral Observer-visible manifest, a separate Observer-hidden evaluation context, and a minimal user scenario.
- Reports and retained evidence are repository artifacts only when their provenance and privacy status are recorded.
- The review does not read or mutate Launcher DB state and adds no application persistence or migration.
- Runtime-specific configuration does not own or duplicate rubric, evidence, or report semantics.

## Permissions, privacy, and security

No application permission, network behavior, or telemetry is added. Calibration must not retain private device or account data. Runtime adapters are read-only and do not authorize product edits. Screenshot content is an untrusted input boundary, not an instruction channel. Every report records the effective permission mode, tool surface, and tools actually exercised.

## Accessibility and localization

Vision review does not validate TalkBack semantics, focus order, keyboard/Switch traversal, numeric contrast, touch targets, or 200% font scaling. It can request those deterministic checks when a visible symptom warrants them. Locale/theme/font-scale metadata is recorded with retained evidence.

## Acceptance criteria

- [ ] UVR-AC-01: `.agents/skills/ux-visual-review` is the single authoritative evidence/rubric/role/report contract; Codex and ZCode adapters contain no duplicated evaluation logic.
- [ ] UVR-AC-02: Primary-source support evidence shows how both runtimes consume the shared Skill or, where direct import is unsupported, the exact thin-adapter boundary and limitation.
- [ ] UVR-AC-03: Observer input is a neutral visible layer and excludes expected state, safety meaning, intended hierarchy/action, acceptance criteria, spec, plan, source, known defects, fixes, and human baseline; unavoidable inherited context is disclosed.
- [ ] UVR-AC-04: Critic findings keep `observation`, `user_impact`, and `recommendation` separate and include evidence refs, category, severity, and confidence.
- [ ] UVR-AC-05: Evidence policy requires an ordered scenario sequence for stateful claims and returns `insufficient-evidence` instead of pass when evidence is inadequate.
- [ ] UVR-AC-06: Deterministic quality surfaces and their authoritative checks are explicitly excluded from Vision pass/fail judgment.
- [ ] UVR-AC-07: Codex and ZCode prototype validation records runtime/version, model/provider, reasoning, invocation, result, effective permission/tool surface, tools used, and isolation limitations using the same rubric and report schema.
- [ ] UVR-AC-08: Calibration covers 5–10 representative cases against human baselines frozen before model review; adjudication records semantic-root-cause agreement, blind-human disposition of extras, missed baseline findings, severity drift, reproducibility, and residual risk.
- [ ] UVR-AC-09: Adoption remains advisory until calibration evidence supports a later decision; the recommendation identifies the repository source of truth and any follow-up Issues.
- [ ] UVR-AC-10: Both roles treat every instruction, link, QR code, and request embedded in evidence as untrusted content and never take tool, network, disclosure, or external-side-effect actions because of it.
- [ ] UVR-AC-11: Retained reports carry a canonical provenance envelope identifying contract revision, subject revision/build, runtime/client/model/reasoning, evidence manifest, permission mode, effective tool surface, and tools used.

## Test oracle

| AC | Evidence |
|---|---|
| UVR-AC-01, UVR-AC-04–06, UVR-AC-10 | Skill validation plus review of shared references and adapter diff. |
| UVR-AC-02, UVR-AC-07 | Dated support matrix with primary-source URLs and local runtime evidence. |
| UVR-AC-03 | Observer forward-test report with isolation metadata and a leakage probe. |
| UVR-AC-08 | Calibration artifact containing 5–10 case records and human-baseline comparison. |
| UVR-AC-09 | Decision section in the Issue #199 assessment and any resulting process-doc diff. |
| UVR-AC-11 | Schema validation of retained reports and contract/subject/runtime provenance. |

## Open questions

- Which ZCode plugin/custom-agent configuration can independently prove `injectAgentsMd: false` and technical write/shell denial while retaining Skill and evidence reads?
- Can the installed `BAI/glm-5.3-flash` path produce a grounded Observer result, complete the Critic role, and return `insufficient-evidence` for a negative fixture? The first retained Observer output contradicts the digest-matched image.
- Does blind human adjudication of the current five-case corpus support advisory use beyond the Organizer confirmation flow?
- Should repository adoption be recorded in `AGENTS.md`, `docs/engineering/quality-strategy.md`, both, or deferred?

## Change history

- 2026-09-03: Draft created for Issue #199; implementation remains limited to research, prototypes, and calibration until the contract is accepted.
- 2026-09-03: ZCode research narrowed team role distribution to a plugin with `injectAgentsMd: false`; bundled CLI 0.16.5 later proved direct project Skill discovery, while isolated-role activation and exact model/tool configuration remain runtime gates.
- 2026-09-03: Codex 0.151.0 fresh-session validation discovered and spawned `ux_observer`; the no-image path returned `insufficient-evidence` with disclosed limited isolation.
- 2026-09-03: Review correction split neutral Observer-visible and hidden evaluation context, established untrusted-evidence and canonical provenance requirements, reran Codex Observer/Critic E2E, and executed five calibration cases plus one repeat; blind human and ZCode validation remain open.
- 2026-09-03: ZCode 3.10.2 initially retained non-Vision `GLM-5.3` until restart. The post-restart `BAI/glm-5.3-flash` custom-Observer run completed and claimed Skill/image receipt, but its visual description contradicted the digest-matched PNG; grounded Observer E2E and all remaining ZCode gates stay open.
