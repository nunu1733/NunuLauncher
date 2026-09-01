# Implementation Plan: QSB予約行に存在するitemをorganizerが扱えるinterop意味論

> Issue: [#185](https://github.com/nunu1733/NunuLauncher/issues/185)
> Spec: [spec.md](./spec.md)
> Decision: [ADR-0010](../../docs/adr/0010-qsb-row-item-overlap-interop.md)(accepted — contract-test方式を採用、bridge不採用)
> Status: **implemented — 2026-09-01。検証の詳細は [docs/assessment/issue-185-qsb-row-interop.md](../../docs/assessment/issue-185-qsb-row-interop.md)**
> Baseline: `ac09d27bae` (`main`, 2026-08-31)

## Current evidence

### 確認済みの事実

- capture失敗のthrow箇所は `RowManifestCodec.validateReservations()` の `require(...none { overlap... }) { "Workspace item overlaps a platform reservation" }`(`lawnchair/src/app/lawnchair/organizer/application/adapter/RowManifestCodec.kt:167-180`)。#172 assessmentでrow 115が `screen=0 cell(2,0)` に実在することをDB取得で確認済み。
- 予約geometryは `LauncherLayoutAdapter.captureWorkspaceContext()`(`LauncherLayoutAdapter.kt:149-165`)が `GridCell(0,0) × GridSpan(idp.numSearchContainerColumns, 1)` を生成する。
- `LoaderCursor.checkItemPlacement`(`src/com/android/launcher3/model/LoaderCursor.java:569-595`)は同一の `numSearchContainerColumns × 1` をload時にmarkし、重複itemの存続を `PreferenceManager2.allowWidgetOverlap`(既定false、`lawnchair/res/values/config.xml:116`)に委ねる。capture側の予約geometryはloaderと一致しており、狭小化の余地はない。
- `allowWidgetOverlap` の `onSet` は `reloadHelper.reloadGrid()` を呼ぶ(`lawnchair/src/app/lawnchair/preferences2/PreferenceManager2.kt:263-268`)が、grid reload後に `LayoutState` が変化しなければrevisionは不変であり得る。**したがってpolicy反転の検出をrevision変化に依存させてはならない**(review Blocking 1)。policyはrevision/digestに含めず、A5/recovery時のstate-based predicate再評価で閉じる。
- A5は `LauncherLayoutAdapter.applyWriteSet` のtransaction内で `capture()` をre-readし、`preconditionsHold` が全actionのexpected(行の完全一致、item identityを含む)を検証している(`LauncherLayoutAdapter.kt:286-404`)。review Blocking 2の「placement一致のみで継続」は現行実装に存在せず、本planのmaterializer免除がその誤解を生まないようspec側で明文化済み。
- diagnostics契約は `PRE_WRITE_REJECTED` familyに `PreWriteRejection` の定数名をそのままcodeとして記録する(`docs/engineering/organizer-diagnostics.md:440-441`)。enumへの新値追加は既存mapping機構でjournalへ流れる。
- #172再現のraw logcat(`Item position overlap` / `Error loading shortcut` 0件)と取得DBから、再現環境ではloaderが重複itemを受容して生存していた。
- `InvariantDeviceProfile.java:415-416`: 非multi-displayでは `numSearchContainerColumns == dbGridInfo.getNumHotseatColumns()`。5x5では予約 = 行全体。
- Nova converter(`lawnchair/src/app/lawnchair/backup/NovaBackupConverter.kt:432`)はsmartspace無効時にcellY shiftを適用しないため、QSB行内itemはimportで到達可能。

### 推測と区別した点

- 再現emulatorの `allowWidgetOverlap` 値は未確認(adb接続不可)。受容gateにより両値で挙動が定義されるため、実装と検証はこの値に依存しない。

### 推論

- captureの `LayoutState` は予約重複itemを既に表現できる(items と reservedWorkspaceRegions は独立)。fail-closedしているのは `validateReservations` のみであり、射影・分類・検証・materializeの各層にも重複を想定しないguardが存在する:

| 層 | 現在の重複扱い | 本planでの変更 |
|---|---|---|
| `RowManifestCodec.validateReservations` | throw | 重複を許可(他のrequireは不変) |
| `FullTargetSetMaterializer.materialize`(integration) | role割当てに重複を考慮しない(Movableになり得る) | precedence (0) `ExistingRole.Preserved` |
| `PlanningValidation.checkOverlap`(`PlanningValidation.kt:261-271`) | item↔予約重複をrejection | この項目のみ撤去 |
| `PlanningPlacement.determinePreservation`(`PlanningPlacement.kt:475`) | 重複を考慮しない | precedence (0) `PreserveReason.RESERVED_REGION` |
| `PlanningPlacement.place` のoccupancy初期化 | 予約を先にmark、preserved itemのcellsをmark | 変更不要(冪等) |
| `OrganizationPlanMaterializer.overlapsReservation`(`OrganizationPlanMaterializer.kt:58-62`) | 全targetの予約重複を `Invalid` | captured placementと同一のtargetは免除(plan時guard限定。apply/recoveryのexact preconditionは緩和しない) |
| `LauncherLayoutAdapter.applyWriteSet` | policy反転を検出する仕組みなし | A5/recovery transaction内で受容policyを再読し、acceptance predicateでintended stateを再評価。未満ならtyped `PreconditionFailed` |
| `PreWriteRejection`(`application/public/Results.kt:71`) | 予約受容を表すcodeなし | `OVERLAP_POLICY_REJECTED` を追加。diagnostics契約§5の `PRE_WRITE_REJECTED` familyへ追記 |
| 共通acceptance predicate | `LoaderCursor` とorganizerで意味論が重複 | 単一predicateを定義し、可能なら `LoaderCursor` のQSB重複分岐からも参照(bridge 1箇所)。不可能なら等価contract testを必須化。`WorkspaceOverlapToleranceSource`(production実装は `allowWidgetOverlap`)が「現在の受容policyの真実」を供給する |

## Design

### Modules and interfaces

| Module / path | 変更 | Seam |
|---|---|---|
| `organizer/application/adapter/RowManifestCodec.kt` | item↔予約重複requireの撤去。コメントでIssue #185とADR-0010を参照 | 内部(capture経由で全呼出側に波及) |
| `organizer/application/public` | `CaptureFailureCategory` に `RESERVED_OVERLAP` を追加 | 既存public型のenum追加 |
| `organizer/diagnostics`(model/validation) | `InputCompositionCode` に `CAPTURE_RESERVED_OVERLAP` を追加。`validCodesForFamily(INPUT_READINESS)` へ追加 | 既存closed集合の1値追加(#172 §3/§8規定の範囲) |
| `organizer/integration/OrganizationInputComposer.kt` | (1) `WorkspaceOverlapToleranceSource` の注入`(2) mapLayout後に重複item存在 + 受容無効を検査し `NotReady(RESERVED_OVERLAP / CAPTURE_RESERVED_OVERLAP)` を返す(3) `LayoutWriterCanonicalCaptureSource` は不変 | 内部source注入(bundle/override/evidence sourceと同様)。production wiringはcomposer生成箇所で `PreferenceManager2.allowWidgetOverlap` を接続 |
| `organizer/integration/FullTargetSetMaterializer.kt` | role割当ての先頭に「captured workspace placementがsnapshot予約と重なる→ `Preserved` 」を追加。materializerはreservation listを引数で受け取るようになる | 内部 |
| `organizer/planning/PlanningValidation.kt` | `checkOverlap` のitem↔予約重複チェック撤去 | 内部 |
| `organizer/planning/PlanningPlacement.kt` | `determinePreservation` の先頭に `PreserveReason.RESERVED_REGION` を追加。予約との重複判定に `input.snapshot.reservedWorkspaceRegions` を使用 | 内部 |
| `organizer/planning/PlanningResult.kt` | `PreserveReason` に `RESERVED_REGION` を追加 | 既存public型のenum追加 |
| `organizer/application/actions/OrganizationPlanMaterializer.kt` | targetが当該itemのcaptured workspace placementと同一の場合に予約guardを免除 | 内部 |
| UI(`ManualOrganizationPreferences.kt`) | `preservedReasonString` に `RESERVED_REGION` の分岐追加 + string resource(en/ja) | 既存summary UI |
| composer wiring(`organizer/` DI/graph構築箇所) | `WorkspaceOverlapToleranceSource` のproduction実装 | 既存composer生成経路 |

### Data flow

1. capture: 予約重複行を含む `favorites` をlosslessに読む(throwしない)。`LayoutState`/manifest/revisionはshape不変。受容policyはrevision/digestに含めない。
2. compose: capture → unknown lock → mapLayout → **[新] 予約重複item存在 && !tolerant → `NotReady(CAPTURE_RESERVED_OVERLAP)`**(受容sourceは1回だけ読む)→ 既存のbundle/override/evidence flowへ続く。
3. plan: TargetSet role と placement disposition の両方で `RESERVED_REGION` precedence が適用される。plannerは予約セルを初期occupancyとしてmarkし、movable targetを予約外へ割当てる(既存どおり)。
4. materialize: plan時guardとして「target == captured placement」のその場保存のみ予約重複を許す。**この免除はplanner出力の検証に限られ、A5以降のexact preconditionを緩和しない**(review Blocking 2)。
5. apply(A5): transaction内で `capture()` re-read → `STALE_REVISION` / `preconditionsHold`(行の完全一致、item identityを含む)→ **[新] 共通acceptance predicate: 現在の受容policyを再読し、intended manifestに予約重複desktop rowが含まれる場合は受容必須。満たされなければ `PreconditionFailed(OVERLAP_POLICY_REJECTED)` でno-write**(write前、atomic)。
6. recovery: 既存のpre-state完全一致判定に加え、**[新]** recovery targetに予約重複行が含まれる場合はA5と同一のacceptance predicateで再評価する。不一致ならtyped failureでno-writeとし、recovery pointは保持される(retryでpolicy回復後に再評価可能)。
7. correlated reload後のA7: 受容有効環境ではloaderが当該itemを保持するためrecapture一致が維持される。受容がapply後に反転した場合の行削除は、既存のA7 mismatch → recovery経路で既存契約どおり処理される。

### Alternatives rejected

- **予約の狭小化**: 5x5でno-op + loaderとのgeometry不一致で#155を再発させる(spec Decision節参照)。
- **import時修復**: 既存workspaceを救済できず、import fidelityに反する。
- **予約重複itemをMovableとして移動させる**: planner target生成・UI copy・#83分類契約への影響が大きく、本bug fixの射程を超える。ADR-0010のalternativesに記録。
- **受容状態を `LayoutState` / context resource / revision・digest計算に埋め込む**: revision formula変更はrecovery pointのpre/post digest一致判定に波及し、build更新を跨ぐpending recovery pointが `NEITHER` 分類となりRecoverability不変条件(#13)を損なう。policy値はcapture時点の端末状態であり、layoutのcanonical contentではない。**不採用。**
- **capture時の受容値をplan/writeSetに保存し、A5で値比較する**: writeSet/protocol型の拡張が必要な上、「重複行を含まないintended state」まで不要に拒絶する。state-based predicate(intended stateが予約重複行を含む場合のみ受容を要求)の方が狭く、reviewの要求(apply/recovery時にcurrent policyでacceptanceを再評価)に合致する。**不採用。**
- **受容gateをcapture内のthrowで実装する**: #172のdebug log行(例外class名)が発火し、composer理由がblanket `CAPTURE_INVALID` に戻る。狭いtyped理由の要件に反する。**不採用。**
- **organizer側で `allowWidgetOverlap` booleanを読み、loader判定を再実装する**: loader側の条件変更時にsemantic driftする(review non-blocking)。共通predicateへの統合(可能なら `LoaderCursor` からも参照)を第一選択とし、不可能な場合は等価contract testでdriftを検知する。**部分採用(統合できない場合のみcontract test)。**

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `RowManifestCodec.kt` | 重複require撤去 | 重複状態の単一のfail-close箇所 |
| `OrganizationInputComposer.kt` + public `CaptureFailureCategory` | 受容gate + `RESERVED_OVERLAP` | readiness判定の正本がcomposerであるため(#83/#172) |
| diagnostics `InputCompositionCode` + `organizer-diagnostics.md` §5 | `CAPTURE_RESERVED_OVERLAP` 追加 | journal理由コードのclosed集合の正本 |
| `FullTargetSetMaterializer.kt` / `PlanningPlacement.kt` / `PlanningResult.kt` | `RESERVED_REGION` precedence | 分類precedenceの2つの正implementation site |
| `PlanningValidation.kt` | item↔予約重複rejection撤去 | captured状態の一貫性検証の正本 |
| `OrganizationPlanMaterializer.kt` | その場保存の免除 | apply直前の予約guardの正本 |
| UI + strings(en/ja) | preserved件数copy | `preservedReasonString` がenum網羅whenのため必須 |
| `LauncherLayoutAdapter.kt` / 共通acceptance predicate | A5/recovery内の受容再評価 + `PreWriteRejection.OVERLAP_POLICY_REJECTED` | Blocking 1/2: write前の最終防衛線。predicateはcomposer gateと共有 |
| `preconditionsHold` / `RecoveryWriteSetMaterializer` | 変更なし(exact契約を維持) | Blocking 2: placement一致のみでの継続を許さない既存保証が正である |
| FakeLayoutWriter / organizer test fixtures | 受容sourceとpredicateのfake挙動をproduction契約と一致させる | unit test seamが実adapterと同じpredicateを使うことを保証 |
| ADR-0010 / ADR-0008注記 | 決定記録 | AGENTS.mdのADR条件(表現可能状態の再定義は高コスト判断) |

## Migration and recovery

- schema/format migrationはnone(recovery v2、journal schemaVersion 1、backup format不変)。受容policyはrevision/digestに含めないため、recovery pointの意味はbuildを跨いで安定する。
- downgrade: 新理由コード(`CAPTURE_RESERVED_OVERLAP`、`OVERLAP_POLICY_REJECTED`)はjournal上のString定数であり、旧buildは未知codeを `UNMAPPED` へ落とすか、新enum値を含むjournalのdecode失敗時は#172規定のcorruption isolationに従う。新規riskはなし。
- rollback: code revertのみで既存挙動に戻る。data移行物なし。
- 失敗経路: 受容predicate不成立時はA5/recoveryともtransaction commit前のtyped拒絶であり、中途半端な適用は生じない。recoveryはpointを保持して後日の再評価に備える。
- policy反転とrecovery: 受容有効環境で作成したrecovery pointが受容無効環境で適用される場合、predicateはrecovery targetの予約重複行を受容できないためtyped failureでno-writeとする。recovery pointの削除・書換えは行わない。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | ADR-0010本体、ADR-0008 change history | review |
| AC-2 | `RowManifestCodec` capture/manifest fixture、composer分類test、planner seam test、materializer guard test、apply/A7 unit test | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` |
| AC-3 | `docs/assessment/issue-185-qsb-row-interop.md`: #172手順(5x5 Nova restore → organizer起動)のemulator実行記録 + focused instrumentation(5x5 DBへ予約重複行を構築→manual runがA7まで通過) | emulator `nunu_qpr2_api36_1`、`connectedLawnWithQuickstepGithubDebugAndroidTest`(対象class絞り) |
| AC-4 | negative test群(target→予約、予約不正geometry、item↔item、予約↔予約)+ recovery round trip(予約重複行を含むpre-state) | 同上 unit tests |
| AC-5 | composer受容source行列(4組合せ)unit test | 同上 |
| AC-6 | 既存test群無回帰 + `ModelValidationTest` 等のclosed集合更新 | `./gradlew spotlessCheck` + `testLawnWithQuickstepGithubDebugUnitTest` + `assembleLawnWithQuickstepGithubDebug` |
| AC-7 | strings en/ja + summary compose test | 同上 |
| AC-8 | policy反転test行列(review要求リスト対応): (a) 受容有効でorganize→apply前に無効化→typed no-write、(b) 無効環境のtyped `NotReady`→silent drop/relocationなし・自動retry/busy loopなし、(c) 重複行を含まないintended stateはpolicy反転の影響なし、(d) recovery targetが重複行を含み受容無効→no-write・point保持、(e) 同一座標・別ID入替→exact precondition不一致、(f) capture後の新規conflicting occupant→revision/stale拒絶、(g) unrelated revision変更(対象item不変)→既存 `STALE_REVISION` | unit/adapter seam test(`tests/unit/app/lawnchair/organizer/application/adapter/`、`actions/`) |
| AC-9 | 共通predicateのunit test(bridge参照時)または `LoaderCursor` acceptance↔organizer判定の等価contract test(同一cell/同一policy値の行列)。選択方式をADR-0010へ記録 | unit tests(`tests/unit`、`LauncherCursor` 依存はJVM側で可能な範囲) |

既存testの更新が必要な箇所: `DeterministicOrganizationPlannerTest.reservationOverlapAndUnknownPageAreRejected`(item↔予約重複はpreservedへ、不明page/予約↔予約は拒絶のままへ分割)、`RealAdapterRowMatrixInstrumentationTest.reservationOverlapIsRejectedOnlyWhenRegionsShareAPage`(同様の意図確認と更新)。

含めるべき観点: unit/contract(分類・gate・guard・predicate)、property(既存property harnessに予約重複fixtureを追加し、target非重複とconservationを確認)、integration/emulator(AC-3)、failure injection(受容source例外時はfail-closedに `CAPTURE_INVALID` 経路へ倒す)、UI(copy表示)、retry挙動(busy loop不在)。

## Documentation updates

- [ ] spec status/history(本spec)
- [ ] ADR-0010新規作成(受容policyのstale検出をstate-based predicateとした理由、revision/digest埋込み不採用の理由、acceptance predicateの配置判断を含む)+ ADR-0008への「superseded in part by ADR-0010」注記
- [ ] `docs/engineering/organizer-diagnostics.md` §5(closed集合17値 + `PRE_WRITE_REJECTED` familyへの `OVERLAP_POLICY_REJECTED` 追記)
- [ ] `docs/assessment/issue-185-qsb-row-interop.md`(AC-3)
- [ ] Launcher3 bridge近傍文書(`LoaderCursor` へのpredicate参照を導入した場合のIssue番号と理由)
- [ ] CONTEXT.md / DESIGN.md: 不要(domain語の派生語のみ、不変条件§5の変更なし)
- [ ] `docs/product/mvp-release-readiness.md` の該当行(FR-002/FR-003のinterop修正として記録するかはPR時に判断)

## Execution checklist

- [ ] ADR-0010をacceptedにする(spec承認後、実装前に判断を確定。stale検出方式とpredicate配置の判断を含む)
- [ ] Current behavior reproduced(既存red test: 5x5 + QSB行itemでCAPTURE_INVALID)
- [ ] Tests fail for the missing behavior(capture Ready→分類→guard→A5 predicate反転 の順でred-first)
- [ ] Minimal implementation completed(capture → validation → materializer → composer gate → A5/recovery predicate → UI)
- [ ] Migration/recovery verified(format不変の確認 + recovery round trip + 受容無効環境でのrecovery拒絶)
- [ ] Full relevant verification completed(unit/instrumentation/spotless/assemble)
- [ ] PR evidence and remaining risks recorded(`risk: layout-data` label → CI `final-status` + 独立audit記録 `docs/assessment/pr-<番号>-<slug>.md`)

## Change history

- 2026-08-31: draft作成(spec.md初版に対応)。
- 2026-09-01: Issue #185 review(Changes requested)対応。(1) Blocking 1: `allowWidgetOverlap` 反転の検出をrevision依存からA5/recovery時のstate-based acceptance predicate再評価へ変更。revision/digest埋込みと「capture値の保存+値比較」をalternatives不採用として記録。(2) Blocking 2: materializer免除をplan時guardに限定し、`preconditionsHold`/`RecoveryWriteSetMaterializer` のexact契約無変更を明記、reviewのtest行列(反転2方向、別ID入替、新規occupant、unrelated revision変更、受容無効のNotReady挙動)をVerificationへ追加。(3) Non-blocking: 共通acceptance predicate(bridge)または等価contract testをAC-9として追加し、ADR-0010への記録を必須化。`PreWriteRejection.OVERLAP_POLICY_REJECTED` とdiagnostics契約§5の `PRE_WRITE_REJECTED` family追記をchange setへ追加。
