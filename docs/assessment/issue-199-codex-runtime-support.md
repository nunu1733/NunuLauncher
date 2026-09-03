# Assessment: Issue #199 — Codex Skill and custom-agent support

> Status: research complete; fresh-session negative-path adapter test passed
> Verification date: 2026-09-03
> Local runtime: `codex-cli 0.151.0`
> Scope: repository Skill discovery, project-scoped custom agents, model and sandbox responsibility, and Observer isolation limits.

## Outcome

Codex officially discovers repository-owned skills from `.agents/skills` and project-scoped custom agents from `.codex/agents/*.toml`. The shared `ux-visual-review` contract can therefore be consumed without duplication by two thin, read-only custom-agent adapters. The adapter can omit `model` so the runtime selects a vision-capable model, but model capability must be checked at invocation time. Current public documentation does not define an adapter field that prevents all parent/repository context from reaching a subagent, so complete Observer isolation cannot be claimed from TOML alone.

## Support matrix

| Surface | Current first-party support | Issue #199 consequence | Evidence |
|---|---|---|---|
| Agent Skill format | A Skill is a directory with required `SKILL.md` and optional `scripts/`, `references/`, `assets/`, and `agents/openai.yaml`; `name` and `description` are required. | Evidence policy, rubric, role contracts, and report schema can live in one portable directory. | [OpenAI Docs — Build skills](https://learn.chatgpt.com/docs/build-skills), verified 2026-09-03 |
| Repository discovery | Codex scans `.agents/skills` from the working directory up to repository root and supports symlinked skill folders. | `.agents/skills/ux-visual-review` is a directly discoverable repository source of truth. | [OpenAI Docs — Build skills](https://learn.chatgpt.com/docs/build-skills), verified 2026-09-03 |
| Project custom agents | Standalone project agents are loaded from `.codex/agents/`; each requires `name`, `description`, and `developer_instructions`. | `ux_observer` and `ux_critic` can be thin runtime adapters instead of duplicated prompts. | [OpenAI Docs — Subagents](https://learn.chatgpt.com/docs/agent-configuration/subagents), verified 2026-09-03 |
| Read-only execution | A custom agent may set normal session configuration including `sandbox_mode`; subagents otherwise inherit parent sandbox and live runtime overrides. | Both adapters set `sandbox_mode = "read-only"`, while invocation evidence must record the effective parent permission mode. | [OpenAI Docs — Subagents](https://learn.chatgpt.com/docs/agent-configuration/subagents), verified 2026-09-03 |
| Model selection | Custom agents may set `model` and `model_reasoning_effort`; when omitted, values resolve from explicit spawn, `[agents]` defaults, then parent settings. | The repository adapter does not pin a model. The orchestrator must select and verify a vision-capable model for each review. | [OpenAI Docs — Subagents](https://learn.chatgpt.com/docs/agent-configuration/subagents), verified 2026-09-03 |
| Context isolation | Public custom-agent schema documents model, reasoning, sandbox, MCP, and skills configuration, but no `injectAgentsMd` or parent-history suppression field. Custom agents are configuration layers for spawned sessions. | TOML can prohibit code/spec inspection but cannot alone prove a blind Observer. Use a fresh/no-history spawn when available and report inherited-context limitations. | [OpenAI Docs — Subagents](https://learn.chatgpt.com/docs/agent-configuration/subagents), verified 2026-09-03 |
| Format stability | OpenAI documents custom agents as configuration layers and notes that the format may evolve as authoring/sharing mature. | Keep adapters short and treat the shared Skill as authoritative. Revalidate after material Codex upgrades. | [OpenAI Docs — Subagents](https://learn.chatgpt.com/docs/agent-configuration/subagents), verified 2026-09-03 |

## Adapter boundary

The committed Codex adapters may own only:

- the role name and discovery description;
- `sandbox_mode = "read-only"`;
- the instruction to load the repository Skill and the role-specific references;
- prohibitions on inspecting implementation materials or editing files;
- the requirement to disclose inherited-context limitations.

They must not own rubric categories, severity/confidence definitions, evidence rules, or report fields. Those remain in `.agents/skills/ux-visual-review`.

The adapters deliberately omit `model` and `model_reasoning_effort`. This avoids a repository-wide vendor/version pin, but it means invocation is valid only when the orchestrator selects a model that actually accepts the supplied images. An image attachment being absent or rejected must produce `insufficient-evidence`, not a clean report.

## Isolation limitation

The public Codex custom-agent schema does not establish a complete blind-review boundary. The adapter's `developer_instructions` can prohibit repository inspection, and a caller can supply only a minimal scenario and named evidence, but the adapter itself cannot prove that parent history or repository instructions were excluded. Calibration must therefore record one of these modes:

- `isolated`: the actual spawn mechanism created a fresh/no-history context and supplied only the allowed inputs;
- `limited`: other context may have been inherited, with the specific limitation stated.

This limitation is narrower than ZCode's documented `injectAgentsMd: false` control and prevents claiming cross-runtime equivalence in isolation strength. The shared report schema preserves the distinction.

## Fresh-session negative-path validation

On 2026-09-03, an ephemeral Codex 0.151.0 session was started with a read-only sandbox and an explicit `gpt-5.6-luna` / `xhigh` runtime selection. The parent was instructed to spawn the project agent named `ux_observer` with only this scenario: “A user wants to review a proposed home-layout change before applying it.” No screenshots or evidence manifest were supplied.

Observed result:

- Codex discovered `.codex/agents/ux-observer.toml` and spawned the configured role.
- The Observer returned `schema: ux-visual-review/observer-v1` and `status: insufficient-evidence`.
- `missing_evidence` named an ordered manifest, screenshots, and the required entry/decision/response/terminal states.
- The Observer disclosed `isolation.mode: limited`: repository context was technically available, but it reported that it was not inspected or used.
- No product code was read or edited by the Observer. The parent read the Skill and adapter configuration to resolve and launch the role.

This validates fresh-session discovery, role launch, shared-contract behavior on the no-image path, and write-free execution. It does not yet prove image delivery through the custom-agent adapter or complete context isolation. The separate five-frame calibration pilot validates image reasoning through the collaboration harness, not this exact project-agent path.

## Prototype status and next evidence

- `.codex/agents/ux-observer.toml` and `ux-critic.toml` pass static TOML checks as thin, read-only adapters.
- Fresh-session `ux_observer` discovery and the no-image `insufficient-evidence` path are **runtime-validated**.
- `ux_critic` discovery, image receipt through the project-agent path, and enforcement of write denial under an attempted mutation remain unvalidated.
- Codex support is therefore **runtime-validated for Observer discovery and the negative evidence path**, not end-to-end validated.

## Sources

- [OpenAI Docs — Build skills](https://learn.chatgpt.com/docs/build-skills), retrieved 2026-09-03.
- [OpenAI Docs — Subagents](https://learn.chatgpt.com/docs/agent-configuration/subagents), retrieved 2026-09-03.
