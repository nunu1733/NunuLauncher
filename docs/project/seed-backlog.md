# Seed GitHub Backlog

> Status: Tracking
> Updated: 2026-08-09
> Source of truth: scope、成果物、依存関係、状態は各GitHub Issue。本書は起票済みIssueへのnavigationと、未起票提案だけを管理する。

## Live Issue navigation

状態や依存関係は各Issueを正本とする。以下は詳細へ移動するための索引であり、関係性を本書には複製しない。

- Research / decision: [#2 Deck audit](https://github.com/nunu1733/NunuLauncher/issues/2)、[#3 target/preservation](https://github.com/nunu1733/NunuLauncher/issues/3)、[#4 trigger/recovery UX](https://github.com/nunu1733/NunuLauncher/issues/4)、[#5 layout strategy](https://github.com/nunu1733/NunuLauncher/issues/5)、[#6 category taxonomy](https://github.com/nunu1733/NunuLauncher/issues/6)
- Planning: [#10 planning interface](https://github.com/nunu1733/NunuLauncher/issues/10)、[#11 planner harness](https://github.com/nunu1733/NunuLauncher/issues/11)、[#12 planner v1](https://github.com/nunu1733/NunuLauncher/issues/12)
- Safe application / observability: [#13 safe-application spec](https://github.com/nunu1733/NunuLauncher/issues/13)、[#14 application/recovery](https://github.com/nunu1733/NunuLauncher/issues/14)、[#15 performance budget](https://github.com/nunu1733/NunuLauncher/issues/15)、[#16 diagnostics](https://github.com/nunu1733/NunuLauncher/issues/16)
- Repository maintenance: [#8 fork CI](https://github.com/nunu1733/NunuLauncher/issues/8)、[#9 emulator baseline](https://github.com/nunu1733/NunuLauncher/issues/9)、[#17 documentation sync](https://github.com/nunu1733/NunuLauncher/issues/17)

Foundationの成果は完成UIではなく、安全に検証可能なplanning/application seamである。

## Proposed work after Foundation decisions

次は未起票の提案であり、依存欄はこの表内のproposal orderを示す。先行researchでscopeが変わり得るため、現時点ではGitHub Issueとして確定しない。

| Proposal order | Title | Type | Depends on proposal / live Issue |
|---|---|---|---|
| 11 | Implement locked-placement persistence and UX | feature | live #3, #14 |
| 12 | Deliver the manual full-organization vertical slice | feature | live #12, #14; proposal 11 |
| 13 | Deliver onboarding organization proposal | feature | proposal 12 |
| 14 | Implement convergent incremental placement for new apps | feature | live #6, #12, #14 |
| 15 | Select and version the organization rule format | research | live #5, #6, #12 |
| 16 | Implement rule import and export | feature | proposal 15 |
| 17 | Evaluate optional usage-frequency signals | research | live #12; proposal 12 |
| 18 | Threat-model optional external classification | research | live #6 |

Orders 11–14をMVP候補とする。外部LLMとrule import/exportは、MVPのlayout safetyを遅らせない独立trackにする。
