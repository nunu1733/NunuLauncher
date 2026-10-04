# Seed GitHub Backlog

> Status: Proposed
> Updated: 2026-08-09
> Note: これは初期Issueを作るための設計図であり、進捗の正本ではない。Issue作成後にリンクを追記し、状態管理はGitHubへ移す。

## Recommended creation order

| Order | Proposed title | Type | Exit artifact | Depends on |
|---|---|---|---|---|
| 1 | [#1 Create the NunuLauncher fork and pin the Lawnchair baseline](https://github.com/nunu1733/NunuLauncher/issues/1) | upstream | fork URL、base commit、license確認、再現build command、初回CI | none |
| 2 | [#2 Audit Lawnchair 15 Deck layout against NunuLauncher requirements](https://github.com/nunu1733/NunuLauncher/issues/2) | research | capability/gap matrix、reuse/refactor/replace recommendation、変更候補path | 1 |
| 3 | [#3 Define the organization target set and item preservation policy](https://github.com/nunu1733/NunuLauncher/issues/3) | research | item type inventory、move/preserve/reject policy、profile identity | 1, 2 |
| 4 | [#4 Define organization triggers, confirmation, and recovery UX](https://github.com/nunu1733/NunuLauncher/issues/4) | research | manual/onboarding/install別state diagramとfailure UX | 2 |
| 5 | [#5 Specify layout strategy v1 across device profiles](https://github.com/nunu1733/NunuLauncher/issues/5) | research | page/region/cell/folder/Dock rule、tie-break、overflow examples | 3 |
| 6 | [#6 Specify category taxonomy and local classification v1](https://github.com/nunu1733/NunuLauncher/issues/6) | research | taxonomy、signal priority、override、undefined behavior、fixture set | 2, 3 |
| 7 | Define the pure organization planning interface | feature | accepted spec、domain types、planner contract、diagnostic model | 3, 5, 6 |
| 8 | Build the planner fixture and property-test harness | feature | representative fixtures、generators、invariant tests | 7 |
| 9 | Implement deterministic full-layout planning v1 | feature | passing contract/property tests、explainable plan | 7, 8 |
| 10 | Define and implement safe layout application and recovery | feature | transaction adapter、recovery point、failure injection tests | 1, 3, 4, 7 |
| 11 | Implement locked-placement persistence and UX | feature | accepted lock semantics、migration、backup/restore tests、UI | 3, 10 |
| 12 | Deliver the manual full-organization vertical slice | feature | snapshot→preview→confirm→apply→recover user flow | 9, 10, 11 |
| 13 | Deliver onboarding organization proposal | feature | opt-in onboarding flow、skip/retry behavior | 12 |
| 14 | Implement convergent incremental placement for new apps | feature | package event matrix、planner mode、focus/notification behavior | 6, 9, 10 |
| 15 | Select and version the organization rule format | research | typed model、format decision、schema、migration policy、ADR if needed | 5, 6, 9 |
| 16 | Implement rule import and export | feature | validation、SAF flow、round-trip/migration tests | 15 |
| 17 | Evaluate optional usage-frequency signals | research | permission UX、privacy review、fallback、quality measurement | 9, 12 |
| 18 | Threat-model optional external classification | research | data flow、consent、redaction、credential and prompt-injection controls | 6 |

## First milestone

Orders 1–10をFoundationとする。この段階の成果は、UI上の完成機能ではなく、上流に対して位置付けられた安全で検証可能なplanning/application seamである。

Orders 11–14をMVPとする。外部LLMとrule import/exportは、MVPのlayout safetyを遅らせない独立trackにする。

## Issue creation notes

- Orders 2–6はresearch Issueとして、timeboxより明確な問いとexit artifactを優先する。
- Order 2で既存Deck layoutを十分に深められると判明した場合、Orders 7–10のpathと名称を更新する。
- Orders 3–6が未解決のままplanner implementation IssueをReadyにしない。
- 各Issueは [docs/product/requirements.md](../product/requirements.md) の要件IDまたはdecision IDを参照する。
