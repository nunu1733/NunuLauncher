# Visual evidence policy

## Required input

Provide a minimal scenario and an evidence manifest.

The scenario states only:

- the user's goal;
- the action or flow being attempted;
- any user-visible risk needed to interpret the flow.

Do not give the Observer acceptance criteria, implementation plans, source code, intended hierarchy, known defects, or a proposed fix.

Each evidence item has a stable ID and records:

- ordered sequence position;
- user-visible state or the action immediately preceding it;
- file or attachment reference;
- locale, theme, font scale, viewport or device class when known;
- capture provenance and date when the evidence will be retained.

## Sequence coverage

Use one screenshot only for a genuinely static question. For a stateful flow, include the smallest sequence that exposes the relevant continuity:

1. entry or pre-action state;
2. action-ready or decision state;
3. immediate visible response;
4. terminal success, failure, stale, or recovery state relevant to the scenario.

Add warning, destructive-confirmation, expanded-content, loading, or empty states when the review question depends on them. Omitted states must be named as limitations.

Review visible evidence only. Do not infer animation, focus movement, scrolling reachability, or off-screen content from still images. Use video, interaction logs, or deterministic tests for those claims.

## Privacy and retention

- Prefer synthetic fixtures and test accounts.
- Do not retain private device data, personal names, notification contents, account identifiers, package identifiers, or location data.
- Crop or redact unrelated sensitive content before review, while preserving enough surrounding layout to judge hierarchy and orientation.
- Record whether evidence is repository-retained, CI-retained, or ephemeral.

## Insufficient evidence

Return `insufficient-evidence`, with missing evidence and the smallest useful next capture, when any of these prevents a defensible review:

- the image is absent, unreadable, materially cropped, or its order is unknown;
- the user goal or visible state cannot be identified;
- a stateful claim is supported only by an unrelated final frame;
- the important action, warning, result, or error is off-screen;
- screenshots are presented as proof of motion, accessibility semantics, numeric geometry, or functional correctness;
- evidence from different builds or scenarios is mixed without provenance.

Insufficient evidence is not a pass, failure, or defect finding.

## Deterministic authority

Keep these surfaces authoritative in tests or measurement tools:

- exact contrast ratio and color-token compliance;
- touch-target dimensions;
- clipping and overflow detection;
- 200% font-scale support;
- TalkBack semantics and focus order;
- keyboard or switch traversal;
- functional state transitions and persistence;
- acceptance-criteria and spec compliance.

A review may describe a visible symptom and request the relevant deterministic check. It must not invent measurements or mark these surfaces passed.
