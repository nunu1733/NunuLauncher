# Implementation Plan: Portable vision-based UX visual review

> Issue: #199
> Spec: [spec.md](./spec.md)
> Status: draft

## Current evidence

- OpenAI documentation states that Codex discovers repository skills at `.agents/skills`, supports the open Agent Skills standard, and loads project-scoped custom agents from `.codex/agents/*.toml`.
- Codex custom-agent files require `name`, `description`, and `developer_instructions`; they can set `sandbox_mode = "read-only"` and omit `model` so model selection remains a runtime concern.
- The local runtime is Codex CLI 0.151.0 through provider `openai` and protocol `codex`. Observer/Critic image E2E and a sandbox write-denial probe passed with the project adapters; hidden workspace files remain technically readable, so isolation is `limited`.
- ZCode 3.10.2 with bundled CLI 0.16.5 directly discovers the repository Skill without copying. After two retained grounding failures, a fresh user-level run through provider `BAI` and `glm-5.3-flash` matched all six prominent case-06 anchors, completed Critic review, returned `insufficient-evidence` for missing-image case-07, ignored screenshot-carried pseudo-instructions, and reproduced the contract digest. The active protocol remains `unknown`; Write/Bash were absent rather than exercised through a permission-error path, repository Read remained broad, and `injectAgentsMd: false` has behavioral but not independent input-trace evidence.
- The calibration corpus contains five cases and one repeat. The known Issue #123 dark-theme defect was missed; extras on clean cases remain pending blind human adjudication.

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
    ├── report-schema.md
    └── calibration.md

.codex/agents/                         # thin runtime adapters
├── ux-observer.toml
└── ux-critic.toml

<supported ZCode project path>/        # thin runtime adapters; research-gated
└── ...
```

The shared Skill owns all evaluation semantics. Adapters only select a role, point to the shared files, constrain writes, and record runtime limitations.

### Data flow

```text
neutral scenario + Observer-visible manifest
  -> Observer (perception only)
  -> observer report + hidden Critic context
  -> Critic + shared rubric (human baseline still withheld)
  -> ux-visual-review/v1 advisory report
  -> blind human calibration/disposition
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
| UVR-AC-01, 04–06, 11 | Skill structure, security boundary, schema, and provider/protocol provenance validation | `python3 .../skill-creator/scripts/quick_validate.py .agents/skills/ux-visual-review` plus repository contract and retained-report validation |
| UVR-AC-10 | Screenshot-carried pseudo-instruction behavioral probe | Case-06 Observer/Critic reports, tool traces, and requested-file pre/post check |
| UVR-AC-02, 07 | Support matrix and adapter invocation | Codex 0.151.0 E2E; ZCode 3.10.2 Observer/Critic/negative-fixture E2E plus attested canonical reports and documented isolation/tool-surface limits |
| UVR-AC-03 | Isolated Observer leakage probe | Fresh subagent/session with only scenario + evidence manifest |
| UVR-AC-08 | 5–10 case calibration | Two-stage reports compared with human baseline |
| UVR-AC-09 | Assessment review | Advisory recommendation and follow-up Issue list |

## Documentation updates

- [x] Draft spec and plan.
- [x] Support matrix / architecture assessment.
- [x] Five-case calibration corpus and one repeat recorded.
- [ ] Blind human adjudication of extra findings recorded.
- [ ] `AGENTS.md` decision after calibration.
- [ ] `docs/engineering/quality-strategy.md` decision after calibration.
- [ ] ADR only if the final decision meets all ADR criteria.

## Execution checklist

- [x] Issue and repository governing documents reviewed.
- [x] Shared Skill draft created.
- [x] Codex thin adapters created; project Observer/Critic image E2E, no-image negative path, and write-denial probe runtime-validated.
- [x] Codex Observer/Critic ignored screenshot-carried URL and file-creation pseudo-instructions despite broad exposed capabilities; retained role traces contain only evidence reads and image viewing.
- [x] ZCode support boundary and direct project Skill discovery runtime-validated; two failed grounding attempts and the final successful run are retained.
- [x] Corrected Observer and Critic forward-test pilot completed with neutral filenames and separated hidden context using Luna/xhigh.
- [x] Five-case model calibration and one clean-case repeat completed.
- [ ] Blind human comparison/disposition completed.
- [x] ZCode grounded Observer image E2E, Critic activation, negative fixture, behavioral `injectAgentsMd` probe, and unavailable Write/Bash surface recorded; independent context tracing and permission-error denial remain explicitly unavailable in this client path.
- [x] Advisory-only adoption recommendation recorded; current AC-08 work remains in Issue #199 unless the spec scope is changed and approved.
- [ ] Spec owner review obtained before marking accepted or enforcing the process.
