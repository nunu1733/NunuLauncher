---
issue: "#479"
status: implemented
tier: M
requirements: []
updated: 2026-10-01
---

# Organizer hubのexchange行がapp barの下に見える位置で決定的に表示される

階層Mの軽量specである（[Risk tiers](../../docs/project/github-workflow.md#risk-tiersリスク階層)）。
Launcher DB書込み、schema migration、recovery store、上流model/loader bridgeを
含む変更にはこの形式を使わない（通常の `spec.md` + `plan.md` を使う）。

Tier M判定: hub入口の表示位置を直すproduction UX fixであり、新しい書込み経路を作らず、
Launcher DB書込み・migration・recovery storeに触れない（**本修正はLauncher DB pathに一切触れない**。
diffはUI表示とtest/CI routingのみ）。実機でのowner確認（録画・TalkBack）は
[owner決定（PR #494 comment）](https://github.com/nunu1733/NunuLauncher/pull/494#issuecomment-5933158467)
により省略し、代替証跡と残余リスクはVerification / Accessibility節のとおり。

## Problem

hub（`OrganizerHubPreferences`）にexchange sessionが有効な状態で入ると、`organizer-hub-request` /
`organizer-hub-proposal` 行がM3 large app barの下に隠れて見えないことがある（~50%、entry時の
measure pass interleave依存）。機序（#479で確定。証跡: branch `issue-479-compose-ghost-node`
commits `2a63ce51ea`/`e7bd9b38a5`/`708ab6bbfb`/`9cafbbed5b`、emulator runs 2026-10-01）:
`organizer-hub-status-checking` 行の除去でLazyListStateが再anchorした直後、後続のmeasure passで
exchange行がindex 0に挿入されてもkey anchoringが `organizer-hub-start` をcontent topに維持し、
挿入行がcontentPadding（app bar高さ分）のgap（y 354-676 / 1440x3120）にlayoutされる。行は
semanticsノードとして画面内boundsを保つため、TalkBackは覆われた行を読み、実touchは当たらない。

この機序によりmanual-organization-ui laneのtouch oracle
（`issue372ConsultationSessionSurvivesARealMaterialsWriteViaTheProductionRoute`）が恒常赤となり、
現在quarantine中（runner argument `nunuQuarantineIssue479TouchOracle=true`）。またtest側も
strategy行clickが `performScrollToNode` + `assertIsDisplayed` disciplineを欠き、
bottom-clipされた行のtapがsystem gesture-nav insetに吸収される別欠陥（Swallow-2）を重ねていた。

## Benchmark

該当課題なし（NFR-014）。編集負担ベンチマーク（B1〜B7）が対象とする編集面
（editing surface）の経路には触れない。hub入口の可視性修正であり、編集操作の追加・削減も
place計画の変更もない。

## Prior art

- b/469669851（Compose既知bug調査）/ https://issuetracker.google.com/issues/469669851 / 2026-10-01 / 不採用 — 本件の機序（contentPadding配下への挿入行配置）とは別件で、修正根拠にならない。
- b/418746335 / https://issuetracker.google.com/issues/418746335 / 2026-10-01 / 不採用（対処不要）— 2.9.2で修正済みで本repoのcompose-bomに既に包含される。本件の再現原因ではない。
- b/187188981（semantics可視性filtering、1.6.0で修正済み）/ https://issuetracker.google.com/issues/187188981 / 2026-10-01 / 不採用 — 挿入行はbounds上「画面内」のためfilter対象にならず、a11y不整合は本修正（可視位置への再anchor）で解消する。
- LazyListStateのkey-anchored scroll position（挿入でfirst-visible itemを維持する仕様）/ https://developer.android.com/develop/ui/compose/lists / 2026-10-01 / 採用 — 挿入行がgapにlayoutされる機序と、`scrollToItem` による明示再anchorの根拠。
- PreferenceScaffold + 非同期行挿入の同種再anchor先例（Lawnchair upstream / material3）: なし（調査済み）— 汎用機構は作らずhubスコープで対応する。

## Outcome

exchange sessionがある状態でhubに入ったとき、request/proposal行がapp barの下に見える位置で
決定的に表示され、実touchでも到達できる。touch oracleをquarantineから復帰させ、
manual-organization-ui laneを恒常greenに戻す。

## Scope

- `lawnchair/src/app/lawnchair/ui/preferences/destinations/OrganizerHubPreferences.kt`（hubスコープ限定）:
  exchange行（`organizer-hub-request` / `organizer-hub-proposal`）が存在し、かつこのリストinstanceで
  ユーザーのdragが未発生（nested scroll connectionの `onPostScroll` で `NestedScrollSource.UserInput`
  かつリストが実消費したdeltaでのみ発火。programmatic scroll（`scrollToItem` / bring-into-view）は
  nested scrollをdispatchしないためguardを発火させない。
  drag guardは `rememberSaveable` でリストのsaveable stateとライフサイクルを揃え、
  recreation後の位置復元でもguardを失わない）、
  かつscroll中でなく、かつ `firstVisibleItemIndex > 0` である場合に限り、
  `scrollToItem(0)` でリスト先頭（index 0）へ再anchorし、行がapp barの下にrenderされるようにする。
  再anchor effectは観測tuple（first-visible index / exchange行の存在 / scroll中フラグ）を
  `snapshotFlow` で監視し、scroll終了（idle遷移）でも再評価する
  （scroll中にskipされた補正がidle遷移で失われない）。
  再anchor先をexchange行のindexではなくindex 0とする根拠: hubリストはentryごとに新規composeされ、
  まだユーザーdragが発生していない。したがってexchange行が存在しdragが未発生の状態で
  anchorが0より後ろにずれているとき、そのずれは常にchecking行除去を契機とするchurn artifactであり、
  exchange行のindexへのsnapを意図した位置調整と区別できない。index 0へのsnapは
  exchange行より上に置かれたdurable status行の可視性も同時に保証する。
  dragが一度でも起きた後は二度と位置を調整しない。sessionなし・anchorが既に0のentryはno-op。
- test側: strategy行clickへrequest行経路と同一の `performScrollToNode` + `assertIsDisplayed`
  disciplineを適用。branch上で追加済みの失敗時diagnostics（#479 census / bounds-timeline /
  await evidence）は保持する。
- quarantine解除: `tools/ci/run-manual-organization-ui-instrumentation.sh` から
  `nunuQuarantineIssue479TouchOracle=true` のrunner argumentを削除、
  `OrganizerDiagnosticsRouteInstrumentationTest.kt` からJUnit `Assume` gateと
  `QUARANTINE_RUNNER_ARGUMENT` 定数を削除、`docs/engineering/ci-test-portfolio.md` の
  manual-organization-ui lane行とheaderを更新する。

## Non-goals

- durable status行の並び順・表示条件の変更（#374/#376契約は不変）。
- 汎用LazyList / `PreferenceLazyColumn` / scaffold機構の変更（再anchorはhubスコープに限定）。
- 上流Composeへのbug report（follow-up issueの起票を推奨。本specの対象外）。
- #473（`moveSlotGapTo` AIOOBE）の断続的timeoutへの対応。
- compose-bom / navigation-composeのversion bump。

## Behavior scenarios

### Scenario: exchange sessionありでhubに入る

Given requestまたはproposalのexchange sessionが有効
When hubに入る（checking行除去とexchange行挿入のmeasure pass順序は任意）
Then `organizer-hub-request`（または `organizer-hub-proposal`）行がapp barの下に見える位置に
layoutされ、実touchで起動できる。挙動はmeasure passのinterleaveに依存しない（決定的）。

### Scenario: ユーザードラッグ後の再配置なし

Given hubリストでユーザーがdragによりscrollした
When その後recomposeによりexchange行の挿入・除去が起きる
Then first-visible位置は一切調整されない（drag記録後は `scrollToItem` を呼ばない）。

### Scenario: sessionなし・anchorが既に正しい（edge / zero-write）

Given exchange sessionが存在しない、またはanchorが既に0で再anchor条件を満たさない
When hubに入る
Then scroll位置は一切変更されず、可視geometryは修正前と同一であり、
Launcher DBへの書込みは発生しない。

### Scenario: quarantine解除後のtouch oracle

Given runner argumentなしでmanual-organization-ui laneを実行
When `issue372ConsultationSessionSurvivesARealMaterialsWriteViaTheProductionRoute` を実行
Then oracleはpassする。失敗時はcensus / bounds-timeline / await evidenceがfail出力に残る。

## Acceptance criteria

- [x] AC-1: exchange sessionありのhub entryで、request/proposal行がapp barの下に見える位置に
      決定的に表示される。oracle = quarantine解除済みtouch test
      （`issue372ConsultationSessionSurvivesARealMaterialsWriteViaTheProductionRoute`）の
      連続pass（local emulator複数回 + PR上のCI lane green）。oracleのtouch clickは
      単一の実touch（`REQUEST_ROW_CLICK_ATTEMPTS = 1`、test側retryなし）であり、
      「最初のユーザーtouchが失われないこと」がassert対象の契約である。
- [x] AC-2: このリストinstanceでのユーザードラッグ後、位置が再調整されないこと
      （drag後の `scrollToItem` 呼び出しがないこと）。drag guardは
      `rememberSaveable` でリストのsaveable state（`rememberLazyListState`）と
      ライフサイクルを揃える。実drag後の位置保全 + saveable state復元後の保全を
      testで検証する
      （`OrganizerHubDragGuardInstrumentationTest.hubUserDragPositionIsNotReanchoredWhileExchangeRowsPresent`。
      実dragはinstrumentationのUiAutomationへの生の
      `injectInputEvent` drag（`UiDevice`は同一プロセスの後続keyboardテストの
      key/focus配信を乱すため不使用 — CI 3実行で再現した汚染の実証済み）、
      saveable復元は
      `StateRestorationTester.emulateSavedInstanceStateRestore()`。pre-fixの
      `remember` guardでは復元後にre-anchorが発火して本テストが失敗することを
      実証済み）。
- [x] AC-3: sessionなし・anchorが既に正しいhub entryで可視geometryが不変（no-op）であり、
      いかなるscenarioでもLauncher DB書込みが発生しないこと。
- [x] AC-4: strategy行clickが `performScrollToNode` + `assertIsDisplayed` disciplineを
      通ること（test diffで確認）。
- [x] AC-5: quarantineが解除されていること（script引数・`Assume` gate・
      `QUARANTINE_RUNNER_ARGUMENT` 定数の削除、`docs/engineering/ci-test-portfolio.md`
      lane行+headerの更新）。`python3 tools/repo-contract/validate_ci_portfolio.py` がpassする。
- [x] AC-6: 失敗path diagnostics（#479 census / bounds-timeline / await evidence）が
      本PRで削除されないこと。

証跡の対応表はPR #494本文を参照。

## Verification

- 本修正についてはowner決定（[PR #494 comment](https://github.com/nunu1733/NunuLauncher/pull/494#issuecomment-5933158467)）
  により実機録画確認を省略し、エミュレータ連続検証（oracle ×6、class、lane ×2）+ CI lane greenを
  代替証跡とする。残余リスク: TalkBack実機確認は未実施。問題が判明した場合はIssue #479のreopenで対応する。
- local emulator: 当該test class単体 + full lane
  （`tools/ci/run-manual-organization-ui-instrumentation.sh`、quarantine引数なし）を複数回実行。
- `./gradlew spotlessCheck`。
- `python3 tools/repo-contract/validate_ci_portfolio.py`。
- PR上でCI lane `organizer-instrumentation-manual-organization-ui-tests` がgreen。
- 書込み経路を追加しないことの確認: diffは上記Scopeの4ファイル、AC-2 focused testを追加した
  `tests/organizer-instrumentation/app/lawnchair/organizer/ui/OrganizerHubPreferencesInstrumentationTest.kt`、
  本specの計6ファイルのみで、DB/preference書込みコード・migrationを含まない。
- 本修正はLauncher3/AOSP由来コードのbridgeに触れない（`app.lawnchair` 自前のCompose UIのみ）ため、
  `measure_upstream_patch_surface.py` の計測reportは対象外。
- quarantine解除はCI test routingの変更に当たるため、実装PRでtest-audit skillを適用し
  recordを作成する。

## Accessibility and localization

- 修正により、覆われた位置に残っていたsemanticsノードが可視rowと一致する位置に再anchorされ、
  TalkBackの読み上げ対象と視覚が一致する（#479で指摘されたa11y不整合の解消）。
- label・文言・font scalingへの影響なし（位置fixのみ）。TalkBack実機確認は
  [owner決定（PR #494 comment）](https://github.com/nunu1733/NunuLauncher/pull/494#issuecomment-5933158467)
  により省略した残余リスクであり、問題判明時はIssue #479のreopenで対応する。

## Change history

- 2026-10-01: Draft created for #479（root cause調査の証跡はbranch `issue-479-compose-ghost-node`、
  emulator runs 2026-10-01）。
- 2026-10-01: Review round 1 (ChatGPT, PR #494) の指摘 [中]×3（flow観測漏れ /
  userDraggedのsaveable非対称 / 2-attempt oracle）を修正。Tier M の実機owner確認は
  エミュレータ連続検証（oracle ×6、lane ×2、CI lane green）とownerのmerge/close指示を
  もって代替するowner決定を記録。
- 2026-10-01: Review round 2 の残条件2点（AC-2 focused test追加、owner決定 permalink との
  契約同期）を解消。AC-2回帰テストをOrganizerHubPreferencesInstrumentationTestに追加。
- 2026-10-01: AC-2 focused testの実証で、`LazyListState.interactionSource` には実dragの
  `DragInteraction` が配信されない環境差（recreation後の組合せで観測）を確認したため、
  guard発火をnested scroll観測（`NestedScrollSource.UserInput` の実消費delta）へ変更。
  programmatic scrollはguardを発火させない仕様は不変。
- 2026-10-01: AC-2回帰テストを確定版へ改定。review round 3の指摘に従い、
  `StateRestorationTester.emulateSavedInstanceStateRestore()` によるsaveable state復元oracleを
  導入（`scenario.recreate()`+`setContent` はharnessがsaveable stateを復元しないため不採用 —
  probeで実証）。実dragの注入はinstrumentation UiAutomationへの生の `injectInputEvent`
  drag（compose-testのtouch relayは負荷下でdragsを落とし、`UiDevice`は
  同一プロセスの後続keyboardテストを汚染するため）。guard破綻（armされない/programmatic scroll誤arm）と
  remember/rememberSaveable非対称の双方を検出することを確認: pre-fix（`remember` guard）では
  復元後にre-anchorが発火してテストが失敗する。
