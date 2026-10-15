---
issue: "#554"
status: accepted
requirements: []
updated: 2026-10-08
---

# API 37 provider環境でRecents callbackを正しく受信する

Risk tier: H。#545と同じprovider bind/transition境界のvendored upstream対応として保守的に扱う。
DB、model/loader、高リスクpathには触れず、layout-data/migration audit gateの対象ではない。

## Problem / Outcome

API 37.0 release priv-app＋release RRO provider構成で、Home visibility通知に未読8 bytes、
Recents開始通知にBundle alignment例外が発生し、gesture overview遷移が完了しない。
#545の7件のcompat修正を前提に、shell→launcher overview→既存appの遷移を成立させ、
G3（recents PendingIntent creator mode）のBAL判断を実測可能にする。

## Scope / Non-goals

- 受信callbackのAPI 37 wire schema差分を、既存listenerへ正規化する最小bridgeで扱う。
- API 36の既存schemaとNothing OS 4の既存経路を保持する。
- thumbnail生成（#555）、QUICKSTEP_MAX_SDK引き上げ（#545）、anchor刷新、
  新permission/dependency、DB変更、desktop/keyguardの新UI意味論は対象外。
- implementationは#550 head上のstack。ADR-0018 Decision 3/8のcutover保留を維持する。
  spec/planはmain向けdocs-only PR。実装PRのbase/main統合とIssue closeはcutover契約に従う。

## Prior art

- AOSP Android 17 runner / [pinned source](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/libs/WindowManager/Shell/src/com/android/wm/shell/recents/IRecentsAnimationRunner.aidl) / 2026-10-08 / transaction 3のonAnimationStartからminimizedHomeBoundsが削除。既存listenerにはnullとして渡す。
- AOSP Android 17 home listener / [pinned source](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/libs/WindowManager/Shell/shared/src/com/android/wm/shell/shared/IHomeTransitionListener.aidl) / 2026-10-08 / transaction 1はisVisible/keyguardGoingAwayOrWaking/behindDesktopの3 boolean。追加2値を読み、既存visibility契約はisVisibleで更新する。
- Lawnchair [upstream](https://github.com/LawnchairLauncher/lawnchair/blob/4793ba3a238bb1a3e786a3471a6d84767ed05267/quickstep/src/com/android/quickstep/SystemUiProxy.kt) / 2026-10-08 / 16-dev継続headでも同runnerの旧schemaを保持。全surface capture/anchor刷新は原因2件に対し不要であり不採用。
- API 37.0 emulator fingerprint `google/sdk_gphone64_arm64/emu64a:17/CE2A.260420.019/15611780:userdebug/dev-keys` のSystemUI DEX runner proxyも上記引数順とtransaction 3を実測確認。artifactはassessmentへ固定する。

## Behavior scenarios

1. API 37 providerのRecents開始callbackはcontroller、apps、wallpapers、homeContentInsets、
   extras、TransitionInfoの順に読み、全消費を検証後、既存listenerへminimizedHomeBounds=nullで渡す。
   cancel/tasksAppearedやその他transactionは既存Stubへ委譲する。
2. API 37 Home visibility通知は3 booleanを全消費後、既存onHomeVisibilityChanged(isVisible)へ渡す。
   既存MAIN_EXECUTOR配送とInsets通知を維持する。追加2値の新意味論は実装しない。
3. API 36は既存生成Stub、Nothing OS 4は既存専用decoderを使用する。
4. 不正descriptor、切れたpayload、余剰dataはBinder/Parcel例外で拒否し、listenerを呼ばない。
   enforceNoDataAvailを削除しない。例外を成功扱いで握り潰さない。

## Data / Permissions / Accessibility

新しい永続状態、permission、通信、sensitive data収集、UI文言はなし。
ホームlayout、migration、recovery、localizationの契約を変更しない。

## Acceptance criteria

- [ ] AC-1: API 37 release providerで新規ジェスチャーoverview windowのBadParcelableException（not fully consumed）とBundle length is not aligned例外が0件。
- [ ] AC-2: API 37 release providerでapp→gesture overview→task card選択→同appのforeground復帰が成立。開始callback到達、overview UI、遷移後top activityを証跡化。
- [ ] AC-3: AC-2の遷移windowからG3のBAL block有無を確定し、PI creator modeの判断を#545へ引き渡す。BAL有無の確定が成果であり、mode変更はscope外。
- [ ] AC-4: API 36 providerでの overview/task切替成立のruntime再確認は本Issueから外し、#559へ追跡する。本PRの検証対象は「554 diffがAPI 36の provider受信pathに回帰を生まない」であり、headとaccepted baseの同一profile対照（launcher FATAL 0・受信decoder arrow例外0）で確認する。Nothing OS専用decoderの保持はdiff reviewで確認（実機未所持を明記）。
- [ ] AC-5: exact実行SHA、APK hash、provider preflight、操作、log、overview/遷移後screenshotをassessmentに固定。spotlessCheck、debug/release build、repo-contract、CIを記録。

## Test oracle / test-audit authoring gate

Primary ownerは実API 37 SystemUI→本アプリBinder callbackである。pre-fix release
`da15f4b2e7`（#545 candidate APK）で2026-10-08再現済み。fresh boot後Settings→bottom swipeで
両parcel例外とShellRecents startを観測した。最初のunread8はR8 mappingで
IHomeTransitionListener.Stub、Bundle例外はRecentsAnimationListenerStubへ帰属する。

既存JVM/CI API36 coverageは実API37 sender schemaとprovider権限を再現できない。
診断用ADB runtime oracleを保存し、gesture pathと各signature、overview UI、task復帰を確認する。
新test用production export/seam、永続PR laneは作らない。classificationはDiagnostic。
影響surfaceはQuickstep/provider callback、既存CIでは未mapping sourceとして既存全source gateを起動。
lower-layer source grepや生成Stubの自己roundtripは独立したwire互換証拠としない。
不正payloadはコードreviewで全消費/descriptor検証とdispatch順を確認し、未実行と明記する。

release実行はmaxSdk36のままだと互換gateに遮られるため、#545同様に37の一時candidateを
独立commitでbuild/検証し、その後revertする。candidateでのpassをadvertised support拡張とは扱わない。
thumbnail黒fallbackは#555の既知未達として区別する。
`No matching remote found to takeover` は原Issueの補助signature。単独でAC-1違反とは扱わず、
正常transitionでも残る場合はShell側の該当branchとAC-2成立の両方を記録して判断する。

## Open questions

製品判断なし。cutover前は実装stackを保持し、mainline完了を宣言しない。

## Change history

- 2026-10-08: #554の再現とAOSP/schema比較にもとづくproposed仕様。Issueのclose指示は実装・検証・pushを包含するが、cutover契約の解除を暗黙には行わない。

- 2026-10-08: 別Review sessionの[Approve](https://github.com/nunu1733/NunuLauncher/pull/556)（head `d56bb6026515e696f89cd9be51a67d6b312f8b96`）を確認。利用者の#554対応指示の範囲で受入。cutover保留は維持。

- 2026-10-08: Owner decision（[PR #557 review 6052316052](https://github.com/nunu1733/NunuLauncher/pull/557#issuecomment-6052316052) の「高」への対応）。AC-4のruntime overview/card成立確認を#559（emulator環境state driftでme-legのPASS artifact `candidate2-debug-2987e52`でも不成立が再現）へ分離し、本Issueの検証対象を「554 diffによるAPI 36 provider受信path回帰なし（head/accepted base同一profile対照）」へ縮小する。parcel/FATAL回帰チェックとNothing OS diff review確認は本AC内に維持。判定素材: 3 artifact対照（`85dea91`/`0c25041`/昨PASS artifact）全て同型不成立＋受信decoder arrow例外0。
