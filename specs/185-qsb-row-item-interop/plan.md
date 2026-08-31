# Implementation Plan: QSB予約行に存在するitemをorganizerが扱えるinterop意味論

> Issue: [#185](https://github.com/nunu1733/NunuLauncher/issues/185)
> Spec: [spec.md](./spec.md)
> Decision: [ADR-0010](../../docs/adr/0010-qsb-row-item-overlap-interop.md)(本planの実装前にacceptedが必要)
> Status: draft
> Baseline: `ac09d27bae` (`main`, 2026-08-31)

## Current evidence

### 確認済みの事実

- capture失敗のthrow箇所は `RowManifestCodec.validateReservations()` の `require(...none { overlap... }) { "Workspace item overlaps a platform reservation" }`(`lawnchair/src/app/lawnchair/organizer/application/adapter/RowManifestCodec.kt:167-180`)。#172 assessmentでrow 115が `screen=0 cell(2,0)` に実在することをDB取得で確認済み。
- 予約geometryは `LauncherLayoutAdapter.captureWorkspaceContext()`(`LauncherLayoutAdapter.kt:149-165`)が `GridCell(0,0) × GridSpan(idp.numSearchContainerColumns, 1)` を生成する。
- `LoaderCursor.checkItemPlacement`(`src/com/android/launcher3/model/LoaderCursor.java:569-595`)は同一の `numSearchContainerColumns × 1` をload時にmarkし、重複itemの存続を `PreferenceManager2.allowWidgetOverlap`(既定false、`lawnchair/res/values/config.xml:116`)に委ねる。capture側の予約geometryはloaderと一致しており、狭小化の余地はない。
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
| `OrganizationPlanMaterializer.overlapsReservation`(`OrganizationPlanMaterializer.kt:58-62`) | 全targetの予約重複を `Invalid` | captured placementと同一のtargetは免除 |

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

1. capture: 予約重複行を含む `favorites` をlosslessに読む(throwしない)。`LayoutState`/manifest/revisionはshape不変。
2. compose: capture → unknown lock → mapLayout → **[新] 予約重複item存在 && !tolerant → `NotReady(CAPTURE_RESERVED_OVERLAP)`**(受容sourceは1回だけ読む)→ 既存のbundle/override/evidence flowへ続く。
3. plan: TargetSet role と placement disposition の両方で `RESERVED_REGION` precedence が適用される。plannerは予約セルを初期occupancyとしてmarkし、movable targetを予約外へ割当てる(既存どおり)。
4. materialize/apply: preserved itemのrowはcaptured値のままupdate(実質無変更)。correlated reload後、受容有効ならloaderは当該itemを保持し、A7 recaptureがintendedと一致する。
5. recovery: pre-state manifestは予約重複行を含めて復元される(既存契約どおり)。受容有効環境では復元後のreloadも当該itemを保持するため、検証が通る。

### Alternatives rejected

- **予約の狭小化**: 5x5でno-op + loaderとのgeometry不一致で#155を再発させる(spec Decision節参照)。
- **import時修復**: 既存workspaceを救済できず、import fidelityに反する。
- **予約重複itemをMovableとして移動させる**: planner target生成・UI copy・#83分類契約への影響が大きく、本bug fixの射程を超える。ADR-0010のalternativesに記録。
- **受容状態を `LayoutState` / context resourceに埋め込む**: revision/digest/recovery format互換性に波及する。capture時のinjected source読取で十分であり、format変更は回避する(pref反転は既存のreload→revision変化→stale機構で守られる)。
- **受容gateをcapture内のthrowで実装する**: #172のdebug log行(例外class名)が発火し、composer理由がblanket `CAPTURE_INVALID` に戻る。狭いtyped理由の要件に反する。

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
| ADR-0010 / ADR-0008注記 | 決定記録 | AGENTS.mdのADR条件(表現可能状態の再定義は高コスト判断) |

## Migration and recovery

- schema/format migrationはnone(recovery v2、journal schemaVersion 1、backup format不変)。
- downgrade: 新理由コードはjournal上のString定数であり、旧buildは未知codeを `UNMAPPED` へ落とすか、新enum値を含むjournalのdecode失敗時は#172規定のcorruption isolationに従う。新規riskはなし。
- rollback: code revertのみで既存挙動に戻る。data移行物なし。
- 失敗経路: 受容無効時はwrite前にtyped拒絶(recovery point作成前)であり、中途半端な適用は生じない。

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

既存testの更新が必要な箇所: `DeterministicOrganizationPlannerTest.reservationOverlapAndUnknownPageAreRejected`(item↔予約重複はpreservedへ、不明page/予約↔予約は拒絶のままへ分割)、`RealAdapterRowMatrixInstrumentationTest.reservationOverlapIsRejectedOnlyWhenRegionsShareAPage`(同様の意図確認と更新)。

含めるべき観点: unit/contract(分類・gate・guard)、property(既存property harnessに予約重複fixtureを追加し、target非重複とconservationを確認)、integration/emulator(AC-3)、failure injection(受容source例外時はfail-closedに `CAPTURE_INVALID` 経路へ倒す)、UI(copy表示)。

## Documentation updates

- [ ] spec status/history(本spec)
- [ ] ADR-0010新規作成 + ADR-0008への「superseded in part by ADR-0010」注記
- [ ] `docs/engineering/organizer-diagnostics.md` §5(closed集合17値)
- [ ] `docs/assessment/issue-185-qsb-row-interop.md`(AC-3)
- [ ] CONTEXT.md / DESIGN.md: 不要(domain語の派生語のみ、不変条件§5の変更なし)
- [ ] `docs/product/mvp-release-readiness.md` の該当行(FR-002/FR-003のinterop修正として記録するかはPR時に判断)

## Execution checklist

- [ ] ADR-0010をacceptedにする(spec承認後、実装前に判断を確定)
- [ ] Current behavior reproduced(既存red test: 5x5 + QSB行itemでCAPTURE_INVALID)
- [ ] Tests fail for the missing behavior(capture Ready→分類→guard の順でred-first)
- [ ] Minimal implementation completed(capture → validation → materializer → composer gate → UI)
- [ ] Migration/recovery verified(format不変の確認 + recovery round trip)
- [ ] Full relevant verification completed(unit/instrumentation/spotless/assemble)
- [ ] PR evidence and remaining risks recorded(`risk: layout-data` label → CI `final-status` + 独立audit記録 `docs/assessment/pr-<番号>-<slug>.md`)
