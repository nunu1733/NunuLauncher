---
issue: "#565"
status: accepted
tier: M
requirements: []
updated: 2026-10-10
---

# Launcher cold startでのfontManager lazy循環待ちデッドロック除去

階層Mの軽量specである（[Risk tiers](../../docs/project/github-workflow.md#risk-tiersリスク階層)）。
判定: Risk tier **M**。変更pathは `lawnchair/src/app/lawnchair/LawnchairLayoutFactory.kt`（fork code）と
instrumentation test・CI routingのみ。Launcher DBへの新しい書込み経路、schema migration、recovery store、
上流model/loader bridge、高リスクpath一覧（`tools/repo-contract/validate_high_risk_evidence.py`）への該当はない。
plan.mdは階層Mでは要求しない。

## Bug oracle（正本）

- repository追跡oracle: `docs/assessment/563-home-empty-overview-evidence/anr/cold-start-deadlock-anr-trace.txt`
  exact commit `b043c85be580847422c4b4057443cdf64e3e2830`（PR #566でmainへ導入、base HEAD `0c737bd45c` にも存在）。
  システムdropbox `data_app_anr` 由来の実trace excerpt: `ViewPool-init` tid=32が
  `kotlin.SynchronizedLazyImpl` monitor `<0x051a0dbc>` を `locked`（`LawnchairLayoutFactory.getFontManager` 経由の
  `MainThreadInitializedObject.get` でfutureへ `park`）したまま、`main` tid=1がinflate中に同じmonitorへ
  `waiting to lock` で `Blocked`。tid=33の別ViewPool-initも同monitorでblock。
- Issue本文（owner作成・受入条件記載）: <https://github.com/nunu1733/NunuLauncher/issues/565> を
  2026-10-10T12:45Z に取得。修正方式はspecで確定すると記載されているため、本specが修正方式の決定を兼ねる。
  再現率（restartあたり約1/2、17分間にANR1回+wedge3回）と観測環境（emulator-5556 / AVD `issue142_api36` /
  API 36 / fingerprint `google/sdk_gphone64_arm64/emu64a:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys`）は
  Issue本文および #563 evidence記録のowner一次情報に従う。

## Problem

Launcher processのcold start（force-stop後の再起動、install後初回起動）で、`ViewPool-init` thread
（RecentsView TaskView poolの事前inflate、非main looper thread）が
`LawnchairLayoutFactory.fontManager` のKotlin `by lazy`（`LazyThreadSafetyMode.SYNCHRONIZED`）monitorを
取得したまま、initializer内の `FontManager.INSTANCE.get(context)`
（`com.android.launcher3.util.MainThreadInitializedObject`）が `Executors.MAIN_EXECUTOR.submit { ... }.get()`
でmain threadの初期化を待つ。同じくinflate中のmain threadが同じfactory instanceの同じlazy monitorを待ち、
mainはメッセージループへ戻らないためfutureも完了しない。循環待ちによりhomeが起動しない（ANR / 対話なしwedge）。

## Benchmark

編集負担ベンチマーク（B1〜B7、NFR-014）の該当課題なし。理由: 本変更はcold start中の循環待ち除去のみで、
編集導線・確認flow・編集負担の定義される対象（organizer/edit surface hotseat等）を一切変更しない
behavior-only修正である。観測可能なhome画面の描画結果・font適用は不変（下のScenarioで不変性を固定）。

## Prior art

- Kotlin stdlib `lazy` 公式API docs（SYNCHRONIZEDは初期化中lockを保持する／外部からのsynchronizeは
  accidental deadlockを招くと明記） / https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/lazy.html /
  確認日2026-10-10 / 採用: 「cross-threadな待ちを伴うinitializerをlazy lockの下に置かない」設計根拠。
- Kotlin stdlibソース `LazyJVM.kt`（`SynchronizedLazyImpl.value`がinitializerをmonitor内で実行、
  `SafePublicationLazyImpl`はmonitor外で実行） / https://github.com/JetBrains/kotlin/blob/master/libraries/stdlib/jvm/src/kotlin/util/LazyJVM.kt /
  確認日2026-10-10 / 比較: `PUBLICATION`でも循環は解消するが冗長2重cacheが残るため不採用（Decision参照）。
- AOSP frameworks/base `LayoutInflater.java`（クラスはthread-safeでない。clone ctorが`mFactory2`をコピーする） /
  https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/LayoutInflater.java /
  確認日2026-10-10 / 採用: ViewPoolの`cloneInContext`が同一factory instanceを背景inflateへ共有する機構説明。
- AOSP frameworks/support `AsyncLayoutInflater.java`（背景inflateではViewが`Handler`生成・`Looper.myLooper()`
  呼び出し不可。Factory/Factory2非対応） /
  https://android.googlesource.com/platform/frameworks/support/+/89f7eba/v4/java/android/support/v4/view/AsyncLayoutInflater.java /
  確認日2026-10-10 / 採用: 「背景inflate pathのコードがmain thread待機にlockで応答してはならない」という
  platform契約の方向性根拠。
- AOSP platform/packages/apps/Launcher3 `ViewPool.java` commit `d1a67d0d7238cecf2c55ff095e2ec2b1621ac9a1`
  「Preventing dead lock in layout inflation」(Bug 143353100, 2019-11-11; fork history内のlocal commit) /
  https://android.googlesource.com/platform/packages/apps/Launcher3/ / 確認日2026-10-10 /
  採用: 上流はinflate機構側（in inflater側）だけで解決し、factory内部のlock契約はapp側責任とする前例。
  よって修正はforkの`LawnchairLayoutFactory`に置く。
- 上流Lawnchair現状: `LawnchairLauncher/lawnchair` 16-devの`LawnchairLayoutFactory.kt`は2026-10-10時点でも
  `by lazy { FontManager.INSTANCE.get(context) }` を保持（GitHub code searchで確認。ローカルmirror
  `upstream/16-dev@4793ba3a238bb1a3e786a3471a6d84767ed05267` でも一致） /
  https://github.com/LawnchairLauncher/lawnchair/blob/16-dev/lawnchair/src/app/lawnchair/LawnchairLayoutFactory.kt /
  確認日2026-10-10 / 採用: fork-local修正とし、上流への報告はfork Issueへ混ぜず意図的別操作（upstream chooser）で
  行う対象として記録（本PRの範囲外）。

## Outcome

修正後、cold start時に`ViewPool-init` threadがfont manager解決をmain thread待機で待ち、同時にmain threadが
inflate中に同じfactoryのfont解決を進める状況でも、どちらのthreadも相互のmonitorで停止しない。homeのANR/wedge
による未起動が解消し、font適用・view生成契約は現状と同一に保たれる。

## Decision（修正方式）

採用: **factory内のlazy除去**。`LawnchairLayoutFactory.onCreateView`のTextView経路で
`FontManager.INSTANCE.get(context)` を毎回呼ぶ。memoizationは`MainThreadInitializedObject`自身の`mValue`が
単一の正本であり、factory側に第2のロック層を作らない。待機中monitorを保持するコードが構造的に存在しなくなるため、
順序不変条件に依存しない。main thread上のper-callコストはapplication context解決と`mValue` null checkのみ。

却下した代替:

- A. factory構築時にmainでeager解決（Issue本文の示唆1つ）: lazy（monitor）は残るため「bg thread spawn前に
  mainが初期化する」という順序不変条件への依存が再導入され、将来の初期化順序変更で回帰し得る。冗長2重cacheも残る。
- B'. `lazy(LazyThreadSafetyMode.PUBLICATION)`: initializerがmonitor外で走るため循環は消えるが、
  未解決時に呼び出し側thread全てがinitializerを二重実行し（MTIOへは各自submit）、同じく冗長cacheを残す。
- C. `LazyThreadSafetyMode.NONE` / `unsafeLazy`: 並行初期化時のvisibility保証がなく、初期化中の他threadが
  未完遂値を読む可能性（behavior非対等）。却下。
- D. ViewPool経路のinflateでfactoryを使わせない（Issue本文の示唆）: 上流AOSP `ViewPool`/`RecentsView` bridge
  変更を伴い（AGENTSのLauncher3/AOSP最小bridge変更規約）、かつpool生成viewのfont適用挙動を変える
  （behavior変更が目的外）。却下。
- E. ViewPoolのpooling自体やfont overrideの無効化: 簡便だが契約外の変更。却下。

## Scope

- 含む振る舞い: `LawnchairLayoutFactory`のfont解決機構（`onCreateView` の TextView/BubbleTextView/DoubleShadowBubbleTextView/Button 経路のfont適用）。
- 含む検証: deterministic instrumentation regression（実Android runtime、API36 emulator）とcold-start再現率対照。
- 含むCI routing: regression testの既存lane co-occupancyとsurface self-trigger追加
  （詳細はPR本文のtest-audit記録。新規laneを作らない根拠を含む）。

## Non-goals

- #563 の HOME-origin empty overview（別欠陥。`docs/assessment/issue-563-api36-home-empty-overview.md`）。
- `SystemUiProxy.mRecentTasks` null bind race、SystemUI TIS rebind gap（#563 assessment related findings。別途起票）。
- 他の`by lazy`包_MTIO候補の横断修正: 全productionツリー監査で本循環に該当する残存箇所は不存在
  （`by lazy`が`MainThreadInitializedObject`系`INSTANCE.get`を包むのは本箇所のみ。他は非MTIOまたはmain専用）。
- ViewPool事前inflateの有効化/無効化、font機能の拡張、UI意匠変更。
- 上流LawnchairへのIssue報告（upstream chooser経由の意図的別操作として記録、本PRへ混ぜない）。

## Behavior scenarios

### Scenario: cold同時fontアクセス（循環待ち除去）

Given launcher process cold相当の状態（`FontManager.INSTANCE`未解決）で、同一の`LawnchairLayoutFactory`
instanceへmain looperが占用中（`Launcher.onCreate` inflate相当）に2つの背景inflate threadが
`onCreateView("TextView")` を呼ぶ
When 各呼び出しがfont解決を試みる
Then どの呼び出しも他の呼び出しが保持するmonitorで`BLOCKED`にならない（第2呼び出しはmain解決待ちで待つか完了する）
And main相当呼び出しは待機なく`TextView`を返し`FontManager`を解決する
And 背景呼び出しはmainがループへ戻った後に完了し、nullでないviewを返す（例外不落）

### Scenario: warm経路の背景アクセス（font適用不変）

Given `FontManager`が解決済み
When 背景threadが同一/別factory instanceで`onCreateView("TextView")`を呼ぶ
Then main thread関与なしに即完了し、`overrideFont`経路を含むviewを返す（返り値はnullでなく、生成view型は現状同一）

### Scenario: 隣接factory契約（生成マッピング不変）

Given 解決済みfont manager
When `onCreateView`へ`TextView`/未知tag名（`FrameLayout`）を渡す
Then `TextView`はnon-null、未知tag名はnullを返し、view生成マッピング（Button等）とfont適用の可視結果は変更前と同一
（失敗注入: 解決不能時は従来どおり`runCatching`がcatchしview返却は継続 — font適用のみ欠落。永続状態への書込みは本経路に存在しない）

## Verification

- deterministic RED→GREEN regression: `tests/organizer-instrumentation/app/lawnchair/startup/ColdStartFontLazyInversionInstrumentationTest.kt`
  をAPI36 emulator（実Launcher process内のinstrumentation）で実行。ベース実装（lazy保持）でRED
  （`REGRESSION #565` stack dump付きAssertionError。caller-1 `SynchronizedLazyImpl.getValue:86` locked→
  `MainThreadInitializedObject.get:66` park、caller-2 `getValue:81` BLOCKED — oracle ANR traceと同一signature）、
  修正後GREEN。実行記録はPR handoff packetへtrace artifact付きで残す。
- user-level再現率対照: Issue報告環境と同じAPI36 platformのemulatorで、force-stop→HOME cold start を
  修正前APK/修正後APKで反復し、wedge・ANR発生率とdropbox `/data/anr` 記録を比較してPRへ記録する。
- ANR trace: oracle trace（b043c85be…）と、修正後同環境で当該循環を示す`data_app_anr`が観測されないこと。
- 影響version範囲（Issue終了条件2）: fontManager lazyはupstream Lawnchair commit `a7c69072b7`（2021-10-20）で導入、
  `d2f4294aeb`→revert `7767d76320`（2022-07-17）以降連続して存在し、これらはfork product baseline
  `505dbc40e6154c05158b5d0271c45f6a885a411b`（v15.0.0-beta3.0）のancestor。`ViewPool`背景inflateは
  `d1a67d0d7238`（2019-11-11）以降。base HEAD `0c737bd45c`、#559 run tree `6595f22`・fixed APK tree
  `54ce5074`（trace対象）および上流16-dev `4793ba3a23`・GitHub code search（2026-10-10）でもlazyは存在。
  したがって影響範囲は NunuLauncher全履歴（v15-beta3 baseline〜main HEAD、16-dev stack全般）と、
  該当lineageを含むupstream Lawnchair builds。発動条件は cold start時のRecentsView TaskView pool
  事前inflate（`ViewPool-init` thread）とmain inflateの競合で、観測頻度は環境timing依存
  （報告: API36 emulator harnessでrestartあたり約1/2。機構はKotlin/JVM lockとLooper順序のみ依赖で
  platform API version固有ではない）。
- 上流UI bridge不変: 変更path列挙はPR本文（`LawnchairLayoutFactory.kt`とtest・CI routing・docs/specsのみ。
  `measure_upstream_patch_surface.py --target HEAD --enforce-baseline` は上流UI bridge変更を伴わないため
  不要を記録）。
- `./gradlew spotlessCheck` / `assembleLawnWithQuickstepGithubDebug` / 既存organizer unit gateは無変更維持。

## Accessibility and localization

font解決の呼び出し回数・待機機構の変更のみで、TalkBack label、focus順序、font scaling結果、翻訳textの
描画は変更前と同一（Scenario 2・3で固定）。cold startでhomeが応答するようになることはaccessibility改善方向。
実機対照として修正前後のhome画面表示確認をPR evidenceへ添付する。

## Change history

- 2026-10-10: Draft→accepted for #565（oracle: b043c85be… trace + Issue本文。修正方式Decision: factory lazy除去）。
