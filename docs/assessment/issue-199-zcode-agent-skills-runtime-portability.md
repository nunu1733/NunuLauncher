# Assessment: Issue #199 — ZCode / Agent Skills runtime portability

> Status: research complete for the ZCode and Agent Skills portability surface
> Verification date: 2026-09-03
> Scope: ZCode custom subagents, plugins, skills, workspace-context isolation,
> vision-model responsibility, and the Agent Skills shared-contract boundary.
> Out of scope: Codex/OpenAI runtime details, UX rubric design, calibration, and
> production adapter implementation.
> Evidence targets: Agent Skills official source commit
> [`69ef37e9424c0a7ea9dd2293b559e43ec8176379`](https://github.com/agentskills/agentskills/commit/69ef37e9424c0a7ea9dd2293b559e43ec8176379)
> and ZCode official plugin source commit
> [`9fd7d864501ce73a8dfd963b1f810d398e9ffbbc`](https://github.com/zai-org/zcode-plugins/commit/9fd7d864501ce73a8dfd963b1f810d398e9ffbbc).
> ZCode product documentation is published as live documentation and does not
> expose a documentation commit; every ZCode documentation claim below records
> the retrieval date explicitly.

## Outcome in one line

The portable source of truth can be an Agent Skills-compatible
`.agents/skills/ux-visual-review/` directory, but neither the Agent Skills
format nor a ZCode plugin guarantees a vision-capable execution model. The
ZCode adapter must therefore own runtime isolation (`injectAgentsMd: false`),
read-only tool policy, explicit skill activation, and selection/validation of
an image-capable model/provider/protocol combination. A repository-shipped
ZCode plugin is the documented team-distribution mechanism, but a real-client
prototype is still required to prove that its subagent can load the repository
skill without copying the contract.

## 1. Support matrix

| Surface | Current first-party support | Consequence for Issue #199 | Evidence |
|---|---|---|---|
| Agent Skills format | A skill is a directory containing required `SKILL.md`; `name` and `description` are required, while `references/`, `scripts/`, and `assets/` are optional. Relative references resolve from the skill root. | The rubric, evidence policy, Observer/Critic role contract, and report schema can live under one portable skill directory without runtime-specific model IDs. | [Agent Skills specification at target commit](https://github.com/agentskills/agentskills/blob/69ef37e9424c0a7ea9dd2293b559e43ec8176379/docs/specification.mdx) (confirmed 2026-09-03) |
| Portable discovery path | The format specification does not mandate an install path. The official client-integration guide recommends project/user `.agents/skills/` as a cross-client convention, alongside client-native locations. | `.agents/skills/ux-visual-review/` is a sound repository convention, but discovery is a client responsibility rather than a guarantee of the file format. | [Agent Skills client implementation guide at target commit](https://github.com/agentskills/agentskills/blob/69ef37e9424c0a7ea9dd2293b559e43ec8176379/docs/client-implementation/adding-skills-support.mdx) (confirmed 2026-09-03) |
| Progressive disclosure | Compatible clients disclose `name` + `description`, load the full `SKILL.md` on activation, then load referenced resources as needed. Subagent delegation is an optional advanced client feature, not part of the core file format. | Keep the common entry point short and link focused references directly; do not encode the Observer/Critic runtime topology as if the standard required it. | [Agent Skills client implementation guide at target commit](https://github.com/agentskills/agentskills/blob/69ef37e9424c0a7ea9dd2293b559e43ec8176379/docs/client-implementation/adding-skills-support.mdx) (confirmed 2026-09-03) |
| ZCode skills | A ZCode skill is a `SKILL.md` directory. Enabled-skill metadata is injected each turn and bodies load on invocation. ZCode can import external-agent skills by symlink or copy to Global or Project scope. Team distribution uses a plugin; plugin skills must use flat `skills/<name>/SKILL.md` layout. | The common skill can be reused locally through ZCode's documented symlink import. A team-installable form should be a plugin, subject to the no-copy validation in section 6. | [ZCode Skill](https://zcode.z.ai/en/docs/skill) (confirmed 2026-09-03) |
| ZCode custom subagents | Custom subagents are Beta and currently user-level only. Settings writes `~/.zcode/agents/<name>.md`; workspace/project custom-agent editing is not available in Settings. The definition supports model, thought level, tools, max turns, `injectAgentsMd`, and MCP requirements. | A user-level file is unsuitable as the repository's authoritative contract. Use it only for local validation, or ship a plugin-provided subagent as the repository adapter. | [ZCode Subagents](https://zcode.z.ai/en/docs/subagents) (confirmed 2026-09-03) |
| ZCode plugin distribution | A plugin can bundle `skills/`, `agents/`, commands, hooks, and MCP declarations under `.zcode-plugin/plugin.json`; plugin agents are `agents/*.md` and their Markdown body is the system prompt. GitHub, Git, and local marketplace sources are supported. | A thin Observer/Critic adapter plus the shared skill is distributable as one plugin. Plugin enablement is runtime trust and is not equivalent to a harmless prompt-only install. | [ZCode Plugin](https://zcode.z.ai/en/docs/plugin), [official plugin tutorial at target commit](https://github.com/zai-org/zcode-plugins/blob/9fd7d864501ce73a8dfd963b1f810d398e9ffbbc/docs/PLUGIN_DEVELOPMENT.md) (confirmed 2026-09-03) |
| `AGENTS.md` isolation | Since ZCode v3.7.1, subagents receive user and workspace `AGENTS.md` by default. `injectAgentsMd: false` opts a custom subagent out; built-in Explore does not inject them. | Observer isolation from implementation expectations is expressible in a ZCode custom/plugin subagent and should be an explicit frontmatter invariant. | [ZCode Subagents](https://zcode.z.ai/en/docs/subagents) (confirmed 2026-09-03) |
| Other workspace context | The primary agent reads only `~/.zcode/AGENTS.md` and the current workspace-root `AGENTS.md`; it does not scan nested instruction files or expand includes. Project memory applies only to the main conversation; subagents neither read nor write it. Attachments and `@` file/folder or `#` conversation references are explicit context inputs. | `injectAgentsMd: false` removes the documented automatic instruction files, and subagent memory does not add hidden project facts. The invocation contract must still restrict what the primary agent passes as the subagent task and attachments. | [ZCode Agent](https://zcode.z.ai/en/docs/agents) (confirmed 2026-09-03) |
| Vision model selection | A subagent can inherit the primary model or pin a specific model ID. ZCode classifies image support from the provider/model configuration, model catalog, built-in rules, and API protocol as Supported, Unsupported, or Unknown. It does not document automatic fallback to a vision model. | The adapter/runtime configuration, not the common Skill, must select or require a verified image-capable model. `model: inherit` alone cannot meet that invariant. | [ZCode Subagents](https://zcode.z.ai/en/docs/subagents), [ZCode model configuration](https://zcode.z.ai/en/docs/configuration) (confirmed 2026-09-03) |

## 2. Agent Skills compatibility boundary

The strict portable subset for this work is:

- one directory whose name matches a lowercase-hyphenated `name` of at most 64
  characters;
- a `SKILL.md` with non-empty `name` and `description` (description at most
  1024 characters);
- Markdown instructions in the body; and
- focused resources under `references/`, linked with relative paths from the
  skill root.

These constraints and the recommendation to keep the entry file below 500
lines come from the [Agent Skills specification at the recorded commit](https://github.com/agentskills/agentskills/blob/69ef37e9424c0a7ea9dd2293b559e43ec8176379/docs/specification.mdx)
(confirmed 2026-09-03).

The standard's optional `compatibility` field may describe environment
requirements, but it is descriptive rather than a portable capability gate.
The standard's `allowed-tools` field is explicitly experimental and support may
vary by implementation. Moreover, ZCode's documented plugin-skill allowlist is
`name`, `description`, `when_to_use`, `license`, and `metadata`; it says other
fields are ignored. Therefore neither `compatibility: vision-capable model` nor
`allowed-tools` can be the only enforcement mechanism for this workflow.
Sources: [Agent Skills specification](https://github.com/agentskills/agentskills/blob/69ef37e9424c0a7ea9dd2293b559e43ec8176379/docs/specification.mdx)
and [ZCode Plugin, Skill field reference](https://zcode.z.ai/en/docs/plugin)
(both confirmed 2026-09-03).

The standard also deliberately leaves install locations and invocation syntax
to clients. `.agents/skills/` is the official integration guide's
cross-client convention, not a normative part of `SKILL.md`. ZCode's `$skill`
syntax, plugin marketplace, model choice, context injection, tool allowlist,
and subagent files must remain adapter concerns. Source:
[Agent Skills client implementation guide](https://github.com/agentskills/agentskills/blob/69ef37e9424c0a7ea9dd2293b559e43ec8176379/docs/client-implementation/adding-skills-support.mdx)
(confirmed 2026-09-03).

## 3. ZCode subagent and context isolation

A ZCode subagent definition is Markdown whose body is its system prompt and
whose camelCase frontmatter may include:

```yaml
---
name: ux-observer
description: Observe first-impression UX from supplied visual evidence.
model: <verified-image-capable-model-id>
tools: <minimal-read-only-tools-plus-skill-activation>
injectAgentsMd: false
maxTurns: <positive-integer>
---
```

The supported keys and case sensitivity are documented by
[ZCode Subagents](https://zcode.z.ai/en/docs/subagents) (confirmed 2026-09-03).
This is illustrative, not a validated adapter: the exact model ID and exact
skill-activation tool name must be taken from the client used for validation.

For the Observer, `injectAgentsMd: false` is required. Otherwise, on v3.7.1 and
later, ZCode injects both user-level and workspace `AGENTS.md` by default. The
built-in Explore role is read-only and skips these files, but its prompt cannot
be edited and it cannot be deleted or disabled, so it cannot carry the custom
Observer role contract. Source: [ZCode Subagents](https://zcode.z.ai/en/docs/subagents)
(confirmed 2026-09-03).

This switch establishes the documented automatic-instruction isolation, not a
complete information-flow proof. The primary agent still chooses the task text
sent to the isolated context. Therefore the common contract and adapter must
limit Observer input to the user scenario plus selected screenshots/sequence,
and must prohibit forwarding Issue acceptance criteria, spec/plan, source code,
or the developer's intended visual hierarchy. The need for this invocation
rule is an inference from ZCode's model that a primary Agent launches a
subagent with an isolated task and receives its summary; the product docs do
not expose a separate denylist for individual attachments or prompt fields.
Source: [ZCode Subagents](https://zcode.z.ai/en/docs/subagents)
(confirmed 2026-09-03).

ZCode project memory does not weaken this boundary because the docs state that
subagents neither read nor write it. The primary Agent's own `AGENTS.md`
injection has no documented opt-out, and it reads only the global and workspace
root files. Source: [ZCode Agent](https://zcode.z.ai/en/docs/agents)
(confirmed 2026-09-03).

## 4. Vision-capable model responsibility

ZCode exposes two model choices for a subagent: omit `model`/use `inherit` to
follow the primary Agent's current model, or record a specific model ID. A
specific model setting takes effect only for new sessions after the definition
changes; inherited subagents follow later primary-model switches. Source:
[ZCode Subagents](https://zcode.z.ai/en/docs/subagents)
(confirmed 2026-09-03).

Image support is not determined by model ID alone. ZCode uses the provider and
model configuration, capability catalog, built-in rules, and active API
protocol. `Unsupported` strips image data and substitutes a text notice;
`Unknown` lets the provider decide and may fail. The same nominal model can
produce a different result through different providers/protocols. Coding Plan
GLM-5.3-Flash is the documented built-in multimodal model for screenshot and
image analysis. For custom/third-party models, the operator must verify the
canonical model ID and endpoint and declare the capability in model
configuration. Source: [ZCode model configuration](https://zcode.z.ai/en/docs/configuration)
(confirmed 2026-09-03).

Consequently, responsibility is split as follows (inference from the two
first-party contracts above, confirmed 2026-09-03):

1. The shared Skill states the semantic requirement (`vision-capable model`)
   and returns insufficient evidence rather than a pass when images are absent.
2. The ZCode adapter author/operator selects a specific verified model, or
   validates the inherited selection before review.
3. ZCode runtime performs its Supported/Unsupported/Unknown capability routing.
4. For Unknown, the provider is the final accept/reject authority; this is not
   a usable success guarantee for automated review.

There is no first-party evidence that ZCode automatically changes an inherited
text-only model to a vision model. Issue #199 should treat `model: inherit` as
conditionally supported, not as proof of the Vision precondition. Sources:
[ZCode Subagents](https://zcode.z.ai/en/docs/subagents) and
[ZCode model configuration](https://zcode.z.ai/en/docs/configuration)
(both confirmed 2026-09-03).

## 5. ZCode distribution and common-source options

### Option A: ZCode import with Symlink (documented, local setup)

ZCode's Import Skills UI can scan external agents, create a symlink instead of
a copy, and target the current Project. This preserves a single on-disk Skill
source and tracks later edits, provided the source path remains available.
Source: [ZCode Skill](https://zcode.z.ai/en/docs/skill)
(confirmed 2026-09-03).

This is the strongest documented no-duplication route for local calibration,
but it is a per-user import action and does not distribute custom subagents.

### Option B: Repository plugin (documented distribution, link still unproved)

ZCode's supported team distribution is a plugin marketplace. A plugin may
declare skill and agent component directories as directory paths or arrays;
its standard layout places skills under `skills/<name>/SKILL.md` and subagents
under `agents/*.md`. Sources: [ZCode Plugin](https://zcode.z.ai/en/docs/plugin)
and [official plugin tutorial at the recorded commit](https://github.com/zai-org/zcode-plugins/blob/9fd7d864501ce73a8dfd963b1f810d398e9ffbbc/docs/PLUGIN_DEVELOPMENT.md)
(both confirmed 2026-09-03).

The docs do not explicitly prove that a plugin component path can point at the
repository's sibling `.agents/skills/` directory, nor that a symlink inside a
Git-backed marketplace package is preserved and followed. They also do not
define a subagent frontmatter key that automatically mounts a named skill.
ZCode does document that a subagent with a custom `tools` allowlist cannot use
skills unless the skill tool is present, but it does not publish the exact tool
identifier on the Skill page. Source: [ZCode Skill](https://zcode.z.ai/en/docs/skill)
(confirmed 2026-09-03).

Accordingly, the plugin path is the preferred adapter distribution mechanism,
but no-copy reuse is **not yet validated**. Do not commit duplicate role logic
to `agents/*.md`; the prototype must prove one of these arrangements in a real
client:

1. the plugin manifest maps its `skills` component directly to the repository
   `.agents/skills/` directory; or
2. the plugin provides only subagent adapters while the shared Skill is
   imported to Project scope by symlink, and the adapter invokes it by name.

If neither works, move the canonical Skill directory inside the plugin and
make other runtimes discover or link that same directory; do not maintain two
independent `SKILL.md` copies.

## 6. Required ZCode prototype evidence

Before declaring the ZCode adapter supported, record all of the following on a
specific ZCode version:

1. Install/enable the local plugin and show that its Observer and Critic appear
   as plugin subagents.
2. Invoke Observer with `injectAgentsMd: false` and demonstrate that neither
   repository nor user `AGENTS.md` content appears in its input/output behavior.
3. Demonstrate that Observer can activate the exact repository-owned
   `ux-visual-review` Skill and read each referenced file without a duplicate.
4. Use a screenshot fixture to show the selected model is classified Supported
   and receives the image. Repeat with Unsupported or a negative fixture to
   prove the workflow returns insufficient evidence instead of a false pass.
5. Verify that the subagent's tool set cannot edit files or execute arbitrary
   shell commands while retaining the skill activation and evidence-reading
   capabilities it needs.
6. Restart/new-session after changing subagent model or frontmatter; ZCode docs
   say running sessions do not hot-reload those changes.

The runtime behavior behind items 1, 5, and 6 is documented by
[ZCode Subagents](https://zcode.z.ai/en/docs/subagents), plugin loading by
[ZCode Plugin](https://zcode.z.ai/en/docs/plugin), and image routing by
[ZCode model configuration](https://zcode.z.ai/en/docs/configuration)
(all confirmed 2026-09-03). Items 2–4 are Issue #199-specific validation needed
to close gaps that the public docs do not prove.

## 7. Open questions and residual risks

- **Custom image capability schema:** ZCode tells operators to declare image
  capability for custom models but the public documentation examined does not
  give the exact JSON key/schema. Resolve through a first-party schema/API or
  real-client settings export before automating custom-provider setup.
  Source: [ZCode model configuration](https://zcode.z.ai/en/docs/configuration)
  (confirmed 2026-09-03).
- **Project skill physical path:** ZCode documents Global/Project import targets
  but not the resulting manual filesystem path or supported hand-authored
  project path on the Skill page. Treat UI import as the supported entry until
  verified in the target client. Source: [ZCode Skill](https://zcode.z.ai/en/docs/skill)
  (confirmed 2026-09-03).
- **Plugin-to-shared-skill link:** directory-path plugin declarations exist,
  but sibling-path and symlink semantics are not documented. This blocks a
  claim that repository plugin installation alone already satisfies the
  single-source acceptance criterion. Sources: [ZCode Plugin](https://zcode.z.ai/en/docs/plugin)
  and [official plugin tutorial](https://github.com/zai-org/zcode-plugins/blob/9fd7d864501ce73a8dfd963b1f810d398e9ffbbc/docs/PLUGIN_DEVELOPMENT.md)
  (both confirmed 2026-09-03).
- **Beta stability:** custom subagents are explicitly Beta and user-level scope
  may change. Pin the validated ZCode version in calibration evidence and keep
  the adapter thin. Source: [ZCode Subagents](https://zcode.z.ai/en/docs/subagents)
  (confirmed 2026-09-03).
- **Plugin trust:** enabled third-party plugins may execute local processes and
  read inherited Agent environment variables. Keep this adapter prompt-only
  unless executable components become necessary, and review any future hooks
  or scripts as code-execution changes. Source: [ZCode Plugin](https://zcode.z.ai/en/docs/plugin)
  (confirmed 2026-09-03).

## 8. Recommendation for Issue #199

Proceed with `.agents/skills/ux-visual-review/` as the runtime-neutral contract
and keep model names, `injectAgentsMd`, tool names, installation paths, and
subagent frontmatter out of it. For ZCode, prototype a plugin-provided
`ux-observer` and `ux-critic`; both must activate the common skill explicitly,
the Observer must set `injectAgentsMd: false`, and both must use read-only tool
sets. Pin a verified Supported image model for calibration rather than relying
on `inherit`. Promote the adapter from experimental only after the six pieces
of evidence in section 6 are recorded; until then, the support level is
**design-supported / runtime-unvalidated**.
