---
issue: "#228"
status: draft
spec: ./spec.md
updated: 2026-09-11
---

# Plan: ホーム未配置アプリ選択のOrganizer対象追加

> Baseline: `origin/main` = `b761839479259cb4df815151e01df93c655448a7` (2026-09-11時点)。
> 本planは spec.md (draft) に対応する。**D-1 (unchecked-by-default) とD-2 (新規scope-composed run mode) はowner決定済み** ([Issueコメント 2026-09-11](https://github.com/nunu1733/NunuLauncher/issues/228#issuecomment-5634964606))。D-3 (候補表示順) はowner判断により実装PR内で確定する。本planはD-2の比較記録を残すが、採用は (B) で確定済み。実装開始はspec accepted後。
>
> **再入場検証 (2026-09-11)**: 初版baseline `6b6bf8dd9fa0c42399185dbb13c30192f1e15962` から現baseline `b761839479` への差分を確認した。`specs/228` 参照の拡張点 (`TargetSet.additions` / `CandidateItem` / `RunMode` / `ADDITIONS_UNDER_FULL_ORGANIZATION` / `checkCandidates` / `FullTargetSetMaterializer` の `additions = emptyList()` 固定 / `PlanPreview.kt` variants / `ActionMaterializer.kt` Insert経路) は**いずれも未変更**である。organizer側の変更は Issue #271 (durable status projection: `ReadinessGate.stateFlow` / `RecoveryStore.readInspectionSnapshot` / `ManualOrganizationRun` への読み取り専用facade追加) が本plan対象外のadditive変更であり、本planの前提に影響しない。Issue #228のコメント再取得では、スナップショットコメント (2026-09-10) 以降の追記はない。

## 1. 現状の実装と拡張点 (baseline確認済み)

### 1.1 既に存在する拡張点 (domain / application層)

| 要素 | 場所 | 状態 |
|---|---|---|
| `TargetSet.additions: List<CandidateItem>` | `lawnchair/src/app/lawnchair/organizer/planning/OrganizationInput.kt` (L216) | 存在。productionからは常に空 (`integration/FullTargetSetMaterializer.kt` L63) |
| `CandidateItem` (`CandidateKind.APPLICATION/DEEP_SHORTCUT`, `CandidateTarget.AppKey/ShortcutKey`, `availability`, `span`) | 同上 (L136-) | 存在。安定identityは `ComponentKey` + `ProfileId` |
| `RunMode.IncrementalPlacement` | 同上 | 存在。plannerは `placeIncrementalRun` で既存itemを全Preserveし候補のみ配置 |
| 検証 `ADDITIONS_UNDER_FULL_ORGANIZATION` | `planning/PlanningValidation.kt` L619-624 | FullOrganizationでadditions非空をreject |
| 候補検証 `checkCandidates` (grid超過 / unavailable) | `planning/PlanningValidation.kt` L627-641 | 存在 |
| typed create pathの断片: `PlannedCandidateItem` 型と `ApplyAction.Insert` のDB書込み面 | `application/actions/ActionMaterializer.kt` L82 (型定義のみ), `application/adapter/LauncherLayoutAdapter.kt` L423 (Insert事前条件は「存在しないこと」) | **断片のみ。** `OrganizationPlanMaterializer.kt` L79-88がplanned placementをcaptured snapshot itemに限定するためcandidateを含むplanは `Result.Invalid` となり、`PlanPreviewProjector.kt` L79/L343も `PlannedCandidate` をrejectする。plan→candidate Insertのproduction経路は存在しない (本Issueで構築) |
| launchable列挙の権限面 | `integration/AndroidClassificationSignalSnapshotSource.kt`, `ui/CategoryOverrideAuthoring.kt` (L160-166: `LauncherApps.getActivityList(null, user)`) | 参照実装あり |

### 1.2 存在しないもの (本Issueの作業対象)

1. **missing-app detection module**: installed launchable apps と snapshot のapp表現の差分を計算する純粋module。productionにもtestにも存在しない。
2. **選択UI**: `ui/ManualOrganizationRun.kt` の `State` machine (`Idle → Capturing → Planning → Preview → Applying → Applied` + typed失敗state) に選択phaseが存在しない。
3. **production composition**: `integration/OrganizationInputComposer.kt` L314 は `RunMode.FullOrganization` 固定、`FullTargetSetMaterializer` は `additions = emptyList()` 固定 (L63)。
4. **candidate materialization経路**: `OrganizationPlanMaterializer` はplanned placementをcaptured itemに限定し (`Result.Invalid`)、candidateのcanonical item構築とInsert生成経路が存在しない。**materializerが受取るresolverはfolder title用のみ** (`fun materialize(input, result, sourceState, titleResolver: FolderTitleResolver)` L47-52、production呼出しは `LayoutApplicationModule.kt` L180) であり、candidate用のapplication resolver (launch intent / profile / title / icon) は存在しない。preview側も `PlanPreviewProjector` が `PlannedCandidate` をrejectするため、Add行のprojectionも新規。
5. **適用時availability再検証**: 既存Insert事前条件は「DBに存在しないこと」のみ (`LauncherLayoutAdapter.kt` L423) で、capture側availabilityはprofile由来 (`OrganizationInputComposer.kt` L373/L428) でありcomponent状態を反映しない。preview後のdisable / suspend / uninstallを捕捉する事前条件が存在しない。
6. **分類signal・provenanceのadditions対応**: signal materialization (`OrganizationInputComposer.kt` L289) とtarget digest/provenance (L297-319) はcaptured itemsのみが対象。候補を分類対象に含め、選択集合をprovenance identityに反映させる経路が存在しない (ADR-0007のimmutable identity要件)。
7. **preview projectionのAdd表現**: `application/public/PlanPreview.kt` の `PreviewChange` variantsは `MoveChange / PreservedChange / NewFolderChange / NewPageChange / ItemWarningChange` のみ。source placementを持たないAdd行のvariantがない。
8. **候補planning ID導出**: `CandidateItem` は必須の `ItemId` を持ち、分類signalも `ItemId` キー (`ClassificationSignals.decisions: Map<ItemId, CategoryDecision>`) だが、`CandidateTarget.AppKey` からplanning IDを導出する規則は存在しない。captured item IDはfavorites行IDの数値文字列 (`RowManifestCodec.kt` L236)、生成folderは `ItemId("planned-folder-${ordinal}")` (`EffectiveLocks.kt` L244) のため、candidate用namespaceの設計が必要。
9. **UI copy**: 選択画面のstrings (en/ja) なし。

## 2. D-2 comparison: run mode composition

| 観点 | (A) FullOrganizationがadditionsを許可 | (B) 新規run mode |
|---|---|---|
| 検証規則の変更 | `checkAdditionsUnderFull` の削除/緩和。既存のfull-organize検証表面 (property test含む) への影響を再確認する必要がある | 既存mode無変更。新modeの検証・決定性・冪等性testを新規に追加 |
| planner変更 | `placeFullRun` が既存配置計算に候補unitを参加 (allocationは `placeIncrementalRun` のfolder group / unit構成を再利用可能) | 同左を新mode配下で実装。`placeIncrementalRun` は現状「既存全Preserve」のため、#228要求 (既存も再整理) には新経路が必要 |
| 意味論の明示性 | 「全体整理 + 明示的追加」が1つのmodeになる。`RunMode` の意味境界 (full = snapshot全item対象) が変化する | mode名で「scope-composed」を明示できる。`RunMode` enum追加はinteractiveだが局所的 |
| 冪等性 | 適用後に候補がsnapshot表現になり、再実行で空差分 (自然成立) | 同左 |
| 決定 | **(B) 採用 (owner, 2026-09-11)**。`FullOrganization` の「additionsは空」という不変条件と既存契約 (spec 12, spec 83, 既存property test群) を維持できる。 | (A) は不採用。比較記録として残す |

どちらの場合も、配置計算は「既存対象のMove/Preserve計算」と「候補の配置unit化」を1つのallocator入力へ合成する形になり、`placeIncrementalRun` の `FolderCandidate` / `IncUnit` 構成 (L114-160) が再利用候補である。この合成が既存の決定性・tie-break規則を壊さないことの検証が最大の技術リスクである。

採用された (B) の実装要件: 新mode (仮称 `RunMode.ScopeComposedOrganization`) 向けの検証 (`PlanningValidation` に既存FullOrganization検証と並行して追加、既存検証は無変更)、planner dispatchへの新mode追加、新mode向けの決定性・冪等性property test。新modeの正式名称は実装PRで確定し、CONTEXT.mdへの用語追加 (全体整理/増分配置に並ぶ第3mode) を同じPRで行う。

さらに (B) の実装要件として、次の2点がspec AC-13から要求される:

- **分類signalのadditions対応**: `OrganizationInputComposer` のsignal materialization (`materializeSignals(mapped.items, ...)` L289) はcaptured itemsのみが対象である。選択済み候補を同一のpolicy cut (immutable bundle + user category override) の下で分類対象に組み込む処理を、(A)/(B)いずれでもcomposerへ追加する必要がある。
- **provenance identityのadditions包含**: target materializationのidentity/digestはexisting membershipsのみから計算されている。選択集合をdigestへ含めずにadditionsだけ差し替えると、異なるtarget内容が同一provenance identityを持つ (stale再利用)。選択集合を含む新規immutable identityの計算はADR-0007のauthority modelの延長であり、ADR-0007本文の更新 (additionsのprovenance規定の追記) を本featureのPRで行う。

## 3. 変更moduleと所有境界

```text
lawnchair/src/app/lawnchair/organizer/
├── integration/
│   ├── MissingAppCandidateSource.kt        # 新規: LauncherApps列挙とcaptureの差分計算
│   │                                        #   (読み取りのみ。pureな差分計算は別関数としてtest可能に)
│   ├── OrganizationInputComposer.kt        # 更新: 選択済み候補のcomposition追加 (additions転換、
│   │                                        #   分類signal materializationへの候補追加、provenance/digest拡張)
│   └── ProductionOrganizationInputComposer.kt # 更新: 新flowのwiring
├── planning/
│   ├── CandidatePlanningIds.kt             # 新規: `CandidateTarget.AppKey` → `ItemId` 導出 (純粋関数、namespace分離)
│   ├── PlanningValidation.kt               # 更新: 新mode (仮称 ScopeComposedOrganization) 向けadditions検証
│   │                                        #   (既存FullOrganization検証 ADDITIONS_UNDER_FULL_ORGANIZATION は無変更)
│   ├── PlanningPlacement.kt                # 更新: 既存再整理 + 候補配置の合成経路
│   └── DeterministicOrganizationPlanner.kt # 更新: 新modeのdispatch
├── application/
│   ├── actions/OrganizationPlanMaterializer.kt # 更新: candidate/source partition検証、CandidateApplicationResolver注入、
│   │                                        #   候補canonical item構築、candidate Insert生成
│   ├── preview/PlanPreviewProjector.kt     # 更新: `PlannedCandidate` 行のAdd projection (L79/L343のreject解除)
│   ├── protocol/                           # 更新: CandidateApplicationResolver port (composition/plan時の解決) と
│   │                                        #   適用時availability再検証port (component+profile単位、commit前拒否)
│   └── public/PlanPreview.kt               # 更新: `AddChange` projection (additive) + PreviewCountsのAdd count
├── ui/
│   ├── ManualOrganizationRun.kt            # 更新: 選択phaseのState追加、Add含むrunの具体preview gate
│   └── MissingAppSelectionScreen.kt        # 新規: 選択UI (multi-select / search / bulk)
└── (rules/, locks/, diagnostics/ は原則無変更)
```

所有境界:

- detectionは **integration** に置く (platform `LauncherApps` 読み取りが必要なため)。純粋差分計算 (`Set<ComponentKey+ProfileId>` ベース) はplatform型に依存しない関数として切り出し、planning側の型 (`CandidateTarget.AppKey`) を出力とする。planning moduleへplatform型を漏らさない (AGENTS.md設計規約)。
- 候補planning ID導出は **planning** の純粋関数とする。表示label等のlocale依存情報から採番せず、安定identityから決定的に導出する。namespaceはcaptured item ID (favorites行IDの数値文字列) と生成folder (`planned-folder-*`) の双方と衝突しない専用prefixを持つ。
- 選択stateはUIが所有し、composition seamへは「確定済み候補集合」として渡す。UIが`LauncherApps`を直接呼ぶのは既存 `CategoryOverrideAuthoring` の前例に従うが、detection本体は再利用可能な単一sourceに置く。
- candidate materializationは **application/actions** の既存 `OrganizationPlanMaterializer` の拡張として行う。独立のcandidate用materializerを作らず、planner出力のpartition (existing placements / planned candidates) を1箇所で検証し、Insert生成も既存 `ActionMaterializer` の流儀に従う。
- candidateのcanonical構築に必要な解決 (launch target / title / icon / profile / item availability) は **application所有の `CandidateApplicationResolver` port** が担う。portは`materialize` へ `titleResolver` と並んで注入し、platform実装は既存adapter面 (`LauncherApps` / `PackageManager` / model projection) を用いる。UIやcomposerが自前で解決しない。
- availability再検証は **application** 所有のport (protocol層) とし、adapter実装はplatform `LauncherApps` / `PackageManager` を用いる。UIはportを知らない。検証結果はcommit前拒否 (`PreWriteRejection` 相当のtyped結果) として既存apply pathに接続する。**`CandidateApplicationResolver` はcomposition/plan時の構築解決、availability再検証portは適用直前の再検証であり、責務を混在させない。**
- preview projectionの変更は `PlanPreview.kt` / `PlanPreviewProjector.kt` のadditive拡張とし、`PlanPreviewDetails` / `PreviewCounts` の既存shape契約 (spec 208 AC-1) は既存行の範囲で維持する (Add分の拡張はspec §5の意図的拡張)。

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

- composition seam: `OrganizationInputComposer` に選択済み候補 (`List<CandidateTarget.AppKey>`) を渡す新規method、または既存 `composeFullOrganization` の引数拡張。`CandidateItem` への変換 (`span = 1x1`, `availability` 再確認, planning ID導出) はcomposer内で行う。
- planner入力は既存 `OrganizationInput` のshapeのまま (`targets.additions` を埋める)。interface変更は最小。
- 候補planning ID導出規約 (planning純粋関数、spec AC-15対応):

```kotlin
// planning (新規)
object CandidatePlanningIds {
    /**
     * 安定identity (ComponentKey + ProfileId) から決定的に導出する。
     * - prefix "candidate-" は captured item ID (favorites行IDの数値文字列) と
     *   生成folder ID ("planned-folder-*") の双方と衝突しない専用namespace
     * - hashはSHA-256の16進64文字 (切詰めない。candidate間の同IDはhash衝突のみ)
     * - 表示label / locale / 列挙順に依存しない。同一identityから同一ID
     */
    fun planningId(target: CandidateTarget.AppKey): ItemId =
        ItemId("candidate-" + sha256Hex("${target.component}:${target.profile.value}"))
}
```

  - 導出はcomposerが `CandidateItem` 変換時に使う。既存snapshotとの衝突検証はunit testでnamespace境界として固定する (数値文字列・`planned-folder-*`との交差なし)。
- candidateのcanonical構築解決port (application所有、新規):

```kotlin
// application/protocol (port) — platform実装はadapter
interface CandidateApplicationResolver {
    /** composition/plan時に1回。canonical application item構築に必要な解決 */
    fun resolve(target: CandidateTarget.AppKey): CandidateApplicationResolution
}
sealed interface CandidateApplicationResolution {
    data class Ready(
        val launchTarget: ApplicationLaunchTarget,  // intent構築面はadapter内部に隠す
        val title: String,
        val icon: ApplicationIconRef,
        val profile: ProfileId,
        val availability: Availability,
    ) : CandidateApplicationResolution
    data class Unavailable(val reason: CandidateResolutionFailure) : CandidateApplicationResolution
}
```

  - `OrganizationPlanMaterializer.materialize` は `titleResolver: FolderTitleResolver` と並ぶ引数としてportを受取り (`LayoutApplicationModule.kt` L180の呼出しを更新)、production実装はouter composition (`LawnchairApp`) から注入する (既存 `FolderTitleResolver` と同じDI経路)。
  - 解決失敗 (`Unavailable`) は計画段階のtyped失敗であり、部分採用しない (選択集合のうち1件でも解決不能ならcomposition失敗として扱い、再検出を促す)。
- 適用時availability再検証port (application所有、新規):

```kotlin
// application/protocol (port) — adapter実装はplatform面
interface CandidateAvailabilityPort {
    /** commit前の再検証。検証対象はplan中のcandidate identity集合 (component+profile) */
    fun verifyLaunchable(candidates: List<CandidateTarget.AppKey>): AvailabilityVerification
}
sealed interface AvailabilityVerification {
    data object AllAvailable : AvailabilityVerification
    data class Unavailable(val identities: List<CandidateTarget.AppKey>) : AvailabilityVerification
    data class Unknown(val reason: String) : AvailabilityVerification  // 検証自体の失敗もfail-closedで拒否
}
```

  - 検証呼出しは既存apply pathのstale revision再確認と同じ境界 (`LauncherLayoutAdapter` のtransaction前) に置く。失敗はcommit前のtyped拒否として現れ、DB書込みは発生しない。testは既存fault-injection契約に倣いportを置換する。

## 5. 制御フロー (data flow)

```text
ManualOrganizationRun (start)
  → Capturing (既存 canonical capture)
  → CandidateDetection (新State: MissingAppCandidateSource.detect, zero-write)
  → Selecting (新State: 選択UI。process-local state)
      ├─ cancel → Idle (zero-write)
      └─ confirm (選択集合。空でも可)
  → composition (composer が planning ID導出 + TargetSet.additions 変換 + 分類signal/provenance拡張)
  → Planning (planner。既存のpreview/reject経路)
  → Preview (inspectPlan, read-only。Add行を含む変更一覧)
      └─ Addを含むrunで具体previewが得られない場合: 確認不可 (typed失敗/re-preview誘導)
         既存の count-only fallback (details=null→確認時materialize) はAdd無しrunのみ許容
      └─ confirm → Applying (既存 transaction / stale / recovery)
             ├─ commit前: availability再検証 + stale revision + 事前条件 → 失敗はrollback, 変更0件
             └─ commit後: 適用後検証 → 失敗は既存recovery契約 (Unresolvedもあり得る)
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
| 選択済み候補の解決失敗 (`CandidateApplicationResolver.Unavailable`) | composition段階のtyped失敗。部分採用しない。再検出を促す |
| planner reject (容量不足等) | 既存の `PlanningRejected(IMPOSSIBLE/INVALID)` 表示。未配置候補は `UnplacedItem` warningとして提案される既存経路に乗せる |
| Add含むrunで具体preview unavailable | 確認不可 (typed失敗 / re-preview誘導)。count-only fallbackでの確認を禁止 (spec AC-14)。Add無しrunは既存fallback挙動のまま |
| 適用時のapp無効化 / uninstall | commit前availability再検証で拒否 (fail-closed全体失敗)。rollbackされworkspace変更0件。再検出を促す |
| 検証port自体の失敗 (`Unknown`) | fail-closedでcommit前拒否 (楽観的な成功扱いにしない) |
| commit後の検証失敗 | 既存recovery契約。`Unresolved` / recovery失敗の結果表示は既存契約 (spec 13 / #210系) に従い、無条件復旧をUIが約束しない |
| stale layout revision | 既存 `State.Stale` (recapture) |

## 8. テスト戦略

- **detection unit test** (JVM): 純粋差分計算に対し、folder内 / dock / app pair / 重複配置 / work profile分離 / disabled・suspended除外 / 複数launcher activity / non-launchable構造除外 / 空 snapshot の fixture。property test (任意のsnapshot×inventoryで、候補 = inventory − 表現identity集合 が常に成立)。
- **planner unit / property test**: 選択候補を含む入力での決定性 (byte-equivalent再現)・冪等性 (適用後再計画で空差分)・conservation (既存itemは保持/移動/削除のいずれか)・未選択候補の不在。空workspace入力。
- **composer / provenance unit test**: 選択集合を含むsignal materialization (候補がbundle + user overrideのみを経由して分類される)、選択集合の変更がprovenance identity (target digest) を変えること、空additionsで既存identityと一致すること (既存flow非退行)。
- **materializer unit test**: candidate partition検証 (captured itemとplanned candidateの混合plan)、候補canonical item構築、生成folder membership、candidate Insert生成。Add無しplanで既存挙動がbyte-equivalentに維持されること。
- **projection unit test**: `AddChange` の構築 (top-level配置と生成folder所属の双方)、counts整合 (**Add count = `AddChange` 行数 = 配置先確定済み選択候補数。生成folder所属候補を含む**)、Move/Preserve行への非干渉 (spec 208のidentity invariant testの無変更通過)。生成folderのmember list構造 (label-only) の無変更確認。
- **planning ID unit test**: 決定性 (同一identity→同一ID)、locale/label非依存、namespace境界 (captured item ID数値文字列・`planned-folder-*`との交差なし)、選択集合→同一ID (AC-15)。
- **resolver契約test**: `CandidateApplicationResolver` のtyped failure (`Unavailable`) がcomposition失敗となること、`CandidateAvailabilityPort` (apply直前) との責務分離 (plan時失敗は書込みに到達しない)。
- **application契約test** (test DB): Insertを含む適用のtransaction・失敗注入・rollback・stale・適用後検証 (model snapshot突合せ)。availability再検証portの注入 (preview後・commit直前のdisable/uninstall → commit前拒否、変更0件)、port失敗 (`Unknown`) のfail-closed、commit後recovery失敗 (`Unresolved`) の結果契約。
- **preview gate test**: Add含むrunで `details = null` のfallbackが確認不可であること、Add無しrunで既存fallback挙動が維持されること。
- **instrumentation test** (API 36 / Platform 36.1): 選択UI (multi-select / search / select all (filter範囲) / clear all / 選択数 / cancel)・zero-write (DB比較 **+ application write seam呼出し回数0**)・空workspace混在のend-to-end・ja string解決。**新規test classは `ci.yml` connected-test lanes のclass filterへ実装PRで追加する** (filterは明示列挙のため自動発見されない)。
- **a11y evidence**: 自動assertion (unit / instrumentation) に加え、TalkBack / Switch Access実操作・keyboard・200% fontのdevice evidenceを分離して記録する。
- **device evidence**: fresh workspace (初期化後 / 別launcherからの切替) での手順実行記録。
- **高リスクgate**: 本specはfrontmatterで `risk: layout-data` を指定しているため、実装PRにはAGENTS.mdの高リスクPR独立エビデンス要件 (`final-status` CI成功 + `docs/assessment/pr-<PR番号>-<slug>.md` の独立audit記録) が課される。実装Workerは最初のPR作成時にこれを前提に計画する。

## 9. 実装順序 (incremental)

**開始gate**: 本planの全手順 (手順1の純粋計算とtestを含む) は、**spec.mdがownerによりacceptedになった後にのみ開始する** (D-1/D-2は決定済み。受入対象は決定反映済みの改訂版spec)。Issue #228とAGENTS.mdの要求により、missing-app identity規則・選択semantics・create-mutation安全契約の受入前にsource実装を始めない。D-3 (候補表示順) は決定性 (NFR-003) を満たす範囲で実装PR内で確定してよい実装判断であり、受入後の実装PRで決める。

1. detection純粋計算 + unit test (platformなしで検証可能)
2. planning ID導出 (`CandidatePlanningIds`) + `MissingAppCandidateSource` production実装 + composition拡張 (additions転換、分類signal materializationの候補対応、provenance/digest拡張)
3. plannerの合成配置経路 + 新mode (ScopeComposedOrganization) 検証 + dispatch + property test
4. `OrganizationPlanMaterializer` のcandidate partition / `CandidateApplicationResolver` 注入 / canonical構築 / Insert生成 + `PlanPreviewProjector` / `PlanPreview.kt` の `AddChange` 表現 (候補ごと1行、生成folder所属を含む) + counts拡張 (spec 195/208契約の既存test無変更確認)
5. 適用時availability再検証port + adapter実装 + 契約test (commit前拒否 / Unknown fail-closed / recovery結果契約)
6. 選択UI + State machine拡張 + Add含むrunの具体preview gate + en/ja strings + CONTEXT.mdへの新mode用語追加
7. instrumentation / device evidence (CI class filter更新を含む)

各段階で既存test全通過を確認する。手順は依存順であり、spec受入前に着手する手順は存在しない。

## 10. リスク

- **R-1 (高)**: 合成配置が既存full-organizeの決定性・冪等性propertyを壊す可能性。対策: 手順3でproperty testを先に拡張し、既存corpusでの回帰を検証する。
- **R-2 (中)**: `PreviewCounts` truth契約 (spec 195 D2) へのAdd group追加が既存UI testに波及する。additive拡張にとどめ、既存行の意味変更をしない。
- **R-3 (中)**: 選択UIのa11y (multi-select semantics) の証拠作成コスト。既存spec 52契約のpatternを踏襲する。
- **R-4 (低)**: app inventory列挙のprofile列挙失敗。typed Unavailableで既存 `InputUnavailable` 表示に接続する。
- **R-5 (中)**: 候補の分類signal materialization組み込みが、既存signal解決の決定性・contradiction検証 (SIGNAL_CONTRADICTION経路) を複雑化する可能性。手順2でunit testを先に固定する。
- **R-6 (中)**: availability再検証portの検証タイミングがcommit直前でもraceを持つ (検証直後にdisable)。transaction境界内での完全な検証はplatform制約上困難な場合があり、その境界を手順5の契約testで明示する (Unknown fail-closedを既定とする)。

## 11. 未確認領域 (明示)

- `placeIncrementalRun` のfolder group構成をfull-organize合成に再利用した場合のtie-break順序が既存`GLOBAL_COMPACT_V2`等のstrategy別期待値と衝突しないかは、実装時にstrategyごとの期待値testで確認する必要がある (baseline時点では未検証)。
- `PreviewLabel` / 行構築 (`OrganizationPreviewContent`) の既存copy構造にAdd行が収まるかの詳細は、手順4での実装時確認 (spec 195の行形式契約に従う)。
- app pair memberとして表現されたappが `TargetKey.AppKey` 等価で差分計算できるかは、captureのapp pair表現 (`AppPairMetadata` / members) の実装詳細確認が必要 (baseline時点でmembersのtarget解決方法は未確認)。
- 「`getActivityList` がdeep-link-only / non-launchable packageを構造的に排除する」というspec §1の前提は、platform側の挙動証拠 (synthetic package / disabled activityの挙動) が未取得であるため、手順1の実装時にplatform観察で確認する。除外が必要になれば別Issueで扱う (spec §1の規定通り)。
- availability再検証の検証タイミングとtransaction境界のrace範囲 (R-6) は、platform上の実証が手順5まで確定しない。
- 本planは`git worktree`上のbaseline (`6b6bf8dd`) で初版検証し、2026-09-11に再入場検証を行ってbaseline `b761839479` へ再アンカーした (§1参照箇所の行番号は現baseline上で再確認済み)。CI上でのbuild/testは実行していない (docs-only差分)。

## 12. 関連

- spec: [./spec.md](./spec.md) (draft)
- Issue: [#228](https://github.com/nunu1733/NunuLauncher/issues/228)
- 依存: #182, #194, #195, #208 (いずれもimplemented), #203 (オプション・未依存)

## Change history

- 2026-09-10: 初版起草 (baseline `6b6bf8dd9fa0c42399185dbb13c30192f1e15962`)。
- 2026-09-11: 再入場検証。baselineを `b761839479259cb4df815151e01df93c655448a7` へ再アンカーし、§1の参照箇所 (`OrganizationInput.kt` L216、`FullTargetSetMaterializer.kt` L63、`PlanningValidation.kt` L619-624/L627-641、`PlanPreview.kt` variants、`ActionMaterializer.kt` L82、`LauncherLayoutAdapter.kt` L423、`CategoryOverrideAuthoring.kt` L166) を現baseline上で再確認。baseline以降のorganizer変更は #271 のadditive変更のみであり、本planの前提に影響なし。
- 2026-09-11: レビュー条件解消 (code-reviewer-1, Request changes → 修正)。(1) 初版の「typed create pathが既存」という§1.1記載を「断片のみ/production経路は本Issueで構築」へ修正し、§1.2にcandidate materialization経路・availability再検証・分類/provenance対応を追加。(2) §2にsignal materializationとprovenance identityのadditions包含要件 (ADR-0007更新を含む) を追記。(3) §3に `OrganizationPlanMaterializer` / `PlanPreviewProjector` / availability再検証portの変更moduleと所有境界を追加。(4) §4にavailability再検証portのinterface草案を追加。(5) §5にAdd含むrunの具体preview gateをflowへ反映。(6) §7にcommit前/後の失敗区分と `Unknown` fail-closedを追加。(7) §8にmaterializer/provenance/preview gate testとCI class filter要件を追加。(8) §9の開始gateを「全手順はspec受入後」に統一 (初版の「手順1はD-1/D-2と独立に着手できる」を削除)。(9) §10にR-5/R-6、§11にplatform前提の未確認項目を追加。
- 2026-09-11: ownerレビュー条件解消 (Request changes → 修正)。(1) **D-2を採用 (B) で確定** (owner決定 2026-09-11) し、§2を「(B) 採用、(A) は比較記録」に更新、§3/§9を新mode `ScopeComposedOrganization` 実装要件へ統一。plan冒頭の「D-1〜D-3 owner確定必須」を「D-1/D-2決定済み、D-3実装PR判断」へ統一。(2) **候補planning ID導出規約** (§1.2 item 8、§3 `CandidatePlanningIds`、§4 `planningId` 草案: `candidate-` + SHA-256 16進64文字、namespace分離、locale非依存) を追加し、AC-15対応のunit testを§8へ追加。(3) **`CandidateApplicationResolver` port** (§1.2 item 4にresolver不在を明記、§3所有境界、§4 interface草案、§7責務分離、§8契約test、§9手順4へ注入を追加) を定義し、`CandidateAvailabilityPort` (apply直前) との責務分離を明示。(4) §8のprojection testを「Add count = `AddChange` 行数 (生成folder所属候補を含む)」へ更新。
