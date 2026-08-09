# NunuLauncher System Design

> Status: Proposed
> Updated: 2026-08-09
> Scope: 目標設計。baselineは `v15.0.0-beta3.0` のcommit `505dbc40e6154c05158b5d0271c45f6a885a411b` に固定済み。正確なsource pathとplatform seamはDeck layoutの評価と関連decisionで確定する。

## 1. Design goals

- ユーザーのホームレイアウトを失わないことを、配置品質より優先する。
- 同じsnapshotと整理ルールから同じplanを生成する。
- 計算とDB更新を分離し、Android frameworkなしで主要な振る舞いを検証できるようにする。
- Lawnchair/Launcher3への変更点を少数のseamへ局所化する。
- 通常runはオフラインで完結し、分類理由と配置理由を説明できるようにする。

## 2. Current facts and assumptions

### Confirmed against Lawnchair 15 Beta 3 source

- 2026-08-09時点で `15-beta` とtag `v15.0.0-beta3.0` はcommit `505dbc40e6154c05158b5d0271c45f6a885a411b` を指す。
- `app.lawnchair.deck.LawndeckManager` と `AddFoldersWithItemsTask` が既に存在し、全アプリのfolder分類、layout切替時のDB複製、新規アプリのfolder追加を行う。
- package追加は `ModelLauncherCallbacks.onPackageAdded` から `PackageUpdatedTask(OP_ADD)` へ渡る。初期案の `PackageInstalledTask` というhook pointは15系に存在しない。
- Lawnchairの手動バックアップはXMLではない。DB・設定・Protobuf metadata等を含むZIPである。
- NunuLauncherは [Lawnchairのfork](https://github.com/nunu1733/NunuLauncher) であり、`main` のproduct baselineは `v15.0.0-beta3.0` のcommit `505dbc40e6154c05158b5d0271c45f6a885a411b` である。

### Not yet confirmed

- 既存Deck layoutを置換、refactor、または段階的に拡張するか。
- 対象集合、ロックの伝播、trigger、配置戦略、rule file形式。
- 15系から16系へ追従する時期と互換方針。

未確認事項を実装で固定しない。対応するIssueは [docs/project/seed-backlog.md](./docs/project/seed-backlog.md) に定義する。

## 3. System context

```mermaid
flowchart LR
    U["User / system trigger"] --> C["Organization Coordinator"]
    C --> S["Launcher Snapshot Adapter"]
    S --> P["Organization Planning Module"]
    R["Rule Management Module"] --> P
    P --> V["Plan Validator"]
    V --> Q["Preview / Confirmation"]
    Q --> A["Layout Application Module"]
    A --> L["Launcher Model + DB"]
    A --> X["Recovery Store"]
    L --> O["Post-apply Verification"]
    O --> C
```

## 4. Modules and interfaces

### 4.1 Organization Planning module

このmoduleがプロジェクトの中心となる深いmoduleである。外部interfaceは概念上1つに保つ。

```text
plan(OrganizationInput) -> PlanningResult
```

`OrganizationInput` はlayout snapshot、device capabilities、整理ルール、分類と頻度のsignal、run modeを含む。`PlanningResult` はplan、理由、警告、適用不能理由を返す。interfaceへAndroid class、SQLite row、UI stateを露出しない。

実装の内側に次を隠す。

- category assignmentの優先順位とfallback
- locked placementと占有領域のconstraint解決
- page、region、cell、folder、Dockの割当
- overflowと未配置アイテムの処理
- 決定性を保つtie-break
- planの自己検証と説明情報生成

入力不足や制約違反を勝手に補正せず、diagnosticとして返す。計画module自体はI/Oを行わない。

### 4.2 Layout Application module

検証済みplanを現在layoutへ適用する深いmoduleである。

```text
apply(ValidatedLayoutPlan) -> ApplyResult
recover(RecoveryPointId) -> RecoveryResult
```

このmoduleの実装は、revision再確認、recovery point作成、transactional write、memory model/UI bind、適用後検証を隠す。Launcher DBはlocal-substitutable dependencyとして扱い、production adapterとtest databaseで同じinterfaceを検証する。

### 4.3 Rule Management module

version付き整理ルールの読込、validation、migration、exportを担当する。ファイル構文はこのmoduleのimplementation detailとし、計画moduleはtyped modelだけを受け取る。XML、JSONなどの選定はIssueで決める。

### 4.4 Launcher Integration

Lawnchair/Launcher3のeventとmodelをproject固有moduleへ接続するadapter群である。

- snapshot adapter: platform modelをdomain snapshotへ変換する。
- package event adapter: package追加とupdateを区別し、user/profileを保持する。
- model write adapter: validated planをLauncher model threadとDB transactionへ渡す。
- UI adapter: 手動run、onboarding、確認、結果、復旧を表示する。

15系の調査対象は、既存 `app.lawnchair.deck`、`ModelLauncherCallbacks`、`PackageUpdatedTask`、`ModelWriter`、`ModelDbController`、backup/restore実装である。既存Deck layoutと競合する二重hookは作らない。

## 5. Core invariants

planと適用結果は以下を常に満たす。

1. **Conservation**: 入力の各配置アイテムは保持、移動、明示的削除のいずれか1つに対応する。
2. **No overlap**: 同じcontainer内で占有セルが重ならない。
3. **In bounds**: placementとspanが対象device profileに収まる。
4. **Referential integrity**: folder等のcontainer参照先が存在し、循環しない。
5. **Lock preservation**: locked placementとその占有領域を変更しない。
6. **Profile isolation**: personal/work/private等のprofile identityを混同しない。
7. **Determinism**: 同じ入力からbyte-equivalentなcanonical planを得る。
8. **Idempotence**: plan適用後に同じ全体整理を行っても変更planを生成しない。
9. **Convergence**: 増分配置後の状態は、同じruleによる次回全体整理と不必要に振動しない。
10. **Atomicity**: 適用は全成功または変更なしとなる。
11. **Recoverability**: 成功した適用も、保持期間内は直前のrecovery pointへ戻せる。

## 6. Organization run

```mermaid
stateDiagram-v2
    [*] --> SnapshotCaptured
    SnapshotCaptured --> Planned
    Planned --> Rejected: invalid / impossible
    Planned --> Confirmed: user or approved policy
    Confirmed --> Stale: revision changed
    Confirmed --> Checkpointed
    Checkpointed --> Applied
    Applied --> Verified
    Applied --> Recovered: write or verification failure
    Verified --> [*]
    Rejected --> [*]
    Stale --> SnapshotCaptured
    Recovered --> [*]
```

自動triggerであってもこの状態遷移を短絡しない。全体整理を無確認で行えるか、新規アプリだけを自動適用するかは別々のpolicyとしてspecで定義する。

## 7. Data ownership and persistence

- Launcher DBが現在のホームレイアウトの正本である。
- 整理ルール、category override、recovery metadataにはownershipとmigration方針を別途定義する。
- lockを既存item tableの追加columnにする案は未決定である。全item type、backup/restore、schema migration、upstream conflictを比較する。
- planはrevisionを持つ一時artifactであり、古いsnapshotへ適用できない。
- recovery pointは一般的なexport backupと分け、アプリ内で原子的に復旧できる形式を選ぶ。

## 8. Error model

少なくとも次を区別し、ユーザー向けmessageと診断情報を持たせる。

- invalid rules
- insufficient capacity
- unsupported item or profile
- stale snapshot
- checkpoint failure
- transactional write failure
- post-apply invariant failure
- permission or signal unavailable

signalがないことは原則として失敗ではない。頻度情報がない場合のdeterministic fallbackを整理ルールに明記する。

## 9. Target source layout

基準source導入後、既存Deck layoutの調査Issueで最終決定する。新規実装が妥当な場合の論理構成は次を目安とする。

```text
lawnchair/src/app/lawnchair/organizer/
├── planning/       # pure domain model and planning implementation
├── application/    # validated plan application and recovery
├── rules/          # typed rules, validation, migration and file I/O
├── integration/    # Lawnchair/Launcher3 adapters and triggers
└── ui/             # preview, confirmation, result and recovery UI
```

package数をこの図に合わせること自体を目的にしない。interfaceを深く保ち、変更のlocalityが高まる分割だけを採用する。platform source側には最小のbridgeを置く。テスト配置は上流のconvention確認後に決める。

## 10. Verification architecture

- Planning contract tests: fixture corpusでplanとdiagnosticを検証する。
- Property tests: overlap、bounds、conservation、lock、determinism、idempotenceを多数の生成layoutで検証する。
- Application contract tests: test DBでtransaction、failure injection、rollback、stale revisionを検証する。
- Upstream integration tests: package event、model reload、backup/restore、grid migration、process restartを検証する。
- UI tests: preview/confirmation/recoveryとaccessibilityを検証する。

詳細は [docs/engineering/quality-strategy.md](./docs/engineering/quality-strategy.md) を参照する。

## 11. Open design gates

実装を開始する前に、少なくとも次をIssueで解決する。

1. 既存Deck layoutのreuse/refactor/replace
2. 対象集合と既存itemの保持規則
3. trigger、確認、recoveryのUX
4. lock対象とfolder内への伝播
5. grid非依存の配置policy v1
6. category taxonomyと分類source
7. 整理ルールのfile formatとversioning
