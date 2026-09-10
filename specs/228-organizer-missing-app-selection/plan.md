---
issue: "#228"
status: draft
spec: ./spec.md
updated: 2026-09-10
---

# Plan: ホーム未配置アプリ選択のOrganizer対象追加

> Baseline: `origin/main` = `6b6bf8dd9fa0c42399185dbb13c30192f1e15962` (2026-09-10時点)。
> 本planは spec.md (draft) に対応する。D-1〜D-3はspecのunresolved decisionsであり、実装開始前にownerが確定する必要がある。本planは (D-2 について) 比較材料を提供するが、選択を確定しない。

## 1. 現状の実装と拡張点 (baseline確認済み)

### 1.1 既に存在する拡張点 (domain / application層)

| 要素 | 場所 | 状態 |
|---|---|---|
| `TargetSet.additions: List<CandidateItem>` | `lawnchair/src/app/lawnchair/organizer/planning/OrganizationInput.kt` (L214-) | 存在。productionからは常に空 |
| `CandidateItem` (`CandidateKind.APPLICATION/DEEP_SHORTCUT`, `CandidateTarget.AppKey/ShortcutKey`, `availability`, `span`) | 同上 (L136-) | 存在。安定identityは `ComponentKey` + `ProfileId` |
| `RunMode.IncrementalPlacement` | 同上 | 存在。plannerは `placeIncrementalRun` で既存itemを全Preserveし候補のみ配置 |
| 検証 `ADDITIONS_UNDER_FULL_ORGANIZATION` | `planning/PlanningValidation.kt` L619-624 | FullOrganizationでadditions非空をreject |
| 候補検証 `checkCandidates` (grid超過 / unavailable) | `planning/PlanningValidation.kt` L625-640 | 存在 |
| typed create path: `PlannedCandidateItem` → `ApplyAction.Insert` | `application/actions/ActionMaterializer.kt` L78-83, `application/adapter/LauncherLayoutAdapter.kt` L423 | 存在。InsertのDB書込み・transaction・recoveryは既存契約に含まれる |
| launchable列挙の権限面 | `integration/AndroidClassificationSignalSnapshotSource.kt`, `ui/CategoryOverrideAuthoring.kt` (L160-166: `LauncherApps.getActivityList(null, user)`) | 参照実装あり |

### 1.2 存在しないもの (本Issueの作業対象)

1. **missing-app detection module**: installed launchable apps と snapshot のapp表現の差分を計算する純粋module。productionにもtestにも存在しない。
2. **選択UI**: `ui/ManualOrganizationRun.kt` の `State` machine (`Idle → Capturing → Planning → Preview → Applying → Applied` + typed失敗state) に選択phaseが存在しない。
3. **production composition**: `integration/OrganizationInputComposer.kt` L314 は `RunMode.FullOrganization` 固定、`FullTargetSetMaterializer` は `additions = emptyList()` 固定。
4. **preview projectionのAdd表現**: `application/public/PlanPreview.kt` の `PreviewChange` variantsは `MoveChange / PreservedChange / NewFolderChange / NewPageChange / ItemWarningChange` のみ。source placementを持たないAdd行のvariantがない。
5. **UI copy**: 選択画面のstrings (en/ja) なし。

## 2. D-2 comparison: run mode composition

| 観点 | (A) FullOrganizationがadditionsを許可 | (B) 新規run mode |
|---|---|---|
| 検証規則の変更 | `checkAdditionsUnderFull` の削除/緩和。既存のfull-organize検証表面 (property test含む) への影響を再確認する必要がある | 既存mode無変更。新modeの検証・決定性・冪等性testを新規に追加 |
| planner変更 | `placeFullRun` が既存配置計算に候補unitを参加 (allocationは `placeIncrementalRun` のfolder group / unit構成を再利用可能) | 同左を新mode配下で実装。`placeIncrementalRun` は現状「既存全Preserve」のため、#228要求 (既存も再整理) には新経路が必要 |
| 意味論の明示性 | 「全体整理 + 明示的追加」が1つのmodeになる。`RunMode` の意味境界 (full = snapshot全item対象) が変化する | mode名で「scope-composed」を明示できる。`RunMode` enum追加はinteractiveだが局所的 |
| 冪等性 | 適用後に候補がsnapshot表現になり、再実行で空差分 (自然成立) | 同左 |
| 推奨 | **(B) を推奨する** (plan時点の判断)。`RunMode.FullOrganization` の既存契約 (spec 12, spec 83, 既存property test群) を変えず、`FullOrganization` の「additionsは空」という不変条件を維持できるため。ただし最終判断はowner review | |

どちらの場合も、配置計算は「既存対象のMove/Preserve計算」と「候補の配置unit化」を1つのallocator入力へ合成する形になり、`placeIncrementalRun` の `FolderCandidate` / `IncUnit` 構成 (L114-160) が再利用候補である。この合成が既存の決定性・tie-break規則を壊さないことの検証が最大の技術リスクである。

## 3. 変更moduleと所有境界

```text
lawnchair/src/app/lawnchair/organizer/
├── integration/
│   ├── MissingAppCandidateSource.kt        # 新規: LauncherApps列挙とcaptureの差分計算
│   │                                        #   (読み取りのみ。pureな差分計算は別関数としてtest可能に)
│   ├── OrganizationInputComposer.kt        # 更新: 選択済み候補を受け取るcompositionの追加
│   └── ProductionOrganizationInputComposer.kt # 更新: 新flowのwiring
├── planning/
│   ├── PlanningValidation.kt               # 更新: D-2 (B) 採用時は新mode向けのadditions検証
│   ├── PlanningPlacement.kt                # 更新: 既存再整理 + 候補配置の合成経路
│   └── DeterministicOrganizationPlanner.kt # 更新: 新modeのdispatch
├── application/
│   └── public/PlanPreview.kt               # 更新: Add表現のprojection (additive)
├── ui/
│   ├── ManualOrganizationRun.kt            # 更新: 選択phaseのState追加
│   └── MissingAppSelectionScreen.kt        # 新規: 選択UI (multi-select / search / bulk)
└── (rules/, diagnostics/ は原則無変更)
```

所有境界:

- detectionは **integration** に置く (platform `LauncherApps` 読み取りが必要なため)。純粋差分計算 (`Set<ComponentKey+ProfileId>` ベース) はplatform型に依存しない関数として切り出し、planning側の型 (`CandidateTarget.AppKey`) を出力とする。planning moduleへplatform型を漏らさない (AGENTS.md設計規約)。
- 選択stateはUIが所有し、composition seamへは「確定済み候補集合」として渡す。UIが`LauncherApps`を直接呼ぶのは既存 `CategoryOverrideAuthoring` の前例に従うが、detection本体は再利用可能な単一sourceに置く。
- preview projectionの変更は `PlanPreview.kt` のadditive拡張とし、`PlanPreviewDetails` / `PreviewCounts` の既存shape契約 (spec 208 AC-1) を維持する。

## 4. Interface / seam 設計

```kotlin
// integration (新規)
interface MissingAppCandidateSource {
    /** 読み取りのみ。失敗はtyped resultで返し、例外を上位へ出さない */
    fun detect(snapshot: CapturedSnapshot): CandidateDetectionResult
}
sealed interface CandidateDetectionResult {
    data class Ready(val candidates: List<DetectedCandidate>) : CandidateDetectionResult
    data class Unavailable(val reason: DetectionUnavailableReason) : CandidateDetectionResult
}
data class DetectedCandidate(
    val target: CandidateTarget.AppKey,   // ComponentKey + ProfileId
    val label: String,                    // 表示用 (solution化は既存label規約に従う)
    val availability: Availability,       // AVAILABLE のみ candidates に載せる
)
```

- composition seam: `OrganizationInputComposer` に選択済み候補 (`List<CandidateTarget.AppKey>`) を渡す新規method、または既存 `composeFullOrganization` の引数拡張。`CandidateItem` への変換 (`span = 1x1`, `availability` 再確認) はcomposer内で行う。
- planner入力は既存 `OrganizationInput` のshapeのまま (`targets.additions` を埋める)。interface変更は最小。

## 5. 制御フロー (data flow)

```text
ManualOrganizationRun (start)
  → Capturing (既存 canonical capture)
  → CandidateDetection (新State: MissingAppCandidateSource.detect, zero-write)
  → Selecting (新State: 選択UI。process-local state)
      ├─ cancel → Idle (zero-write)
      └─ confirm (選択集合。空でも可)
  → composition (composer が TargetSet.additions へ変換)
  → Planning (planner。既存のpreview/reject経路)
  → Preview (inspectPlan, read-only。Add行を含む変更一覧)
      └─ confirm → Applying (既存 transaction / stale / recovery)
```

- 検出はcaptureと同じ安定したcutで行うことが望ましいが、app inventoryにはrevisionが無いため、**適用時の失敗をもってstale捕捉する** (spec §6)。layout側のstaleは既存exact preconditionが捕捉する。

## 6. 互換性・migration・rollback

- 永続dataの新規・変更なし。DB migrationなし。backup/restore影響なし。
- 既存の全体整理flow (選択なし) は無変更で動作する (compositionのdefault = additions空)。
- 万一適用後に問題が発生した場合、既存のrecovery point / `inspectRecovery` がそのまま復旧経路である。Addで作成されたitemも通常のworkspace itemとしてrecovery対象になる (`RecoveryWriteSet` の既存契約)。
- 本機能単独のfeature flagは設けない。問題発生時はrevertで対応する (永続状態を持たないためrevert可能)。

## 7. 失敗処理

| 失敗 | 挙動 |
|---|---|
| detection失敗 (profile取得不能等) | typed `Unavailable`。選択UIへ入れず既存flowへ。書込みなし |
| 候補0件 | 「追加できるアプリがない」表示。既存flow継続可 |
| planner reject (容量不足等) | 既存の `PlanningRejected(IMPOSSIBLE/INVALID)` 表示。未配置候補は `UnplacedItem` warningとして提案される既存経路に乗せる |
| 適用時のapp無効化 | fail-closed全体失敗 (部分成功しない)。再検出を促す |
| stale layout revision | 既存 `State.Stale` (recapture) |

## 8. テスト戦略

- **detection unit test** (JVM): 純粋差分計算に対し、folder内 / dock / app pair / 重複配置 / work profile分離 / disabled・suspended除外 / non-launchable構造除外 / 空 snapshot の fixture。property test (任意のsnapshot×inventoryで、候補 = inventory − 表現identity集合 が常に成立)。
- **planner unit / property test**: 選択候補を含む入力での決定性 (byte-equivalent再現)・冪等性 (適用後再計画で空差分)・conservation (既存itemは保持/移動/削除のいずれか)・未選択候補の不在。空workspace入力。
- **application契約test** (test DB): Insertを含む適用のtransaction・失敗注入・rollback・stale・適用後検証 (model snapshot突合せ)。app無効化のfail-closed。
- **projection unit test**: Add行の構築・counts整合・Move/Preserve行への非干渉 (spec 208のidentity invariant testの無変更通過)。
- **instrumentation test** (API 36 / Platform 36.1, CI job): 選択UI (multi-select / search / select all / clear all / 選択数 / cancel)・zero-write (DB比較)・空workspace混在のend-to-end・TalkBack / Switch Access / keyboard / 200% font・ja string解決。
- **device evidence**: fresh workspace (初期化後 / 別launcherからの切替) での手順実行記録。

## 9. 実装順序 (incremental)

1. detection純粋計算 + unit test (platformなしで検証可能)
2. `MissingAppCandidateSource` production実装 + composition拡張 (D-2決定後)
3. plannerの合成配置経路 + 検証 + property test
4. preview projectionのAdd表現 + counts/group拡張 (spec 195/208契約のtest無変更確認)
5. 選択UI + State machine拡張 + en/ja strings
6. application経路のfail-closed確認 (app無効化) と契約test
7. instrumentation / device evidence

各段階で既存test全通過を確認する。手順1はD-1/D-2と独立に着手できる。

## 10. リスク

- **R-1 (高)**: 合成配置が既存full-organizeの決定性・冪等性propertyを壊す可能性。対策: 手順3でproperty testを先に拡張し、既存corpusでの回帰を検証する。
- **R-2 (中)**: `PreviewCounts` truth契約 (spec 195 D2) へのAdd group追加が既存UI testに波及する。additive拡張にとどめる。
- **R-3 (中)**: 選択UIのa11y (multi-select semantics) の証拠作成コスト。既存spec 52契約のpatternを踏襲する。
- **R-4 (低)**: app inventory列挙のprofile枚挙失敗。typed Unavailableで既存 `InputUnavailable` 表示に接続する。

## 11. 未確認領域 (明示)

- `placeIncrementalRun` のfolder group構成をfull-organize合成に再利用した場合のtie-break順序が既存`GLOBAL_COMPACT_V2`等のstrategy別期待値と衝突しないかは、実装時にstrategyごとの期待値testで確認する必要がある (baseline時点では未検証)。
- `PreviewLabel` / 行構築 (`OrganizationPreviewContent`) の既存copy構造にAdd行が収まるかの詳細は、手順4での実装時確認 (spec 195の行形式契約に従う)。
- app pair memberとして表現されたappが `TargetKey.AppKey` 等価で差分計算できるかは、captureのapp pair表現 (`AppPairMetadata` / members) の実装詳細確認が必要 (baseline時点でmembersのtarget解決方法は未確認)。
- 本planは`git worktree`上のbaseline (`6b6bf8dd`) でのみ検証しており、CI上でのbuild/testは実行していない。

## 12. 関連

- spec: [./spec.md](./spec.md) (draft)
- Issue: [#228](https://github.com/nunu1733/NunuLauncher/issues/228)
- 依存: #182, #194, #195, #208 (いずれもimplemented), #203 (オプション・未依存)
