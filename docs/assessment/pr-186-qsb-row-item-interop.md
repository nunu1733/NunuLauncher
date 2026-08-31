# High-risk audit: PR #186 QSB-row item interop — preserved projection + overlap acceptance predicate (Issue #185)

> Status: rejected (再監査必須 — 下記 Findings F1/F2 と CI merge gate 条件を参照)
> Audit date: 2026-08-31
> Verdict: **REJECT**

- Auditor: 独立ZCode agent session（読み取り監査 + 検証commandの独立再実行。実装を行ったagent/sessionとは別作業。コードは未変更、本記録のみ新規作成）
- PR: https://github.com/nunu1733/NunuLauncher/pull/186
- Head SHA: 8f649e1e4c49602d606be69100c897995737a68b
- Head SHA検証: 上記は `git rev-parse HEAD`（branch `issue-185-implementation`、working tree clean）と一致し、PR #186のheadと同一であることを監査sessionで確認した
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/33434045588
- CI run補足: 上記はPR #186のpull_request merge-gate run（organizer-unit-tests / check-style / validate-repo-contract / build-debug-apk / instrumentation lanes実行）。監査時点での各jobの合否と、`final-status` がFAILUREである事実は Executed test surface 節に記録するとおり。CI完了（再実行含め全lane成功）時点で本記録のCI evidenceの再確認が必要
- Criteria: specs/185-qsb-row-item-interop/spec.md AC-1 AC-2 AC-3 AC-4 AC-5 AC-6 AC-7 AC-8 AC-9、docs/adr/0010-qsb-row-item-overlap-interop.md ADR-0010、docs/adr/0008-qsb-reservation-context-and-recovery-compatibility.md ADR-0008、specs/13-safe-layout-application/spec.md FR-004 FR-005（`PreWriteRejection` closed集合・A5）、docs/engineering/organizer-diagnostics.md §5/§7

## Scope

監査対象diffは `git diff main...8f649e1e4c`（32 files、+1228/−45、commits `1ec7452f2a`→`58f9fa8f2f`→`8f649e1e4c`）。

変更内容: `RowManifestCodec.validateReservations` の item↔予約重複require撤去、`PreserveReason.RESERVED_REGION` 追加と分類precedence 2 site（`FullTargetSetMaterializer` / `PlanningPlacement.determinePreservation`）、`PlanningValidation.checkOverlap` の item↔予約拒絶のみ撤去、`OrganizationPlanMaterializer` のその場保存免除、composer受容gate（`WorkspaceOverlapToleranceSource` 注入 + `CAPTURE_RESERVED_OVERLAP`）、共通predicate `ReservationOverlapAcceptance`、A5/recovery transaction内の受容再評価 gate（`PreWriteRejection.OVERLAP_POLICY_REJECTED`）、production wiring（`ProductionOrganizationInputComposer` → `PreferenceWorkspaceOverlapToleranceSource`）、en/ja copy、diagnostics契約§5・spec 13 closed集合・ADR-0010新規/ADR-0008注記、unit/instrumentation test追加。

runtime書き込み経路とmigration対象: Launcher DBへの書き込み自体は `LauncherLayoutAdapter.applyWriteSet` のtransaction構造内（A5 gateはwrite前・commit前）で変更なし。schema/format migrationはnone（recovery v2・journal schemaVersion 1・revision/digest計算は不変。`LayoutState`/`PersistenceManifest`/context resourceのshape変更なし）。`PreferenceWorkspaceOverlapToleranceSource` は既存preferenceの読み取り専用で、policy値はdigest/revision/recovery recordに不埋め込み（ADR-0010 §3どおり）。

## Criteria check

**AC-1（決定の記録）— 適合。** ADR-0010がacceptedで作成され、採用候補（preserved射影+state-based predicate）、却下候補（予約狭小化、import時修復、revision/digest埋込み、capture時値保存、LoaderCursor bridge不採用とcontract test採用理由）が記録されている。ADR-0008 Change historyに「real item overlap は typed non-write failure」帰結の部分置換が注記され、invalid reservation geometry不変が明記されている。spec Decision節と矛盾なし。

**AC-2（再現workspace形状の回帰）— 適合。** (a) capture: `RowManifestCodec` の重複requireのみ撤去され、予約自身の不正（重複/不明page/非正span/範囲外/予約↔予約）のfail-closedは `validateReservations` に残存を確認（`RowManifestCodec.kt:132-170`）。instrumentation `qsbRowItemCapturesLosslesslyAndComposesPerPlatformTolerance` が実codec+実DB行（row 115、`screen=0 cell(2,0)`、5x5、`GridSpan(5,1)`予約）でlossless captureとReady/NotReady両分岐を検証。(b) 分類: `FullTargetSetMaterializer` の precedence (0) `Preserved`（`FullTargetSetMaterializer.kt:26-35`）と `PlanningPlacement.determinePreservation` の precedence (0) `RESERVED_REGION`（`PlanningPlacement.kt:474-487`）の両siteを確認。productionでのmaterializer呼出はcomposerのみで予約listを引数渡し（`OrganizationInputComposer.kt:245`）。occupancyは予約mark（`PlanningPlacement.kt:36-37`）に続きpreserved itemの全cellをmark（同41-45）するため、予約からはみ出したspan部分もmovable割当から保護される（plan推論どおり変更不要）。unit test: composer分類・materializer role割当・planner disposition（`reservationOverlapIsPreservedInPlaceInsteadOfRejected`）。(c) 適用行不変+A7一致: on-device assessment（下記AC-3）+ instrumentation 16/16。

**AC-3（#172再現手順の解消）— 適合。** `docs/assessment/issue-185-qsb-row-interop.md` に手順（pm clear → allowWidgetOverlap ON → row 115直接挿入 → force-stop/relaunchで生存確認 → Review organization）、名前付き結果（`RUN_STARTED → CAPTURED → PLANNED captured=1 moved=0 preserved=1`）、journal証拠（`preservedByReason:{"RESERVED_REGION":1}`、runId、schemaVersion 1）が記録され、#172の恒久 `CAPTURE_INVALID` が解消されている。受容無効分岐はloader自己修復のためon-device到達不能で、focused instrumentationで証明——spec test oracleの「同等のfocused instrumentationで証明」に合致。

**AC-4（予約不変条件の非弱化）— 適合。** `PlanningValidation.checkOverlap` はitem↔item重複（`PlanningValidation.kt:250-258`）と予約↔予約重複（同268-279）の拒絶を維持。`OrganizationPlanMaterializer` の免除は `targetPreservesCapturedWorkspace`（targetのpage/cell/spanがcaptured workspace placementと完全一致、かつcapturedがWorkspace型）に限られ（`OrganizationPlanMaterializer.kt:176-190`）、予約セルへの移動は `Invalid` のまま（negative test `movedTargetIntoReservationStillInvalid`）。newFoldersの予約guardは無変更。planner negative test（予約↔予約、不明page）は分割後も在存。recovery契約（`preconditionsHold` のexact一致、`RecoveryWriteSetMaterializer`）は無変更（diff対象外）。

**AC-5（受容gate）— 適合（軽微な注記付き）。** composerはmapLayout後・bundle読取前に `reservationOverlaps > 0 && !tolerant` で `NotReady(InvalidCanonicalCapture(RESERVED_OVERLAP))` + `CAPTURE_RESERVED_OVERLAP` を返す（`OrganizationInputComposer.kt:131-150`）。sourceは重複がある時のみ1回読む（重複なしでは読まない=「受容sourceの値は挙動に影響しない」に合致）。composerはAndroid stateを直接読まない（injected source）。unit test 3件+instrumentation両分岐で組合せ行列をカバー。注記: 新codeのretry挙動（自動retry/busy loopなし）は既存NotReady経路（#172のterminal record + user-initiated retryのみ）をそのまま通るため専用testは追加されていない。意味論は既存機構で保証されており許容範囲だが、新code固有のtestはない。

**AC-6（既存表面の無回帰）— 適合。** `InputCompositionCode` は17値に拡張され、`ModelValidationTest.errorEntryAllowsEveryInputCompositionCode` は `entries` 反復のため自動で全17値を検証。diagnostics契約§5は17値+`PRE_WRITE_REJECTION` family追記へ更新済み。`ApplyResultContractTest` のclosed集合に `OVERLAP_POLICY_REJECTED` 追加。`ContractShapeTest` の `PreserveReason` 数 9→10。既存planner testの分割（item↔予約はpreservedへ、予約↔予約/不明pageは拒絶維持）はspec意図どおり。監査session再実行でorganizer suite 800 tests / 0 failures、spotless/assemble成功（下記）。

**AC-7（copy）— 適合。** `manual_organization_preserved_reserved_region` がen/ja両string resourceに存在し、`preservedReasonString` の網羅whenに分岐追加（`ManualOrganizationPreferences.kt:464`）。

**AC-8（受容policyのstalenessとrecovery安全）— 不適合（Findings F1/F2参照）。** A5/recovery transaction内で現在値を再読する構造自体は実装されている（`LauncherLayoutAdapter.kt:314-323`、write前・commit前、`tx.close()` でno-write）。しかしpredicateへの入力が **再読した現在状態 `before.manifest.rows`** であり、spec/plan/ADR-0010/PR本文が要求する **intended state（通常applyのintended manifest、recoveryではrecovery target manifest）ではない**。通常applyでは両集合が一致するため実害はないが（`preconditionsHold` が現在=sourceを強制、applyに削除なし、materializer guardで新規重複target不可）、recovery write setでは現在状態が重複行を欠きrecovery target（pre-state）が重複行を含む状態到達可能で、その場合gateを通過し **削除済み重複itemを復活させるwriteが実行される**（詳細はF1）。加えてAC-8 test oracleが要求するadapter/seam test（反転→typed no-write、recovery fixture no-write/point保持）が一切追加されていない（F2）。

**AC-9（acceptance一致保証）— 不適合（Findings F3参照）。** ADR-0010 §4はbridge不採用と引き換えに「organizerのpredicateと `LoaderCursor.checkItemPlacement` のQSB行重複acceptance ruleが同一の入力で同じ結果を返す等価contract testを**必須**」とする。本PRにそのようなtestは存在しない（`ReservationOverlapAcceptance` を参照するtestはなし。`LoaderCursorTest.java` は無変更でQSB/search container重複分岐のtestを含ない）。PR本文の「AC-9 … ✅」は事実と不一致。

## Executed test surface

監査sessionで実際に実行したcommandと結果（head `8f649e1e4c`、JDK 21 / Gradle 9.3.0）:

- `git rev-parse HEAD` → `8f649e1e4c49602d606be69100c897995737a68b`（PR #186 headと一致）。`git status --porcelain` → 空。
- `./gradlew spotlessCheck` → **BUILD SUCCESSFUL**
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` → **BUILD SUCCESSFUL**。XML集計（`build/test-results/testLawnWithQuickstepGithubDebugUnitTest/*.xml`、67 suites）: **800 tests / 0 failures / 0 errors / 0 skipped**（PR本文の797は本監査時点の集計と3件差。追加test計上の差異と推定されるが、成否に影響なし）
- `./gradlew assembleLawnWithQuickstepGithubDebug` → **BUILD SUCCESSFUL**

CI（PR #186、監査時点 2026-08-31 20:15-20:20 UTC頃）:

- merge-gate run https://github.com/nunu1733/NunuLauncher/actions/runs/33434045588: `changes` / `validate-repo-contract` / `check-style` / `organizer-unit-tests` / `build-debug-apk` / instrumentation lanes（shared-writer、db-migration、api35、issue52、issue155、issue53）= **SUCCESS**。ただし `organizer-instrumentation-issue99-tests` = **FAILURE**（https://github.com/nunu1733/NunuLauncher/actions/runs/33434045588/job/99626157400 ）。失敗testは `CategoryOverridePreferencesInstrumentationTest > targetUnavailableReturnsToFreshDestinationAndRestoresFocus` の `ComposeTimeoutException`（5000ms）で、同一runにadb daemon接続失敗・emulator console起動失敗のログが先行しており、本PRの変更領域（organizer layout/planning/adapter）と無関係のUI test。flaky/infra起因の可能性が高い（同一laneは直近main run https://github.com/nunu1733/NunuLauncher/actions/runs/33388993173 でSUCCESS）。しかし **監査対象headでは `final-status` はFAILURE**（https://github.com/nunu1733/NunuLauncher/actions/runs/33434045588/job/99629019361 ）。AGENTSの「検証対象commit上でCI merge gate (final-status) が実際に成功していること」を現時点で満たさない。
- high-risk gate run https://github.com/nunu1733/NunuLauncher/actions/runs/33434065618: `high-risk-evidence` = **FAILURE**、理由は「no docs/assessment/pr-186-<slug>.md audit record」のみ（本記録のdocs-only commitで解消される想定の手順どおり）。

instrumentation test（実装agent実行・記録済み、本監査では再実行しない）: `ProductionOrganizationInputInstrumentationTest` 16/16、`RealAdapterRowMatrix` + `ProductionPublicSeam` + `Sanitizer` 12/12（emulator `nunu_qpr2_api36_1`）。

## Findings

判定REJECTの根拠は以下のBlocking指摘（F1/F2/F3）と、監査対象headでのCI merge gate `final-status` FAILUREである。軽微指摘は非blocking。

### F1（Blocking）— A5/recovery gateがintended stateでなく現在状態を評価する。recovery targetの重複行が検出されない

`LauncherLayoutAdapter.applyWriteSet` のgateは `overlapAcceptanceHolds(before.manifest.rows, ...)` を呼ぶ（`LauncherLayoutAdapter.kt:314-323`）。`before` はtransaction内再読の**現在DB状態**であり、`writeSet.intendedManifest`（通常applyではintended manifest、recoveryでは `recovery.targetManifest` = pre-state。`LauncherLayoutAdapter.kt:285`）ではない。

- 通常apply: `preconditionsHold` が現在=plan sourceをexactに強制し、applyに削除がなく、materializer guardで新規重複targetが生成されないため、「現在に重複行あり」と「intendedに重複行あり」は同値。**この経路は仕様どおりに機能する**（compose後反転→loader未削除→revision不変の窓でOVERLAP_POLICY_REJECTED）。
- recovery（automaticRecovery `ApplyProtocol.kt:431` / user-initiated `RecoveryProtocol.kt:153`）: recoveryは定義上 現在≠target を復元する機構で、`RecoveryAction.InsertRow` でtargetのみの行を再挿入する。**現在状態から重複行がloaderに削除済み・recovery targetは重複行を含む・policy無効** の状態で、gateは通過しwriteがcommitされる。続く相関reloadでloaderが当該行を再削除するため `db.manifest != stored.preManifest` → `RestoreFailed(VERIFICATION_FAILED)`（`RecoveryProtocol.kt:200-208`）となり、**layoutは部分的に復元された状態へ変更され、削除済みitemが一時的に復活する**。spec Scenario「受容無効環境でrecovery targetが予約重複行を含む」（「typed failureでno-write」「削除済みitemの復活…も行われない」「Launcher layoutは変更されない」）、AC-8本文（「intended stateが予約重複desktop rowを含む場合は現在の受容が必須」）、plan Data flow step 6（「recovery targetに予約重複行が含まれる場合はA5と同一のacceptance predicateで再評価」）、およびPR本文の主張（"the intended state (apply write set or recovery target) is re-evaluated"）のいずれとも一致しない。

修正案は小さい: gate入力を `writeSet.intendedManifest.rows` にする（通常applyでは同値集合、recoveryではtargetを正しく評価）。recovery時の予約listはcontext一致検証（`ContextResourceCodec.recoveryContextsMatch` / `ContextMismatch`）により現在=targetで不変のため `before.layoutState.reservedWorkspaceRegions` のまま可。

### F2（Blocking）— AC-8のseam testが未追加

`OverlapAcceptanceGateTest` は純関数 `overlapAcceptanceHolds` のmatrixのみで、**adapter seam（applyWriteSet/recovery）を通したtestが存在しない**。どのrowsがpredicateに渡るかを固定しないためF1を検出できず、spec test oracle AC-8の要求（(a) 計算後の無効化→typed no-write、(d) recovery targetが重複行を含み受容無効→no-write/recovery point保持、(e) 同一座標・別ID入替→exact precondition不一致 のadapter seam確認）を満たさない。`OVERLAP_POLICY_REJECTED` を検証するtestはclosed集合列挙（`ApplyResultContractTest`）のみ。さらに `docs/assessment/issue-185-qsb-row-interop.md` のAC-8根拠は「反転はreloadGrid→revision変化→stale pathでtyped失敗」に依存しており、これはspec Blocking 1が明示的に依存してはならないと定めた未保証仮定（LayoutState不変ならrevision不変であり得る）そのものである。

### F3（Blocking）— AC-9の等価contract testが存在しない

ADR-0010 §4はLoaderCursor bridge不採用の代替としてorganizer predicate↔`LoaderCursor.checkItemPlacement` QSB重複acceptance ruleの等価contract testを必須化したが、本PRに該当testはない（`LoaderCursorTest.java` 無変更、`ReservationOverlapAcceptance` をLoaderCursor意味論と突き合わせるtestなし）。drift検知機構が欠落しており、AC-9不成立。PR本文「AC-9 … ✅」の訂正も必要。

### 軽微（非blocking）

- `DefaultOrganizationInputComposer` / `LauncherLayoutAdapter` の受容source defaultが `{ true }`（tolerant）。production wiring（`LayoutApplicationModule.kt:126` → `ProductionOrganizationInputComposer`、adapter default引数が `PreferenceWorkspaceOverlapToleranceSource`）は確認済みで現状の漏れはないが、将来のproduction呼出側がwireを忘れるとgateが黙って無効化される。デフォルトをfail-closed側にするか、production構成testで固定することを推奨。
- `RESERVED_REGION` のprecedenceは `LOCKED` より前（spec「全既存reasonに先行」どおり）。lockedかつ重複のitemの件数表示がLOCKEDでなくRESERVED_REGIONに寄る点は表示上の差異のみで振る舞いは同一。
- 新規codeのprivacyは適合: package名・座標・item列挙・raw messageをjournal/logcatへ出す経路なし（gateはtyped enum codeのみ、`notReady` はdigest非付与、§7 Never分類に抵触する出力なし）。

### 判定根拠

REJECT: (1) F1はrisk: layout-data変更の中心安全機構（Blocking 1/2対応としてspecが追加したrecovery受容再評価）がspecの明示シナリオどおりに動作しない仕様適合性の欠陥で、失敗時にlayoutを無変更に戻す契約を破る。(2) F2/F3は受入条件AC-8/AC-9のtest証拠欠落。(3) 監査対象headでCI merge gate `final-status` がFAILURE（issue99 lane。flaky疑いだがAGENTSは同一commitでの成功を要求）。実装の大部分（capture撤去の局所性、分類precedence、plan時guard、composer gate、wiring、privacy、docs/ADR）はspec/ADRと整合しており、F1の修正は1行レベル・F2/F3はtest追加レベルであるため、修正+docs-onlyでないcode変更後の**再監査**と、そのheadでの `final-status` SUCCESS確認を条件に再提出されたい。
