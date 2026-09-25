---
issue: "#458"
status: draft
requirements:
  - AC-458-P1-01
  - AC-458-P1-02
  - AC-458-P1-03
  - AC-458-P1-04
  - AC-458-P1-05
  - AC-458-P1-06
  - AC-458-P1-07
  - AC-458-P1-08
  - AC-458-P2-01
  - AC-458-P2-02
  - AC-458-P2-03
  - AC-458-P2-04
  - AC-458-P2-05
  - AC-458-P2-06
  - AC-458-P2-07
  - AC-458-P2-08
updated: 2026-09-25
---

# 既存テスト/CI資産の semantic 再監査と、証拠によって正当化された変更の適用

## Problem

Issue #422 が impact-based CI portfolio と lane/surface routing の baseline を確立し、
Issue #456 が `test-audit` skill によって今後の test authoring/review の意味論 gate を
導入した。しかし既存の test 資産と CI-routed class は旧規則のもとで作成されており、
semantic ownership criteria（protected contract、credible regression、primary owner
boundary、重複、impact surface、CI 分類）に対する retrospective 評価が行われていない。

機械的な事実として、`tests/organizer-instrumentation/` には CI lane の class filter に
含まれない instrumentation test が存在し、それらは full portfolio であっても一切実行され
ない（#422 監査で記録済み、routing 判断は後続 Issue に deferred）。`tests/unit/` にも
`organizer-unit-tests` job の `--tests` filter が拾わない class が存在する。一方で、どの
class が standing regression でどれが one-issue 診断資料かの記録は散在しており、
routing・統合・削除の判断を下せる状態にない。

## Outcome

既存 test portfolio 全体が semantic audit され、候補ごとの disposition
（Retain / Consolidate / Move boundary / Diagnostic-local-only / Remove / Route）が
`audit.md` に記録される。証拠によって正当化された変更のみが Phase 2 で適用され、
routing 変更がある場合は `docs/engineering/ci-test-portfolio.md` と CI workflow が同じ
PR で更新され、実 GitHub Actions run で検証される。

## Scope

- Phase 1: 監査のみ。`tests/unit/**`、`tests/organizer-instrumentation/**`、CI class
  filter の実行実態、test-support seam、`surface_organizer_ui` lane 間 overlap、flaky
  分類、test-only production seam の調査と記録（`audit.md`）。
- Phase 2: Phase 1 の証拠で正当化された disposition の適用。routing / class filter /
  surface filter 変更、文書更新（同じ PR）。

## Non-goals

- #422 の redesign を新証拠なしに reopen すること。
- test が遅い・flaky・古い・Issue 番号由来であることだけを理由にした削除。
- test 数・lane 数・LOC・wall-clock の最適化そのもの。
- rerun-green を infrastructure-only の証拠として扱うこと。
- 低層が本当の failure mode を観測できない契約を unit test へ無理に移すこと。
- 監査を理由とした production behavior 変更（証明された test-only seam 削除の直接帰結を
  除く）。
- #438 の failure-capture lifecycle 作業の吸収。

## Behavior scenarios

本 spec は repository 振る舞いの変更ではなく監査プロセスの spec である。受入条件は
Issue #458 の acceptance criteria を正とし、本書では要件 ID へ対応付ける。

- AC-458-P1-01: CI-routed instrumentation class 全件に protected contract と primary
  owner boundary の記録がある（`audit.md` §2）。
- AC-458-P1-02: `tests/organizer-instrumentation/**` の全 test class が CI-routed /
  unrouted として識別されている（`audit.md` §1）。
- AC-458-P1-03: unrouted class 全件に Route / Diagnostic-local-only / Remove の明示的な
  disposition がある（`audit.md` §3）。
- AC-458-P1-04: `surface_organizer_ui` lane 間の overlap が contract level で監査されて
  いる（`audit.md` §5）。
- AC-458-P1-05: 既知 flaky/intermittent test が demotion/removal 判断の前に #422 taxonomy
  で分類されている（`audit.md` §6）。
- AC-458-P1-06: 監査で見つかった test-only production/support seam が non-test caller
  付きでインベントリ化されている（`audit.md` §7）。
- AC-458-P1-07: 監査が bounded な Phase 2 変更リストを生産し、count/runtime 削減だけを
  目的とした変更を含まない（`audit.md` §8）。
- AC-458-P1-08: 監査記録が skill の focused-audit evidence 項目を満たす。
- AC-458-P2-01: 承認された Consolidate / Move boundary / Remove / Route 変更が実装されて
  いる（本監査の bounded list では: instrumentation Route 14 class → 既存 5 lane、JVM
  Route 2 class → `organizer-unit-tests` filter、Move boundary 1 class
  （BackupExclusionTest）、fixture 修復 3 件、Remove なし。GridMigrationFailureTest は
  #461 へ分離）。
- AC-458-P2-02: 削除した test によって意味ある regression 契約が無 coverage になって
  いない。
- AC-458-P2-03: routing 変更がある場合、`docs/engineering/ci-test-portfolio.md` と
  `ci.yml`（必要なら `tools/repo-contract/ci_portfolio_map.yml`）が同じ PR で更新されて
  いる。
- AC-458-P2-04: 実 Gradle/class filter が確認されており、path mapping だけを実行証拠と
  して使っていない。
- AC-458-P2-05: repo-contract / CI portfolio validator が通過している。
- AC-458-P2-06: CI workflow routing 変更が実 GitHub Actions run で検証され、対象 lane が
  timeout headroom 内で完了している（実測比の異常増加がない）。
- AC-458-P2-07: 最終監査記録が residual risk と follow-up を識別している（`audit.md`
  §9）。
- AC-458-P2-08: Phase 2 適用前に focused local validation（`audit.md` §8.6）が実施され、
  結果（PASS/FAIL・所要時間・分類）が `audit.md` に記録されている。

## Domain language

なし（既存語彙のみ。test-audit skill の disposition 用語をそのまま使う）。

## Failure behavior

監査の結論が証拠と矛盾した場合、または review で指摘された場合、該当 disposition を
撤回して `audit.md` を先に修正する。routing 変更の適用後、対象 lane が CI 上で
失敗した場合、その class を修正するか disposition を Diagnostic-local-only へ戻すかを
PR 内で記録してから merge する。
