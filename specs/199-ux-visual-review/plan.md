# Implementation Plan: Portable vision-based UX visual review

> Issue: #199
> Spec: [spec.md](./spec.md)
> Status: draft

## Current evidence

- OpenAI documentation states that Codex discovers repository skills at `.agents/skills`, supports the open Agent Skills standard, and loads project-scoped custom agents from `.codex/agents/*.toml`.
- Codex custom-agent files require `name`, `description`, and `developer_instructions`; they can set `sandbox_mode = "read-only"` and omit `model` so model selection remains a runtime concern.
- The local runtime is `codex-cli 0.151.0`. No `zcode` CLI is currently discoverable in `PATH`; ZCode prototype validation therefore requires its supported GUI/runtime path or a documented static validation limitation.
- PR #198 CI run `33707975932` retains an ordered five-frame Organizer UI artifact suitable for an initial sequence calibration candidate. The artifact does not by itself provide a human UX baseline.

## Design

### Modules and interfaces

```text
.agents/skills/ux-visual-review/       # authoritative portable contract
├── SKILL.md
└── references/
    ├── evidence-policy.md
    ├── rubric.md
    ├── observer-agent.md
    ├── critic-agent.md
    └── report-schema.md

.codex/agents/                         # thin runtime adapters
├── ux-observer.toml
└── ux-critic.toml

<supported ZCode project path>/        # thin runtime adapters; research-gated
└── ...
```

The shared Skill owns all evaluation semantics. Adapters only select a role, point to the shared files, constrain writes, and record runtime limitations.

### Data flow

```text
minimal scenario + ordered evidence
  -> Observer (perception only)
  -> observer-v1 report
  -> Critic + shared rubric
  -> ux-visual-review/v1 advisory report
  -> human calibration/disposition
```

### Alternatives rejected

- One agent sees the spec and both observes and critiques: rejected because intended behavior contaminates first-impression evidence.
- Runtime-specific duplicated prompts: rejected because rubric and report semantics drift.
- Numeric UX score: rejected because category trade-offs and confidence disappear.
- Initial merge gate: rejected because false-positive/negative behavior is not calibrated.

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `.agents/skills/ux-visual-review/**` | Shared workflow, evidence policy, rubric, roles, report schema | Portable source of truth |
| `.codex/agents/ux-*.toml` | Read-only role adapters without model pinning | Codex prototype |
| ZCode adapter path from support research | Thin role adapters or documented unsupported prototype | Avoid speculative permanent path |
| `docs/assessment/issue-199-*.md` | Support matrix, architecture decision, calibration, recommendation | Research evidence |
| `specs/199-ux-visual-review/**` | Draft contract and implementation boundary | Issue/spec workflow |
| `AGENTS.md`, `docs/engineering/quality-strategy.md` | Update only if calibration supports repository adoption | Process source of truth |

## Migration and recovery

No application data, schema, rule, or backup migration. Removing the prototype files fully rolls back the capability. Retained calibration evidence is synthetic and can be removed independently.

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| UVR-AC-01, 04–06 | Skill structure and content validation | `python3 .../skill-creator/scripts/quick_validate.py .agents/skills/ux-visual-review` plus repository contract validation |
| UVR-AC-02, 07 | Support matrix and adapter invocation | Codex 0.151.0; supported ZCode runtime recorded by research |
| UVR-AC-03 | Isolated Observer leakage probe | Fresh subagent/session with only scenario + evidence manifest |
| UVR-AC-08 | 5–10 case calibration | Two-stage reports compared with human baseline |
| UVR-AC-09 | Assessment review | Advisory recommendation and follow-up Issue list |

## Documentation updates

- [x] Draft spec and plan.
- [x] Support matrix / architecture assessment.
- [x] Initial calibration artifact; full 5–10 case set remains open.
- [ ] `AGENTS.md` decision after calibration.
- [ ] `docs/engineering/quality-strategy.md` decision after calibration.
- [ ] ADR only if the final decision meets all ADR criteria.

## Execution checklist

- [x] Issue and repository governing documents reviewed.
- [x] Shared Skill draft created.
- [x] Codex thin adapters created and statically validated; fresh-session Observer discovery and no-image negative path runtime-validated.
- [x] ZCode support boundary established; concrete plugin adapter intentionally withheld until no-copy runtime validation is available.
- [x] Observer and Critic forward-test pilot completed with isolated Luna/xhigh subagents.
- [ ] 5–10 case calibration and human comparison completed.
- [x] Advisory-only adoption recommendation and proposed follow-up Issue split recorded in the assessment.
- [ ] Spec owner review obtained before marking accepted or enforcing the process.
