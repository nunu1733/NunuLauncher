# Implementation Plan: オーガナイザー診断exportをサポート済みrelease設定経路で到達可能にする

> Issue: [#138][1]
> Spec: [spec.md](./spec.md)
> Status: accepted — 2026-08-25にIssue #138でStage A承認。Stage B実装は本planに従う。

## Current evidence

確認済み(実読したcode pathと現行振る舞い):

- `lawnchair/src/app/lawnchair/ui/preferences/destinations/DebugMenuPreferences.kt:71` — `OrganizerDiagnosticsExportPreference(LawnchairApp.instance.layoutApplicationModule.diagnostics)` が `MainSwitchPreference(adapter = enableDebugMenu, ...)` の内容lambda内(50-94行)にcomposeされている。既定offでは同groupごと非表示。
- `lawnchair/src/app/lawnchair/preferences/PreferenceManager.kt:68` — `enableDebugMenu = BoolPref("pref_enableDebugMenu", false)`。
- `lawnchair/src/app/lawnchair/allapps/AllAppsSearchInput.kt:229-231` — 入力が `/lawnchairdebug` と完全一致した場合のみ `enableDebugMenu` をtoggleする唯一のcode。
- `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt:52,210,269` — `onOpenDiagnostics: (() -> Unit)? = null` は `ApplyResult`/`RecoveryResult` が `requiresSafeSupport()` のときだけ表示される行から呼ばれる。
- `lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceNavigation.kt:120` — `onOpenDiagnostics = { navController.navigate(DebugMenu) }`。遷移先はDebug menu画面であり、export行はoff状態のswitch内に入れ子のため到達しない(spec Problem節の構造根拠)。
- `lawnchair/src/app/lawnchair/organizer/diagnostics/export/ExportUi.kt` — SAF `ACTION_CREATE_DOCUMENT` → `ExportWriter.writeToUri` → toast。cancel時はno-op return。#67 AC-67-08/09/13の実装本体。
- `lawnchair/res/values/strings.xml:984-987`、`lawnchair/res/values-ja/strings.xml:20-23` — export行label/subtitle/success/errorは既存でen/ja双方に存在。
- `lawnchair/src/app/lawnchair/ui/preferences/destinations/HomeScreenPreferences.kt:145-166` — Layout groupに `NavigationActionPreference`(Grid / PlacementLocks / ManualOrganization / CategoryOverrides)が並ぶ。新entryの自然な配置位置。
- `lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceRoutes.kt:71` — `DebugMenu : PreferenceRootRoute`。子destinationは `PreferenceRoute` 実装(例: 同`:98` `HomeScreenPlacementLocks`)。
- runtime証跡: [Issue #138本文][1](release APK SHA-256、API 36実機相当AVD、repro手順)と[issue-132-exploratory-baseline.md §Diagnostics export route](../../docs/assessment/evidence/issue-132-exploratory-baseline.md)。同evidenceでは同一sessionのworkspace設定・preference navigation・manual organizer surfaceがtouchで正常動作しており、Settings navigation全体はrelease buildでも機能することが確認済み。

推測と未検証:

- 「`MainSwitchPreference` の内容lambdaはswitch off時に表示されない」はcomposition構造とLawnchair Settingsの既存挙動からの読解であり、本repoでのruntime captureはない。Stage B冒頭でrelease APK上の逆証(navigation到達時にexport行が無いこと=AC-1のred側)として確認する。
- release/minified buildでのR8起動による新route・SAF flowへの影響は未知ではないが未証明。Debug menu経由のexportはdebug buildでのみevidenceがある(#103)。AC-6のrelease operator evidenceで確定させる。

## Design

### Modules and interfaces

production変更はpreferences UI配下に局所化する。organizer diagnostics module(`diagnostics/export/`)、journal、`ExportWriter`、`DiagnosticsPort`実装は**変更しない**(零bridge)。

- 新route object: `HomeScreenOrganizerDiagnostics : PreferenceRoute`(`PreferenceRoutes.kt`)。dashboard直結のrootではなくHomeScreenの子destinationとする。
- 新screen composable: `OrganizerDiagnosticsPreferences`(新file、destinations package)。構成は `PreferenceLayout(label = 新title)` + explainer Text + 既存 `OrganizerDiagnosticsExportPreference(port)` のみ。developer flag・feature flag等は置かない(non-goals)。
- diagnostics port取得: Debug menuと同一accessor `LawnchairApp.instance.layoutApplicationModule.diagnostics` を再利用。単一port・単一writerのまま(spec AC-2)。画面composableは `port: DiagnosticsPort? = null`(null時にproduction accessorへfallback)という既存pattern(`ManualOrganizationPreferences(run = null)` と同型)のみを持つ。これはtest観測用の差し替え点であり、新規interfaceやadapterは追加しない。
- AC-3/AC-4の観測方法(review P1対応): instrumentationからは (1) androidx production契約である `LocalActivityResultRegistryOwner` をrecording `ActivityResultRegistry` で包んで提供し(`rememberLauncherForActivityResult` が消費するのはこのownerであり、`ExportUi.kt` 無変更のままlaunch意図とresult dispatchを観測できる)、(2) writer呼び出しの有無は上記port引数へ渡すrecording `DiagnosticsPort`(snapshot呼出回数を観測。writerはexport時にsnapshotを読む)で判定する。どちらも既存seam上の観測であり、production codeにtest用hookを追加しない。
- navigation wiring: `PreferenceNavigation.kt` へ `composable<HomeScreenOrganizerDiagnostics> { OrganizerDiagnosticsPreferences() }` を追加し、`onOpenDiagnostics` lambdaを `navController.navigate(DebugMenu)` から `navController.navigate(HomeScreenOrganizerDiagnostics)` へ変更。
- HomeScreen entry: `HomeScreenPreferences.kt` のLayout groupへ `NavigationActionPreference`(title/subtitle=新string)を追加。Manual organizationの直後を既定順とする。
- 呼び出し側とtestが使うseam: (1) production navigation(HomeScreen → 新route)のinstrumentation、(2) 新screen composable直接のcompose test。`MainSwitchPreference` 等の内部実装は検証しない。

### Data flow

表示は静的(状態読取なし)。活性化 → 既存 `OrganizerDiagnosticsExportPreference` 内のSAF `ACTION_CREATE_DOCUMENT` → ユーザー宛先選択 → 既存 `ExportWriter.writeToUri` → success/error toast。cancel → launcher callbackの早期return(既存)。本planが追加する状態遷移・error pathはない。

### Alternatives rejected

1. **HomeScreen直下へのinline `ClickablePreference`** — stableなnamed routeが残らず、安全terminalからの深い遷移先として曖昧。専用画面は将来のdiagnostics関連集約先(#106 release logcat evidence等)にもなる。採否。
2. **Manual organization画面への常設行追加** — #52所有のrun-state machine UIへ常時要素を混入させ、状態別表示semanticsを複雑化する。安全terminal条件自体は本Issue scope外。採否。
3. **Dashboard category新設** — 単一actionには過大で、non-goal(debug menu露出の回避)とも整合しない。採否。
4. **Debug menu内export行の削除** — #103/#109 evidence surfaceの連続性を壊す割に、issue要件は削除を要求しない。維持してsource差分を最小化する。採否。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceRoutes.kt` | `HomeScreenOrganizerDiagnostics : PreferenceRoute` 追加 | 子destination routeの宣言位置は既存routesと同一 |
| `lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceNavigation.kt` | `composable<HomeScreenOrganizerDiagnostics>` 追加、`onOpenDiagnostics` 遷移先変更 | NavHostの一元的wiring箇所 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/OrganizerDiagnosticsPreferences.kt`(新規) | 専用screen composable(title+explainer+既存export preference) | destinations packageの既存画面patternに従う |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/HomeScreenPreferences.kt` | Layout groupへ `NavigationActionPreference` 追加 | サポート済み経路の可視入口 |
| `lawnchair/res/values/strings.xml` + `res/values-ja/strings.xml` | 新画面title/explainerの2 string(en/ja) | repoのlocalization慣行(en + ja即時追加) |
| `tests/organizer-instrumentation/app/lawnchair/ui/preferences/`(新規test class) | route wiring(HomeScreen entry→新route→export行表示)+ recording registryによるlaunch/cancel観測 + 安全terminal導線assertion | 既存preference系instrumentationと同module(#52/#99 harness群) |
| spec/plan status更新、必要ならassessment evidence追記 | Stage B完了時にstatus `implemented`へ | workflow規約 |

## Migration and recovery

- schema / preference key / DB migrationなし。journal・layout dataへの書き込み変更なし。
- failure中のrollback: 該当なし(UI navigation追加のみ)。SAF cancel/write failureの挙動は既存ExportUi契約(#67)が保持する。
- release rollback / downgrade: PR revertで現行(Debug menuのみ)へ戻る。データ互換性問題なし。
- backup / restore compatibility: 影響なし。新string resourceはbackup対象外のAPK内resource。

## Verification

標準gate(AGENTS.md検証済みcommand):

```bash
./gradlew spotlessCheck
./gradlew assembleLawnWithQuickstepGithubDebug
./gradlew testLawnWithQuickstepGithubDebugUnitTest
./gradlew assembleLawnWithQuickstepGithubDebugAndroidTest
```

host実行(新instrumentation class):

```bash
adb install -r -t -g 'build/outputs/apk/lawnWithQuickstepGithub/debug/Lawnchair.15.Dev.(<sha>).github.debug.apk'
adb install -r -t 'build/outputs/apk/androidTest/lawnWithQuickstepGithub/debug/NunuLauncher-lawn-withQuickstep-github-debug-androidTest.apk'
adb shell am instrument -w -r \
  -e class app.lawnchair.ui.preferences.OrganizerDiagnosticsRouteInstrumentationTest \
  app.lawnchair.debug.test/app.lawnchair.migration.DeckRetirementTestRunner
```

release operator evidence(AC-1/AC-6):

```bash
./gradlew assembleLawnWithQuickstepGithubRelease
# release APKをAPI 36 emulatorへinstallし、
# Home settings → ホーム画面 → organizer diagnostics → activation → cancel → return を実走査。
# UI stateはredacted subset(evidence doc形式)で記録する。
```

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | instrumentation: HomeScreen group entry→新route遷移→export行表示assert。+ release APK上の手順evidence(redacted) | 上記instrumentation command + release AVD手動走査 |
| AC-2 | PR差分review: 新画面が既存composable・accessor再利用、`organizer/diagnostics/**` diffゼロ確認 | PR diff |
| AC-3 | 新instrumentation: production契約のrecording `ActivityResultRegistry` 上で、画面表示・navigation中は `launch` 呼出0、export行の明示activationで `launch`=1 かつ捕捉したintentが `ACTION_CREATE_DOCUMENT`(CATEGORY_OPENABLE、type `application/jsonl`)であることをassert。`hasClickAction()` 等のsemantics assertは補助に留める | debug instrumentation(新class) |
| AC-4 | 同instrumentation: activation後にregistryへ `RESULT_CANCELED` をdispatchし、recording `DiagnosticsPort` のsnapshot呼出増加ゼロ(writer不呼び出し)、journal file byte同一、再launch発生なしをassert。writer seam自体のcancel/write-failure分離は既存 [#67 `ExportWriterTest`](../../tests/unit/app/lawnchair/organizer/diagnostics/export/ExportWriterTest.kt) の `d10CancellationIsolated` / `d10WriteFailureLeavesJournalIntact`(JVM gate内)が所有し、本Issueで再実装しない。加えてrelease手動走査でcancel→returnのend-to-end確認 | debug instrumentation + `./gradlew testLawnWithQuickstepGithubDebugUnitTest`(#67既存case greenの維持)+ release AVD手動走査 |
| AC-5 | instrumentation: manual organization安全terminal stateから `onOpenDiagnostics` 相当導線が新route labelへ到達すること(既存harness拡張、DebugMenu依存の旧assertがあれば修正) | debug instrumentation |
| AC-6 | release(minified)APKでのnavigation→activation→cancel→return実走査記録(redacted UI state)をissue #138へ添付 | API 36 emulator(`nunu_qpr2_api36_1`定義相当) |
| AC-7 | string差分(values/values-ja)+ compose semantics assertion(label/subtitleの存在とclick action) | PR diff + debug instrumentation |
| AC-8 | spotless/debug build/unit gate/androidTest assembly + CI run URL(`final-status`)をPRへ記録 | 標準gate command |

含めるべき観点: UI/accessibility(semantics、localized label)、integration(preference navigation)、failure path(SAF cancel回帰)、regression(安全terminal導線・Debug menu既存動作)、release product(minified R8下での到達性)。performance該当なし(静的UI追加)。

Stop conditions(発生したら実装を止めIssueで判断):

- S-1: release/minified buildで新経路の到達・表示がR8/navigation起因で成立しない。
- S-2: expanded screen(二pane)構成で新routeの表示・back stackが契約を満たせない。
- S-3: port accessor(`layoutApplicationModule.diagnostics`)をpreference画面から参照すると初期化順序・process制約の問題が出る(Debug menuと同一accessorのため発生は想定していない)。
- S-4: 契約を満たすために `organizer/diagnostics/**` やLauncher3由来codeの変更が必要になることが判明(scope超過。spec non-goalsと矛盾するため再承認を要する)。

## Documentation updates

- [ ] spec status/history(承認時に`accepted`、Stage B完了時に`implemented`)
- [ ] CONTEXT.md: 更新不要の見込み(domain language空)
- [ ] DESIGN.md: 更新不要の見込み(§4.5 diagnostics moduleのsink構成不変、§4.4 UI adapterの既有範囲内)。この判断をPRで記録
- [ ] ADR: 作らない見込み(route配置判断はspec/planで固定可能な単一決定)
- [ ] AGENTS.md: 変更なし(新必須commandなし)

## Execution checklist

- [x] Current behavior reproduced(Issue #138本文のrelease repro + evidence docのroute/source boundary記録済み)
- [ ] Tests fail for the missing behavior(release APK上でAC-1経路が不在であることのred確認)
- [ ] Minimal implementation completed
- [ ] Migration/recovery verified(該当なしを明記)
- [ ] Full relevant verification completed
- [ ] PR evidence and remaining risks recorded

[1]: https://github.com/nunu1733/NunuLauncher/issues/138
