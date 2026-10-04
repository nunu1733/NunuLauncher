# Implementation Plan: Onboarding proposalのactionを単一touchで活性化できるようにする

> Issue: #137
> Spec: [spec.md](./spec.md)
> Status: draft

## Current evidence

### 確認済み事実

- 再現条件と観測はIssue #137本文のとおり。release/minified APK(`05329be2d7d368a19997f981fb371a54113c7bb0`)、API 36.1 Pixel 6 AVD、proposal表示後にtap→ `focused=true` のみ、keyboard `Enter` で実行。redacted evidenceは [#132 branch](https://github.com/nunu1733/NunuLauncher/tree/codex/issue-132-dogfooding) の `docs/assessment/evidence/issue-132-exploratory-baseline.md`。
- 対象code: `lawnchair/src/app/lawnchair/organizer/ui/OrganizationOnboardingProposal.kt`
  - `actionButton()`(L185-193): 3 action buttonすべてに `isFocusableInTouchMode = true` + `setOnClickListener`。
  - title(L138-144)とpopup root(L276)もfocusableかつFITM。
  - `show()`(L319-322): attach後にtitleへprogrammatic requestFocus(touch mode中の決定的初期focus entry、PR #95監査でのNFR-009対応)。
  - `dispatchKeyEvent`(L338-368): DPAD/TAB進行を手動処理。`Enter` は含まないためsuper経由でfocused buttonのclickが発火する。これは観測された「Enterだけ動く」と完全に整合する。
  - touch活性化のguard: `reviewInFlight`(L388-411)、presentation claim(`OrganizationOnboardingProposalProcessState`)。
- 回帰導入commit: `1e4da2d0a20964551d225b38fd6d8f1312281d61`(2026-08-21)。title・button・rootへFITM追加。
- test gap: `tests/organizer-instrumentation/app/lawnchair/organizer/ui/OnboardingOrganizationProposalInstrumentationTest.kt` はaction実行を `performClick()`(L177, L188, L240-242)とkey eventのみで検証し、実touch gestureを注入していない。
- platform文書化挙動: FITM viewへの最初のtapはclickではなくfocus取得として消費される。upstream Launcher3自身が `src/com/android/launcher3/widget/NavigableAppWidgetHostView.java` L138/L147付近でfocused childから `setFocusableInTouchMode(false)` を剥がす先例を持つ。
- DragLayer pipeline確認済み: `BaseDragLayer.findControllerToHandleTouch` においてpopup自身は `onControllerInterceptTouchEvent=false` override(L370)によりcontrollerにならない。tapでfocus変化が起きている事実から、DOWNは少なくとも1回目はbuttonへ到達している(interception説のうちDOWN段階の遮断は棄却)。UP以降の挙動は未確定。

### 推測(Phase 0で判別)

- H1(有力): action buttonがFITMのため、DOWN毎に「未focusならfocus取得してpressしない」pathへ進む。反復tapでもclick不発が続くにはgesture間にfocus喪失が起きている必要があり、その主体(誰がfocusを奪うか)は未特定。
- H2: gesture途中(MOVE超過等)にdrag layer/touch controllerがinterceptしてCANCEL→pressed解除。input tap程度の移動では稀だが未排除。
- H3: clickは発火しているがhandler内で失敗 → keyboard Enter成功(同一listener経由)と矛盾するためほぼ棄却済み。Phase 0 logで最終確認するだけとする。

### 最小修正の方向(confirmed factsから導出)

3 action buttonからFITMを除去し `isFocusable` を維持する。非FITM clickable buttonは最初のtapで即座にclickする(platform規約)。key入力時点でtouch modeは離脱するため、DPAD traversalにbutton側FITMは不要。touch mode中の決定的初期focus entryは、title(またはroot)のFITMによるprogrammatic requestFocusで維持する。

## Design

### Modules and interfaces

- 変更は `lawnchair/src/app/lawnchair/organizer/ui/OrganizationOnboardingProposal.kt` 内のview構築部分(`OrganizationOnboardingProposalContent.actionButton`、title/root属性)に限定する。
- 外部interfaceは不変: `OrganizationOnboardingProposalController`、`OrganizationOnboardingProposalStore`、`ManualOrganizationRun.start(trigger)` admission seam、route/entry context、outcome preference key。
- test呼び出し側も同一seam(real launcher floating host + FakeStore)を使い、内部実装を直接検証しない。

### Data flow

変化なし。touch → button click → `onLater`/`onSkip`/`beginReview` → 既存controller/store/admission経路。修正は「touch gestureがclickへ変換されるか」の層のみ。

### Alternatives rejected

- proposal表面全体をComposeへ書き換え: #53契約の「小さなlauncher所有surface」を超える変更であり、受入済みaccessibility挙動を再検証させるコストに見合わない。
- popup独自の `dispatchTouchEvent`/`onTouchEvent` でtap判定を実装しclickを手動呼び出し: platform標準click処理の再発明であり、long press/cancel/slop等のedgeを自前で抱える。却下。
- root/title含めFITMを全部除去: touch mode中のprogrammatic requestFocusが黙って失敗し、#95受入済みの決定的初期focus entry(NFR-009)を壊す。entry point 1箇所は保持する。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `OrganizationOnboardingProposal.kt` `actionButton()` | buttonから `isFocusableInTouchMode = true` を除去 | first-tap-focusによるclick消費の直接原因 |
| 同 `OrganizationOnboardingProposalContent` / popup root | titleまたはrootのFITM保持可否をPhase 0所見で決定(原則titleに集約) | touch mode中の決定的初期focus entry維持 |
| `OnboardingOrganizationProposalInstrumentationTest.kt` | 実touch gesture注入regression test追加、`performClick()` ベースのaction検証を実注入ベースへ置換・補強 | performClick依存が本バグの検知不能性の原因 |
| 診断用log(一時的、commit残存可否はPhase 0で判断) | DOWN/UP/CANCEL/focus遷移のevent flow記録 | H1/H2/H3判別 |

Launcher3/AOSP由来source(`src/`)は変更しない。bridge要件は発生しない見込み。

## Migration and recovery

- schema/rule migrationなし。preference key互換性影響なし。
- failure中のrollback: commit revertのみで復元可能。永続stateは既存outcomeキーのまま inert ではないため追加処理不要。
- release rollback/downgrade、backup/restore compatibility: 影響なし(FITM属性はbuild時定数であり、保存stateに依存しない)。

## Execution strategy

1. **Phase 0 — 再現と機構確定**: API 36.1 emulator上でdebug buildのproposalを表示し、実touch stream(`adb shell input tap` およびinstrumentation内motion injection)で不発を再現。DOWN/UP/CANCELとfocus遷移を記録しH1/H2/H3を判別。反復tapの2本目以降にclickが発火するかも併せて記録。
2. **Phase 1 — regression test先行**: spec AC-1相当の実touch注入testを追加し、pre-fix code上でfailすることを確認して結果を残す。
3. **Phase 2 — 最小fix**: `actionButton()` からFITM除去。Phase 0所見に応じtitle/rootのFITM配置を確定。keyboard traversal・初期focus entry・focus restorationの既存assertionを全てgreenで維持。
4. **Phase 3 — 全surface再検証**: Skip/Later/Reviewのtouch活性化、busy retry、recreation、200% font scale、release build(minified)での手動確認を含む検証表を実施。minified release APKでの確認は今回の観測がrelease build由来であるため必須。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | 実touch注入instrumentation(3 action × 1 tap、副作用assert) | API 36.1 emulator、`connectedLawnWithQuickstepGithubDebugAndroidTest` + `-Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.ui.OnboardingOrganizationProposalInstrumentationTest` |
| AC-2 | 既存keyboard/DPAD traversal case維持 + touch→key切替case | 同上 |
| AC-3 | store outcome assert、同process再表示抑制、busy retry | 同上(unit部は `testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`) |
| AC-4 | recreation/process再起動系既存case | 同上 |
| AC-5 | pre-fix fail記録 | Phase 1実施ログ |
| AC-6 | repository gates + CI | `./gradlew spotlessCheck`、`python3 tools/repo-contract/validate_repo_contract.py`(およびself-test)、`assembleLawnWithQuickstepGithubDebug assembleLawnWithQuickstepGithubDebugAndroidTest`、CI `final-status` |

手動evidenceとして、release/minified APKでのtap活性化screen recording(またはscreenshot系列)をPRへ添付する。

### リスク分類

本修正はorganizer onboarding UI表面のみであり、Launcher DB、planner入出力、application protocol、recovery pathに触れない。よって `risk: layout-data` / `risk: migration` には該当しない見込みで、高リスク独立audit要件は適用外と判断する。ただしPR作成時にlabel判断を明示し、適用判断が変わる場合はAGENTS.mdの独立エビデンス要件に従う。

## Documentation updates

- [ ] spec status/history(specを `accepted` → 完了時に `implemented`)
- [ ] CONTEXT.md — 用語変更なし(対象外)
- [ ] DESIGN.md — system structure変更なし(対象外)
- [ ] ADR — 不要見込み(判断が小さく可逆。Phase 0所見でentry point設計が受入済み#95挙動と両立できない判明時に再評価)
- [ ] AGENTS.md — command変更なし(対象外)

## Execution checklist

- [ ] Current behavior reproduced(API 36.1、実touch stream)。
- [ ] Tests fail for the missing behavior(pre-fix fail記録)。
- [ ] Minimal implementation completed。
- [ ] Migration/recovery verified(該当なしを明示)。
- [ ] Full relevant verification completed(unit/instrumentation/release build/formatting/CI)。
- [ ] PR evidence and remaining risks recorded(command結果、touch活性化の録画/画像、H1-H3判別結果)。
