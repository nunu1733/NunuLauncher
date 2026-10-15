---
status: accepted
---

# Layout Application interface相当の要求テストの実現surface（ADR-0016）

> Status: Accepted（2026-10-02。受入は本PR [#498](https://github.com/nunu1733/NunuLauncher/pull/498) のmergeで完了する。起草: #497 Phase 1 review round 3/4で確定。出典: #497の実装spec起草時に発覚した、accepted ADRの要求テスト表の層の記述と実現surfaceの不一致）
> Date: 2026-10-02
> 対応: #497
> 置換関係: [ADR-0013](./0013-direct-edit-write-contract.md) 要求テスト表と [ADR-0015](./0015-new-app-destination-policy.md) 要求テスト表における「Layout Application interface相当のJVM test（test DB使用）」層の**実現surfaceの判断を引き継ぎ、具体化する**。両ADRの要求テスト表の本文（行の内容・行の対応）は変更しない。両ADRのChange historyへ本ADRへの関連リンクを1行ずつ追加する（ADR-0005へのADR-0015の接続と同じ形式。`docs/adr/README.md` の更新規約どおり、判断の変更は元ADRを消さずsuccessor ADRが置換関係を示す）。

## Context

ADR-0013とADR-0015の要求テスト表は、書込み経路の検証行（途中失敗の注入、transaction rollback、admission後の再検証（defer後のstale検証）、Undoのfail-closed、admission後の再計画、policy snapshotの再flush一貫性、snapshot欠損・破損時のclosed result）を「Layout Application interface相当のJVM test（test DB使用）」へ割り付けた（ADR-0013要求テスト表、ADR-0015要求テスト表）。

起草時に確認した事実は次のとおり（2026-10-02、base `3d8f4dcca5452fd609a1c1e2f279231ca8edc0e4`）:

- これらの行が検証する書込み経路の実体は `ModelWriter`（`src/com/android/launcher3/model/ModelWriter.java`）であり、Android runtime（`Context`、`SQLiteDatabase`、model executor、`BgDataModel`）に依存する。repositoryのunit test環境は純粋JVM（`build.gradle:508` の `testImplementation libs.junit4` のみ）であり、RobolectricやJDBC SQLite等のhost-side test依存は存在しない。したがって「local JVMでtest DBを使って`ModelWriter`経路を実行する」harnessは現状存在せず、新設するには新規test依存とlane審査（AGENTS.mdテスト規約）を要する。
- 受入済みの実装（#448/#450、spec 448 AC-7、ADR-0013要求テスト表の実装実績）は、同じ要求行を**既存の書込み経路harness**（`AndroidJUnit4`で実行し、production DB adapterの代替としてtest DBを用いる。`tests/organizer-instrumentation/com/android/launcher3/DirectEditModelWriterTest.java`、`DirectEditWriteShapeTest.java`等と同じorganizer shared-writer lane）で満たした。
- `docs/engineering/quality-strategy.md` はorganizer JVM gateとinstrumentation（connected test）laneを別surfaceとして区別する。そのため「JVM test」という語では、実surface（`AndroidJUnit4`）と符号が合わず、実装PRのacceptance証跡がADRの語と照合できない（#497 Phase 1 review round 3で指摘）。

## Decision

1. **ADR-0013要求テスト表とADR-0015要求テスト表の「Layout Application interface相当のJVM test（test DB使用）」行のcanonical surfaceを、`AndroidJUnit4`で実行し、production DB adapterの代替としてtest DBを用いる書込み経路harness（organizer shared-writer lane）とする。** #448/#450が同じ要求行を満たすために用いた実績surfaceと同じである（spec 448 AC-7）。将来の実装PR（#497を含む）はこのsurfaceをcanonical ownerとして要求行を満たす。
2. **純粋JVM test（計画関数・分類関数等の決定意味論）は補助として残す。** canonicalへの集約を要求行の重複testで置き換えない。新規CI lane、同一scenarioの重複test、surfaceの新設は行わない。
3. coordinator排他・process死の行は、両ADRが元へ割り付けたsurface（shared-writer instrumentation seam、process-death smokeの慣行）のまま変更しない。
4. 本判断は層の実現surfaceの具体化であり、要求テスト表の行の内容（検証対象の振る舞い）を変えない。ADR-0013/ADR-0015の本文は変更しない。
5. host-side JVM harness（Robolectric等の新規test依存）が将来導入された場合、本ADRの置換関係を更新してよい（その場合は本ADRを置換する新ADRで行う）。

## Alternatives considered

### host-side JVM + test DB harness（Robolectric等）を新設して元の語をliteralに満たす

Rejected。`ModelWriter`経路をhost JVMで実行するには新規test依存（RobolectricまたはJDBC SQLite adapter）と、Launcher3の大規模classpathでのharness新設が必要であり、新規lane審査（AGENTS.mdテスト規約）を伴う過大なinfraである。受入済み実装が既に同じ要求行を満たしたsurfaceと異なる正本を新たに作ることになり、実績との整合も失われる。

### ADR-0013/ADR-0015の本文をin-placeで書き換える

Rejected（#498で一度実施し、Phase 1 review round 4で撤回）。`docs/adr/README.md` の更新規約（判断を変更するときは元ADRを消さず、新ADRから置換関係を示す）に反する。accepted時の判断と現在の判断の境界・変更理由・承認単位が履歴上失われる。

## Consequences

- #497実装PRのtest acceptanceの正本が明確になる。実装spec（#497）とplanは、要求テスト行のcanonical surfaceを本ADRへ参照して記載する。
- ADR-0013/ADR-0015の要求テスト表の本文は不変のまま、層の記述と実surfaceの照合が可能になる。
- 将来のhost-side JVM harness導入時は、本ADRを置換する新ADRで判断を更新する。

## Change history

- 2026-10-02: 起草・受入（#497 Phase 1 review round 3/4で確定。受入は [#498](https://github.com/nunu1733/NunuLauncher/pull/498) mergeで完了する）。
