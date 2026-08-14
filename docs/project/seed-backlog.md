# Seed GitHub Backlog

> Status: Tracking
> Updated: 2026-08-14
> Source of truth: scope、成果物、依存関係、状態は各GitHub Issue。本書は起票済みIssueへのnavigationと、未起票提案だけを管理する。

## Live Issue navigation

状態や依存関係は各Issueを正本とする。以下は詳細へ移動するための索引であり、関係性を本書には複製しない。

- Foundation research / decision (closed): [#2 Deck audit](https://github.com/nunu1733/NunuLauncher/issues/2)、[#3 target/preservation](https://github.com/nunu1733/NunuLauncher/issues/3)、[#4 trigger/recovery UX](https://github.com/nunu1733/NunuLauncher/issues/4)、[#5 layout strategy](https://github.com/nunu1733/NunuLauncher/issues/5)、[#6 category taxonomy](https://github.com/nunu1733/NunuLauncher/issues/6)、[#23 lock persistence](https://github.com/nunu1733/NunuLauncher/issues/23)
- Foundation implementation (closed): [#10 planning interface](https://github.com/nunu1733/NunuLauncher/issues/10)、[#11 planner harness](https://github.com/nunu1733/NunuLauncher/issues/11)、[#12 planner v1](https://github.com/nunu1733/NunuLauncher/issues/12)、[#13 safe-application spec](https://github.com/nunu1733/NunuLauncher/issues/13)、[#14 application/recovery](https://github.com/nunu1733/NunuLauncher/issues/14)、[#46 planner stress coverage](https://github.com/nunu1733/NunuLauncher/issues/46)
- Repository maintenance (closed): [#8 fork CI](https://github.com/nunu1733/NunuLauncher/issues/8)、[#9 emulator baseline](https://github.com/nunu1733/NunuLauncher/issues/9)、[#17 documentation sync](https://github.com/nunu1733/NunuLauncher/issues/17)、[#41 organizer CI gates](https://github.com/nunu1733/NunuLauncher/issues/41)、[#43 high-risk evidence policy](https://github.com/nunu1733/NunuLauncher/issues/43)、[#44 shared-writer audit](https://github.com/nunu1733/NunuLauncher/issues/44)
- Open Foundation work: [#24 empty-folder policy](https://github.com/nunu1733/NunuLauncher/issues/24)、[#15 performance budget](https://github.com/nunu1733/NunuLauncher/issues/15)、[#16 diagnostics](https://github.com/nunu1733/NunuLauncher/issues/16)、[#38 lock authoring and unknown-state review](https://github.com/nunu1733/NunuLauncher/issues/38)、[#58 raw-file restore serialization](https://github.com/nunu1733/NunuLauncher/issues/58)、[#59 grid-migration failure preservation](https://github.com/nunu1733/NunuLauncher/issues/59)、[#60 executor and shared-writer audit follow-ups](https://github.com/nunu1733/NunuLauncher/issues/60)
- MVP track: [#54 incremental provenance research](https://github.com/nunu1733/NunuLauncher/issues/54)、[#56 Deck retirement decision](https://github.com/nunu1733/NunuLauncher/issues/56)、[#57 Deck runtime removal](https://github.com/nunu1733/NunuLauncher/issues/57)、[#52 manual full-organization vertical slice](https://github.com/nunu1733/NunuLauncher/issues/52)、[#53 onboarding organization proposal](https://github.com/nunu1733/NunuLauncher/issues/53)、[#55 convergent incremental placement](https://github.com/nunu1733/NunuLauncher/issues/55)

Foundationの成果は完成UIではなく、安全に検証可能なplanning/application seamである。

## Proposed work after Foundation decisions

次は未起票の提案であり、依存欄はこの表内のproposal orderを示す。先行researchでscopeが変わり得るため、現時点ではGitHub Issueとして確定しない。

| Proposal order | Title | Type | Depends on proposal / live Issue |
|---|---|---|---|
| 1 | Select and version the organization rule format | research | closed #5, #6, #12 |
| 2 | Implement rule import and export | feature | proposal 1 |
| 3 | Evaluate optional usage-frequency signals | research | closed #12; live #52 |
| 4 | Threat-model optional external classification | research | closed #6 |

かつて本書にあったMVP提案 (locked-placement persistence/UX、manual full-organization vertical slice、onboarding organization proposal、convergent incremental placement) は、それぞれ live [#38](https://github.com/nunu1733/NunuLauncher/issues/38)、[#52](https://github.com/nunu1733/NunuLauncher/issues/52)、[#53](https://github.com/nunu1733/NunuLauncher/issues/53)、[#55](https://github.com/nunu1733/NunuLauncher/issues/55) として起票済みである。[ADR-0002](../adr/0002-replace-deck-layout.md) のDeck退役gateも [#56](https://github.com/nunu1733/NunuLauncher/issues/56) / [#57](https://github.com/nunu1733/NunuLauncher/issues/57) として起票済みである。

外部LLMとrule import/exportは、MVPのlayout safetyを遅らせない独立trackにする。
