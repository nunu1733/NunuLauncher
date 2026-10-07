---
issue: "#545"
status: accepted
updated: 2026-10-07
---

# Plan: API 37 Quickstep provider修復 — IWindowManager.createInputConsumer破壊へのcompat対応

> Status: revision 2 accepted / revision 3 **accepted**（ Owner decision 10 addendum）
> （prerequisite: revision 2の受入 2026-10-07。revision 1はPR #548 review round 3でblocking 0・
> Clear（[review](https://github.com/nunu1733/NunuLauncher/pull/548#issuecomment-6035965725)）でaccepted。
> revision 2（Owner decision 8/9 addendum）はPR #549 review round 4でblocking 0・Clear
> （[review](https://github.com/nunu1733/NunuLauncher/pull/549#issuecomment-6039007333)。
> head `96f5c66d4c322daa3af5f2f1cce7618181151666` を確認）でaccepted。
> revision 3（Owner decision 10 addendum）はPR #551 review round 4でblocking 0・Clear
> （[review](https://github.com/nunu1733/NunuLauncher/issues/551) 最後のコメント。
> head `9768862b9b869a55831b1886919a6b113ff15e54` を確認）でaccepted。
> 受入は本PR #551のmergeで完了する）。

**Risk tier: H**（[spec.md](./spec.md) 冒頭の判定どおり。vendored upstream file変更＋provider bind path）。

正本revision: spec accepted commit（PR確定後に本planへ記録）。base commit
`e214b7b19019e0efd2f28f53bf8b56f0b3510b76`（`issue-524-api37-quickstep` head）。

## 現在codeの根拠

（行番号はbase commit固定。spec Baselineと同じ根拠をplan視点で要約する）

- `systemUI/shared/src/com/android/systemui/shared/system/InputConsumerController.java:141-163`
  `registerInputConsumer()`: `:143` で旧form
  `mWindowManager.createInputConsumer(mToken, mName, DEFAULT_DISPLAY, inputChannel)` を呼ぶ。
  このファイルはAOSP素（v15→16-dev stack。`hookDestroyInputConsumer` はmain側のみでstackにない）。
  `:155-172` `unregisterInputConsumer()` は `destroyInputConsumer(mToken, DEFAULT_DISPLAY)` のみ
  （API 37に存在。修正対象外）。
- `quickstep/src/com/android/quickstep/TouchInteractionService.java:778`（生成・唯一）、
  `:873`（`registerInputConsumer()` 呼出。クラッシュstack一致）、`:978`（unregister呼出）。
- `build.gradle:185-186`（`quickstepMinSdk = "35"` / `quickstepMaxSdk = "36"`）、
  `:203-206`（buildConfigField/manifestPlaceholders反映）、`:267-272`（debug override 0/100000）、
  `:436`（`addFrameworkJar('framework-16.jar')`。API 36 jarがclasspath先頭で
  `android.view.IWindowManager` を提供。`VERSION_CODES.CINNAMON_BUN` 不在のためAPI 37 gateは
  compile時定数不可）。
- `docs/adr/0018-lawnchair-16-rebase.md` revision 8（Decision 7にmaxSdk 37保留が記録済み）。
  revision 9への改訂対象。
- `quickstep/src/com/android/quickstep/SystemUiProxy.kt:188-198`（G3対象。**コード変更しない**）。
- `quickstep/src/com/android/launcher3/taskbar/TaskbarRecentAppsController.kt:67-76`
  （`enableRecentTasksThrottle`。`Utilities.ATLEAST_BAKLAVA_1` ガード下で
  `DesktopExperienceFlags.ENABLE_TASKBAR_RECENT_TASKS_THROTTLE_BUGFIX` を参照。fieldは
  framework-16.jarに存在するがAPI 37.0 imageのframework.jarから削除済み。provider bind後にのみ
  実行され37.0 imageで `NoSuchFieldError`。spec Owner decision 8 / AC-10）。
- `systemUI/shared/src/com/android/systemui/shared/navigationbar/KeyButtonRipple.java:108`
  （`SDK_INT_FULL >= 3600001` ガード下で `android.companion.virtualdevice.flags.Flags.viewconfigurationApis()`
  を参照。post-guard matrix (a)/(b)でAPI 37.0 imageのapp-visibleなclass不在
  （hidden_from_bootclasspath名前空間のみ実在をdexdump実測）により `NoClassDefFoundError` を毎回
  実測。spec Owner decision 10 / AC-10b）。

## 変更moduleとinterface/seam

| path | 変更 |
|---|---|
| `systemUI/shared/src/com/android/systemui/shared/system/InputConsumerController.java` | `registerInputConsumer()` のみを修正し、`createInputConsumer` のAPI 37 return形式をreflection呼出しするprivate helperを追加（近傍に#545理由comment）。diffはこの1 fileに限定する |
| `quickstep/src/com/android/launcher3/taskbar/TaskbarRecentAppsController.kt` | `enableRecentTasksThrottle` のflag参照1箇所へ `NoSuchFieldError` degrade guard（throttle無効として継続。近傍に#545理由comment。spec Owner decision 8） |
| `res/values/dimens.xml` | `taskbar_phone_size` のframework参照（`:437`）をliteral `48dp` へ置換（#545参照comment付き。spec Owner decision 9。37.0 imageでのbaked ID shift / `Resources$NotFoundException` を解消。確認対象levelの意図値（`navigation_bar_frame_height` dereference先）は48dpで同一だが、API 36の現APKは別resource（10dp）へ、API 35のtableでは別resource（20dp）へ誤解決が一次出力済み） |
| `systemUI/shared/src/com/android/systemui/shared/navigationbar/KeyButtonRipple.java` | `:108` のflag読取り1箇所へ `NoClassDefFoundError` degrade guard（`ViewConfiguration.getTapTimeout()` 旧挙動へfallback。近傍に#545理由comment。spec Owner decision 10。post-guard matrix (a)/(b)で `NoClassDefFoundError` 実測。直接参照式はchecked例外を送出しないためcatchは`NoClassDefFoundError`のみ） |
| `build.gradle` | `quickstepMaxSdk` `"36"` → `"37"`（1行。`quickstepMinSdk` 不変）。**検証candidate commit** としてcompat修正（4 commits）後に適用し、全matrix成立時にのみ確定保持する（不成立時はdrop/revert） |
| `docs/adr/0018-lawnchair-16-rebase.md` | revision 7→8の次の改訂としてrevision 9: Decision 7にadvertised range 35..37・保留解除（修復検証の証跡参照）を記録 |
| `docs/assessment/545-api37-provider-fix-evidence/` | 検証証跡（README＋logcat/png/txt。APKはcommitしない。sha256をREADMEへ） |

**seam**: 呼出側・テストは既存の `InputConsumerController` public interface
（`registerInputConsumer()` / `unregisterInputConsumer()`）を変えない。bridgeは
`registerInputConsumer()` 内のframework呼出し形式の切替、`TaskbarRecentAppsController`
のflag参照1箇所のdegrade、`taskbar_phone_size` のres値literal化、`KeyButtonRipple` のflag読取り
1箇所のdegrade（いずれも#545 provider修復の最小範囲）である。呼出側（`TouchInteractionService`）・gate（`LawnchairApp`）・G3対象
（`SystemUiProxy`）・5箇所のdimen reader（res側で解決されるため無変更）は変更しない。
wmshellは変更しない（spec Owner decision 6）。

## 実装詳細（コードshape）

`registerInputConsumer()` を次の構造にする（実際のコメント文言は以下のとおり#545参照を含める）:

```java
public void registerInputConsumer() {
    if (mInputEventReceiver == null) {
        InputChannel inputChannel = null;
        try {
            mWindowManager.destroyInputConsumer(mToken, DEFAULT_DISPLAY);
            if (Build.VERSION.SDK_INT >= API_37 /* CINNAMON_BUN absent from framework-16.jar */) {
                // #545: Android 17 (API 37) replaced IWindowManager.createInputConsumer in
                // place (return-InputChannel form; the compiled out-param form was removed).
                // The compile classpath (framework-16.jar) only has the API 36 form, so the
                // API 37 form is invoked reflectively. Lookup failure degrades to an
                // unregistered consumer instead of crashing the provider bind path.
                inputChannel = createInputConsumerCompat(mToken, mName, DEFAULT_DISPLAY);
            } else {
                inputChannel = new InputChannel();
                mWindowManager.createInputConsumer(mToken, mName, DEFAULT_DISPLAY, inputChannel);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to create input consumer", e);
        }
        if (inputChannel == null) {
            Log.e(TAG, "Input consumer not registered (see #545)");
        } else {
            mInputEventReceiver = new InputEventReceiver(inputChannel, Looper.myLooper(),
                    Choreographer.getInstance());
            if (mRegistrationListener != null) {
                mRegistrationListener.onRegistrationChanged(true /* isRegistered */);
            }
        }
    }
}

private InputChannel createInputConsumerCompat(IBinder token, String name, int displayId) {
    try {
        Class<?> iWindowManagerClass = Class.forName("android.view.IWindowManager");
        Method createInputConsumerMethod = iWindowManagerClass.getMethod(
                "createInputConsumer", IBinder.class, String.class, int.class);
        return (InputChannel) createInputConsumerMethod.invoke(mWindowManager, token, name, displayId);
    } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException
            | InvocationTargetException e) {
        Log.e(TAG, "Failed to invoke createInputConsumer (API 37 form, #545)", e);
        return null;
    }
}
```

- API 37 gateのリテラル値はplan確定時に `37` または `VERSION_CODES.BAKLAVA` 比較表現のいずれかを
  選ぶ（`framework-16.jar` に `BAKLAVA` は存在済みを確認済み。可読性とlint/spotless通過で決める）。
- 旧path（API 35〜36）の挙動は現行と同一である（`new InputChannel()` にout-param充填）。
  RemoteException時の既存挙動（log後、receiverを `new InputChannel()` のまま構築）は、旧pathでは
  現行どおり維持する（修正の範囲外。上記shapeでは旧pathのRemoteException時は `inputChannel` が
  非nullのためreceiver構築まで進む — 現行挙動と一致）。新path（API 37）のlookup失敗時のみ
  degrade（spec Scenario 4: ERROR log＋未登録継続）。
- `InvocationTargetException` のcauseがframework側のRemoteException（`rethrowFromSystemServer`
  相当）の場合もdegradeとする（server側失敗をクラッシュループにしない。spec Scenario 4の
  適用範囲）。
- `unregisterInputConsumer()` は変更しない。receiver未登録時に呼ばれても現行のままno-opである。
- debug/release・flavor差の分岐は存在しない（vendor fileの1経路のみ）。

## 実装詳細2（Taskbar flag guard。spec Owner decision 8）

`quickstep/src/com/android/launcher3/taskbar/TaskbarRecentAppsController.kt:67-76` の
`enableRecentTasksThrottle` を次の構造にする（#545参照commentを近傍に残す）:

```kotlin
    val enableRecentTasksThrottle =
        if (Utilities.ATLEAST_BAKLAVA_1) {
            // #545: ENABLE_TASKBAR_RECENT_TASKS_THROTTLE_BUGFIX exists in the framework-16.jar
            // compile classpath but was removed from the API 37.0 device framework; degrade to
            // disabled instead of crashing the provider bind path (NoSuchFieldError in
            // TaskbarActivityContext init). Absent-flag behavior equals throttle-off.
            try {
                DesktopExperienceFlags.ENABLE_TASKBAR_RECENT_TASKS_THROTTLE_BUGFIX.isTrue
            } catch (e: NoSuchFieldError) {
                false
            }
        } else {
            false
        }
```

- field有無によらず `ATLEAST_BAKLAVA_1=false` の環境（API 36 image等。当該imageにfieldは無い）では
  参照自体を行わないため既存の読み取り挙動が変わらない（同経路は完全に不変）。37.0 imageではguardにより
  `NoSuchFieldError` を吸収してthrottle無効で初期化を継続する（spec新Scenario）。

## 実装詳細3（taskbar_phone_size literal化。spec Owner decision 9）

`res/values/dimens.xml:437` を次のとおり変更する（#545参照comment付き）:

```xml
    <!-- #545: the @*android:dimen/navigation_bar_frame_height reference bakes framework
         resource ID 0x01050283, whose resolution differs per API level (contrast in
         docs/assessment/545-api37-provider-fix-evidence/api36-image-framework-res-contrast.txt):
         on API 37.0 images it became navigation_bar_height_portrait (no default-config value),
         crashing TaskbarStashController init with Resources$NotFoundException in phone
         profile; on API 36 images it silently resolves to
         notification_2025_action_list_min_height (~10dp). navigation_bar_frame_height
         dereferences to navigation_bar_height = 48dp (default config) on every level, and
         taskbar_phone_size readers only run in phone mode, so the literal is the intended
         value everywhere (crash fix on 37.0, geometry normalization 10dp -> 48dp on 36). -->
    <dimen name="taskbar_phone_size">48dp</dimen>
```

- API 36では **挙動不変ではなく意図した正規化**（10dp誤解決 → 48dp）であり、matrix (e) で
  native-density screenshot＋overview/task切替に加えてこの幾何正規化をrecordする。
  37.2端末では `navigation_bar_frame_height` → `navigation_bar_height` → 48dp defaultで
  値は同一。`:441` の `rounded_corner_content_padding`（同種の `@*android:dimen` 参照）は
  失敗経路に未到達のため本Issueでは触れない（検証で到達が判明した場合は別判断）。

## migration

- なし（DB・preference・schemaに触れない）。

## rollback

- 実装PR全体をrevertすればbase `e214b7b190` に戻る（単一機能commit群）。
  compat修正4 commits（`InputConsumerController` reflection＋`TaskbarRecentAppsController` guard＋
  `taskbar_phone_size` literal化＋`KeyButtonRipple` guard）と
  検証candidate（`quickstepMaxSdk`）commitを分離するため、matrix (b)以降の
  不成立時はcandidate commitだけをrevertして36維持とできる（spec Owner decision 7 / AC-6）。
  reconciliationの旧candidate `e2fe6f80df` とそのrevert pairはsuperseded historyとして保持される
  （spec Owner decision 8/9の証跡commit到達可能性の維持）。
  stack自体（PR #546 → 本PR）のrollbackはPR #546の取り扱いに従う。

## 実装順序（candidate model。spec Owner decision 7/8/9＋post-guard追記（Owner decision 10））

> 実行履歴: 以下step 1〜9のうち1〜7相当は実施済み（commit `416273ce2f`〜`2987e525bd`、
> pre-guard diagnostic run、post-guard最終matrix `(e)/(f)` PASS・`(a)/(b)`が第四破壊でFAIL。
> **post-guard証跡はcommit `fc5169566c` に固定**）。Owner decision 10に従い、残存stepは次のとおり:
> a. **`2987e525bd` を明示revertしてmaxSdk 36へ戻す**（現headが既にmaxSdk 37のため
>    「candidateの再作成」は不能。revertで36へ戻してから再積む。旧candidateとrevertのpairは
>    superseded historyとして保持しREADMEへ記録）
> b. `KeyButtonRipple.java` degrade guardを1 commit適用（`fix(545): ...`。
>    **`NoClassDefFoundError` のみをcatch**。直接参照式はchecked例外を送出しないため
>    `ClassNotFoundException` をcatchに含めるとcompile不能）→ spotlessCheck →
>    `assembleLawnWithQuickstepGithubDebug`（compile確認）
> c. **matrix (a) をこのcompat 4修正headで実施**（prefix `qa-*`。実行SHAは
>    「compat修正適用後head」＝AC-9の(a)契約に整合）
> d. `quickstepMaxSdk` 36→37を **candidate commitとして新規適用**→ debug/release両build →
>    **matrix (b)+G3を新candidate SHAで実施**（prefix `qb-*`）
> e. **(e)/(f)/hiddenapi/wmshellのPASS結果は旧candidate `2987e525bd` のartifactに固定して
>    再利用**（path不変。READMEのmatrix↔SHA↔APK対応表はSHA別記載：
>    (a)=compat 4修正head、(b)=新candidate、(e)/(f)=旧candidate `2987e525bd`）
> f. 全成立ならcandidate保持＋ADR-0018 rev 9。不成立ならcandidate revert＋36維持。

1. 修正前対照は実施済み（#524 (a)/(b) failure signature。本検証では対照として参照するのみで
   再実施しない）。
2. **branch reconciliation（revert方式）**: 現在のcandidate commit `e2fe6f80df`（quickstepMaxSdk
   36→37。既にpush済み）は証跡commit（`f5a3977281` / `e70a58c1fd` / `206850b3d7`）の祖先のため
   drop/resetせず、**`git revert e2fe6f80df` で明示revertしてtreeを36へ戻す**。以降
   ①（既存`416273ce2f`）→②→③→matrix (a)→新candidate commitの順（spec実施順序どおり）。
   旧candidateとrevertのpairはsuperseded historyとして保持し、旧candidateのartifactとcommit
   message記述は **superseded（acceptance不使用）** としてevidence READMEへ記録する。
3. `InputConsumerController.java` のcompat修正（1 commit。既存 `416273ce2f` を保持）。
4. `TaskbarRecentAppsController.kt` のflag guardを1 commitで適用（`fix(545): ...`。spec Owner
   decision 8）。
5. `res/values/dimens.xml` の `taskbar_phone_size` literal化を1 commitで適用（`fix(545): ...`。
   spec Owner decision 9）。
6. `spotlessCheck` ＋ `assembleLawnWithQuickstepGithubDebug`（compile確認）→
   **matrix (a) をこのSHA（historical: compat修正3 commits適用後head。v3では Owner decision 10 の
   追記step c. の compat 4修正head** を正本とする）でnative density（`wm density reset` 済み）
   で実施**（実行SHAをevidence READMEへ記録。中間証跡: ①commit単体時点の2026-10-07 diagnostic run
   （wm density 280）はcommit `f5a3977281` / `e70a58c1fd` / `206850b3d7` に固定しOwner decision 8/9の
   根拠として保持）。
7. **〔Owner decision 10前のhistorical execution〕** `quickstepMaxSdk` 36→37を **検証candidate
   commit**（`feat(545): raise quickstepMaxSdk ...`）として適用し、debug/release両build済み
   （旧candidate `2987e525bd`。v3では pea: の再candidateが正本）。**matrix (b)/(f) はcandidate
   release APK、matrix (e) はcandidate debug APKで実施**(historical。v3では (b)=再candidate、
   (e)/(f)=旧candidate `2987e525bd` の再利用)。
8. **全matrix成立時**: candidateを最終成果物として保持し、ADR-0018 revision 9 commitを追加、
   candidate releaseのmanifest placeholder静的確認（AC-6）。
   **不成立時**: candidate commitをdrop/revertして `quickstepMaxSdk` 36を維持し、failure evidenceを
   記録する（compat修正自体はprovider修復として保持可否を(a)結果で判定。hiddenapi block時は
   spec Scenario 4どおり停止）。
9. 実装PR本文へReview / handoff packet（github-workflow.md 定形）＋patch surface計測結果＋
   matrix↔SHA対応表を記載。

## test

- 新規永続testなし（spec Test oracleのtest-audit判断どおり。JVM/Robolectricでは
  「API 37 frameworkで旧form消失」を再現できず、provider構成runtime matrixが一次証拠）。
- 既存gate: `./gradlew spotlessCheck`、`assembleLawnWithQuickstepGithubDebug` /
  `...Release`、既存unit/instrumentation gate（CI）。CI merge gate（`final-status`）を
  対象headで成功させる。
- 失敗時の振る舞いtest（spec Scenario 4）もruntimeには落とせないため、code reviewによる
  shape確認＋(a)/(b)成功によって「当該pathが実行されること」を間接確認する（degrade path自体は
  想定外系でmatrixでは発生しない。PRへ記載する未確認範囲とする）。

## 検証方法（matrix詳細）

構成手段・preflight・観測は#524 README §2/§4の手順を再利用する（overlay差し替えは
rm→push→chmod→restorecon→reboot。priv-app配置は `/product/priv-app/` ＋privapp-permissions
allowlist XML。run毎に `cmd overlay lookup` とSystemUI `mRecentsComponentName` の一致を
preflight証跡化）。

- (a) API 37 debug＋debug用overlay＋priv-app（**v3実行SHA: compat修正4 commits適用後head。qa-run**。
  historical: 3-commit版は `fc5169566c` のpre-guard側）: bind成功・
  クラッシュ無し（#524 signature非再現をlogcatで確認）・`isConnected=true`・
  APP_SWITCH→overview成立・task card tap→切替成功・screenshot。
  同logcatからhiddenapi観測抜粋（AC-3）と `PipInputConsumer` 監視（AC-1付帯）。
- (b) API 37 release（maxSdk 37のcandidate build。**実行SHA: candidate commit**）＋release用overlay＋
  priv-app: preflight・`compatible=true`（"disabling recents" 無し・sheet非表示screenshot）・
  overview成立・task切替。
  G3: overview→task切替の遷移時間帯logcatを取得し `ActivityTaskManager` BAL block有無を判定（AC-4）。
- (d) hiddenapi一次出力: root shellで可能なら `hiddenapi list` 相当（`cmd hiddenapi` /
  `hiddenapi` binaryの在否を確認し、取得できた出力をそのまま保存。取得不能な場合はその旨を記録し、
  logcat観測を一次出力とする）。**v3: image上のflags table不在（`d-hiddenapi-logcat-post.txt` /
  `hiddenapi-flags-post.txt`。`fc5169566c`）を確定証跡として再利用**。qa-/qb-runで同種logcatの
  補助観測が取れる場合はacceptanceの既取得PASSを置き換えない。
- (e) API 36 debug＋debug用overlay＋priv-app（**v3: 再実行しない。実行SHA: 旧candidate
  `2987e525bd`、candidate debug APK使用。`fc5169566c` 固定のpost-guard証跡（`me-*`）を再利用**）:
  bind・overview成立・task切替のprovider path機能回帰なし＋**Taskbar geometry正規化のrecord**
  （post-fix screenshot＋APKの `taskbar_phone_size=48dp` 静的確認＋pre-fix誤解決先10dp対照）は
  `fc5169566c` の `me-*` fileで成立済み（意図した変化としてrecord済み）。
- (f) stock構成（**v3: 再実行しない。実行SHA: 旧candidate `2987e525bd`、candidate release APK。
  `fc5169566c` 固定のpost-guard証跡（`mf-*`）を再利用**）: overlay無しAPI 37へcandidate releaseを
  通常install→"disabling recents" 診断log、sheet無し、system overview継続、launcher crash無し。
  API 36 stockの簡易確認も `mf-*` fileで成立済み（#524 (d)-API36相当を修正後buildで取得済み）。
- wmshell監視: **v3: 旧candidate `2987e525bd` の既取得PASS（`ma-wmshell-watch.txt` /
  `mb-wmshell-watch.txt`＝0件）を `fc5169566c` 証跡から再利用**。qa-/qb-run logcatで同種grepが
  取れる場合も補助観測であり、既取得PASSを置き換えない。
- 証跡は `docs/assessment/545-api37-provider-fix-evidence/` へ `a-*`〜`f-*` 形式（#524と同型）で
  保存しREADMEへ一覧化する。**READMEにはmatrixごとの実行SHA・使用APK・APK sha256の対応表を
  必ず含める**（AC-9）。

## 未確認範囲（plan時点）

- hiddenapi enforcementの実挙動（spec Owner decision 3のとおり検証で確定。見込みは非block）。
- wmshell `PipInputConsumer` のlauncher process到達性（前提: 到達しない。matrix logcat監視で確認）。
- degrade path（Scenario 4）のruntime再現（想定外系のためmatrixでは発生しない。code reviewで確認）。
