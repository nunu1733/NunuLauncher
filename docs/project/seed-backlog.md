# Seed GitHub Backlog

> Status: Tracking
> Updated: 2026-09-25
> Source of truth: scope、成果物、依存関係、状態は各GitHub Issue。本書は起票済みIssueへのnavigationと、未起票提案だけを管理する。

## Issue navigation

状態や依存関係は各Issueを正本とする。以下は詳細へ移動するための索引であり、状態・依存関係を本書には複製しない。

- Research / decision: [#2 Deck audit](https://github.com/nunu1733/NunuLauncher/issues/2)、[#3 target/preservation](https://github.com/nunu1733/NunuLauncher/issues/3)、[#4 trigger/recovery UX](https://github.com/nunu1733/NunuLauncher/issues/4)、[#5 layout strategy](https://github.com/nunu1733/NunuLauncher/issues/5)、[#6 category taxonomy](https://github.com/nunu1733/NunuLauncher/issues/6)、[#15 performance budget](https://github.com/nunu1733/NunuLauncher/issues/15)、[#16 diagnostics](https://github.com/nunu1733/NunuLauncher/issues/16)、[#23 lock persistence](https://github.com/nunu1733/NunuLauncher/issues/23)、[#24 empty-folder policy](https://github.com/nunu1733/NunuLauncher/issues/24)、[#54 incremental provenance](https://github.com/nunu1733/NunuLauncher/issues/54)、[#56 Deck retirement decision](https://github.com/nunu1733/NunuLauncher/issues/56)
- Planning / application: [#10 planning interface](https://github.com/nunu1733/NunuLauncher/issues/10)、[#11 planner harness](https://github.com/nunu1733/NunuLauncher/issues/11)、[#12 planner v1](https://github.com/nunu1733/NunuLauncher/issues/12)、[#13 safe-application spec](https://github.com/nunu1733/NunuLauncher/issues/13)、[#14 application/recovery](https://github.com/nunu1733/NunuLauncher/issues/14)、[#46 planner stress coverage](https://github.com/nunu1733/NunuLauncher/issues/46)
- Organizer features / upstream: [#38 lock authoring and unknown-state review](https://github.com/nunu1733/NunuLauncher/issues/38)、[#52 manual full-organization vertical slice](https://github.com/nunu1733/NunuLauncher/issues/52)、[#53 onboarding organization proposal](https://github.com/nunu1733/NunuLauncher/issues/53)、[#55 convergent incremental placement](https://github.com/nunu1733/NunuLauncher/issues/55)、[#57 Deck runtime removal](https://github.com/nunu1733/NunuLauncher/issues/57)
- Repository maintenance / quality: [#8 fork CI](https://github.com/nunu1733/NunuLauncher/issues/8)、[#9 emulator baseline](https://github.com/nunu1733/NunuLauncher/issues/9)、[#17 documentation sync](https://github.com/nunu1733/NunuLauncher/issues/17)、[#41 organizer CI gates](https://github.com/nunu1733/NunuLauncher/issues/41)、[#43 high-risk evidence policy](https://github.com/nunu1733/NunuLauncher/issues/43)、[#44 shared-writer audit](https://github.com/nunu1733/NunuLauncher/issues/44)、[#58 raw-file restore serialization](https://github.com/nunu1733/NunuLauncher/issues/58)、[#59 grid-migration failure preservation](https://github.com/nunu1733/NunuLauncher/issues/59)、[#60 executor and shared-writer audit follow-ups](https://github.com/nunu1733/NunuLauncher/issues/60)
- Organizer redesign (#356 Phase A–C): [#362 disposition/migration](https://github.com/nunu1733/NunuLauncher/issues/362)（正本は [organizer-disposition-migration.md](../product/organizer-disposition-migration.md)）、実装backlog [#365](https://github.com/nunu1733/NunuLauncher/issues/365)〜[#377](https://github.com/nunu1733/NunuLauncher/issues/377)（依存順は同文書§8）

Foundationの成果は完成UIではなく、安全に検証可能なplanning/application seamである。

## Proposed work after the refocus Now phase

次は未起票の提案であり、依存欄はこの表内のorderを示す。再焦点化方針メモ（2026-09-24承認）§12 B4により、本表の項目（RF-next）はGitHub Issueとして起票せず、Now段階（[Epic #439](https://github.com/nunu1733/NunuLauncher/issues/439)配下）の完了とベンチマーク再計測（NFR-014）を経て保守者が着手を判断したときに、1項目ずつ起票する。移管対象一覧と旧表からの吸収対応の正本は [Issue #440のRF-next移管対象一覧コメント](https://github.com/nunu1733/NunuLauncher/issues/440#issuecomment-5834317973) である。旧表の4提案（rule format、rule import/export、usage-frequency signals、external classification）はorder 13・4・14へ吸収した。

| Order | Title | Type | References |
|---|---|---|---|
| 1 | 図による変更前後のpreviewと、提案の項目単位の除外 | feature | #449、spec 194/195 |
| 2 | 上流のdragによる手動移動の取り消し | feature | #442の結論の後、#450 |
| 3 | S3のpackage→category表（flowerpot由来の版付き表） | research | ADR-0007を改める新ADR |
| 4 | 使用頻度を使う方針（よく使うアプリを1ページ目・下側へ） | feature | #441、spec 203、FR-013/D-010、ADR-0012 |
| 5 | 「新着」の整理（既存フォルダへの追加） | feature | #446、spec 12 P-04 |
| 6 | 重複の削除提案 | feature | #451、#441（B7） |
| 7 | アンインストール後の穴の扱い（opt-in） | feature | order 6 |
| 8 | カテゴリの一致するフォルダへの新規アプリ配置 | feature | #446、order 5 |
| 9 | 長期のレイアウト履歴 | feature | recovery store契約の変更 |
| 10 | Dock/ウィジェットの複数選択 | feature | #449、#441再計測 |
| 11 | Undoの永続化 | feature | #450 |
| 12 | 編集画面の編集内容の新しいホームへの載せ直し | feature | #449 |
| 13 | rule import/export（FR-012） | feature | FR-012、ADR-0007 |
| 14 | 外部分類（FR-014） | research | FR-014、D-011 |
| 15 | アプリ内AI（Managed Grounded AI、#206） | feature | FR-017凍結解除判断、D-011 |

かつて本書にあったMVP提案 (locked-placement persistence/UX、manual full-organization vertical slice、onboarding organization proposal、convergent incremental placement) は、それぞれ [#38](https://github.com/nunu1733/NunuLauncher/issues/38)、[#52](https://github.com/nunu1733/NunuLauncher/issues/52)、[#53](https://github.com/nunu1733/NunuLauncher/issues/53)、[#55](https://github.com/nunu1733/NunuLauncher/issues/55) として起票済みである。[ADR-0002](../adr/0002-replace-deck-layout.md) のDeck退役gateも [#56](https://github.com/nunu1733/NunuLauncher/issues/56) / [#57](https://github.com/nunu1733/NunuLauncher/issues/57) として起票済みである。

rule import/export（FR-012）と外部分類（FR-014）、アプリ内AI（#206）は、Now段階の成果条件に入れない独立trackとする。
