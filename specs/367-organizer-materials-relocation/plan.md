# Implementation Plan: 材料面のhub集約と設定側organizer row廃止

> Issue: #367
> Spec: [spec.md](./spec.md)
> Status: draft

## Current evidence

以下はすべてmain `3076bdae7e`（2026-09-19、PR #364 merge後）で確認した現行実装の
事実である。#366は実装前であり、その接続面の記述は`origin/issue-366-spec-plan`
（`bf00f96175`）のdraft spec/planに基づく（事実と計画を分けて記す）。

### 設定 → Home screenの現行構造（削除対象）

- `lawnchair/src/app/lawnchair/ui/preferences/destinations/HomeScreenPreferences.kt`
  - General group内103〜109行目: manual organization直行row
    （`NavigationActionPreference`、label `manual_organization_title`、destination
    `HomeScreenManualOrganization()`、#232昇進コメント付き）。
  - Layout group内159〜201行目のうち、organizer系row 4件:
    `HomeScreenPlacementLocks`（#38、167〜172行目）、
    `HomeScreenOrganizerDiagnostics`（#138、173〜178行目）、
    `HomeScreenCategoryOverrides`（#99、179〜183行目）、
    `HomeScreenCustomCategories`（#336、184〜190行目）。
    いずれも既存label/summary string resource付きの`NavigationActionPreference`。
  - Personalization group（202〜238行目、heading `organizer_personalization_section`）:
    記録toggle（`prefs2.organizerPersonalizationRecording` adapterの
    `SwitchPreference`）とUsage Access行（`UsageAccess.isGranted(context)`を
    `ON_RESUME`で再読取する`ClickablePreference`、`DisposableEffect` +
    `LifecycleEventObserver`、spec 203 U-2）。独立destinationではなくinline構成。
  - 本fileは上記以外にorganizer要素を持たない（grid・lock home screen・dot pagination
    等はorganizer外であり削除対象外）。

### Route / navigation（変更しない面）

- `lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceRoutes.kt`
  - `HomeScreenPlacementLocks`（96〜98行目）、`HomeScreenCategoryOverrides`
    （100〜102行目）、`HomeScreenCustomCategories`（104〜107行目）、
    `HomeScreenOrganizerDiagnostics`（109〜112行目）: いずれも引数なし
    `@Serializable data object : PreferenceRoute`。
  - `OrganizationEntry`（117〜122行目、`MANUAL`/`ONBOARDING`）と
    `HomeScreenManualOrganization(entry)`（124〜133行目）。
- `lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceNavigation.kt`
  113〜128行目: 上記routesのcomposable登録。
  `composable<HomeScreenManualOrganization>`のみ`ManualOrganizationPreferences(trigger,
  onOpenDiagnostics)`を構成し、`onOpenDiagnostics`は
  `navigate(HomeScreenOrganizerDiagnostics)`。
- 材料4 destinationとdiagnostics destinationへのnavigation edgeは現状
  `HomeScreenPreferences`のrowのみである（他のsettings面・launcher側から
  これらrouteへのnavigate呼び出しはない。run面safe terminalの
  `onOpenDiagnostics`と、後述の#366 hubが例外）。

### 下位画面（変更しない面）

- `CategoryOverridePreferences.kt` / `CustomCategoryPreferences.kt` /
  `PlacementLockPreferences.kt` / `OrganizerDiagnosticsPreferences.kt`
  （いずれも`ui/preferences/destinations/`配下）: 画面内部に相互navigationや
  `LocalNavController`直接操作を持たない。diagnostics画面は`PreferenceLayout` +
  `OrganizerDiagnosticsExportPreference`（spec 138）。
- `ManualOrganizationPreferences.kt`: run面（spec 52/271/182/205/328のhost）。
  `PreferenceScaffold(label = manual_organization_title)`（318行目）、durable status
  render（848〜900行目）、safe terminalの「診断を開く」（`onOpenDiagnostics`）。
  本Issueでは無編集。
- lockのworkspace長押しdialog（T-20相当）: spec 38の既存flow外入口。触れない。

### onboarding hintと設定labelの結合（本Issueが触れる唯一のorganizer UI code）

- `lawnchair/src/app/lawnchair/organizer/ui/OrganizationOnboardingProposal.kt`
  - 427行目: onboarding提案の「確認」CTAが
    `HomeScreenManualOrganization(OrganizationEntry.ONBOARDING)`でrun面へ接続
    （本Issueでは変更しない。#370が所有）。
  - 578〜583行目: `reentryBodyText`がpath文言を
    `settings_button_text` → `home_screen_label` → `manual_organization_title` の
    3 label（format resource `organization_onboarding_reentry_hint_body`）から構成。
    コメント（575〜577行目）に「hintは実際にユーザーが見る設定labelから構成され
    driftしない（spec 232）」の契約がある。587行目`combinedAccessibilityText`。
- General groupのmanual organization直行rowを削除する場合（spec Contract notes 1）、
  hintの第3引数は実在しないrowを指すため、同一PRで実在label（#366のhub入口row）へ
  更新する必要がある。format resourceのplaceholder数は変わらない。

### 文字列

- `lawnchair/res/values/strings.xml` / `values-ja/strings.xml`:
  材料rowのlabel/summary（`organizer_lock_screen_title/summary`、
  `organizer_diagnostics_title`、`organizer_diagnostics_description`、
  `organizer_category_overrides_title/summary`、
  `organizer_custom_category_title/summary`）は画面title・hub導線labelとして
  引き続き使用される（削除しない）。
  Personalization系のうち、`organizer_personalization_recording_label/description`、
  `organizer_personalization_usage_access_label`、granted/not granted表記は#366の
  共有composable（hub T-06）が使用し続ける（削除しない）。
  `organizer_personalization_section`（values 1006行目 / values-ja 28行目、
  "Personalization"/「パーソナライゼーション」）のみ、設定側group削除後に
  使用者が存在しなくなる（#366の材料headingは`organizer_hub_materials_heading`を
  新設する計画）。対象localesはvaluesとvalues-jaのみ。

### testの現状

- `tests/organizer-instrumentation/app/lawnchair/organizer/ui/
  CategoryOverridePreferencesInstrumentationTest.kt`、
  `CustomCategoryPreferencesInstrumentationTest.kt`、
  `tests/organizer-instrumentation/app/lawnchair/organizer/locks/
  OrganizerLockScreenTest.kt`（178行目で`PlacementLockPreferences`を直接compose）:
  settings階層を経由しない直接composeのため、route変更（本Issueではnavigation edge
  のみ）の影響を受けない。
- `tests/organizer-instrumentation/app/lawnchair/ui/preferences/
  OrganizerDiagnosticsRouteInstrumentationTest.kt`
  - 231〜262行目 `homeScreenEntryNavigatesToDiagnosticsRouteShowingExportSurface`:
    `PreferenceNavigation(startDestination = HomeScreen)`（246行目）で
    `organizer_diagnostics_title` row（251行目）を探してclickする旧oracle。
    本IssueでHome screenからこのrowが消えるため更新必須。
  - 264行目以降 `safeTerminalOpenDiagnosticsRoutesThroughProductionGraphToExportSurface`:
    run面safe terminal経由（startDestination = `HomeScreenManualOrganization()`）。
    本Issueでも有効のまま維持するoracle。
- `tests/organizer-instrumentation/app/lawnchair/organizer/ui/
  OnboardingOrganizationProposalInstrumentationTest.kt`
  - 373〜386行目: hint announcementが実settings label
    （`settings_button_text` / `home_screen_label` / `manual_organization_title`）
    を含むことのassert。
  - 525〜560行目 `homeScreenSettingsShowsTheOrganizerEntryInGeneralAboveTheFold`:
    `PreferenceActivity.createIntent(context, HomeScreen)`で設定を開き、
    `manual_organization_title` rowがGeneral group内・first viewportにあることを
    assert（#232 oracle）。本Issueでrowが消えるため更新必須。
- `tests/organizer-instrumentation/app/lawnchair/organizer/ui/
  ManualOrganizationPreferencesInstrumentationTest.kt` /
  `ManualOrganizationProductionE2EInstrumentationTest.kt`:
  run面直接compose/harnessであり、settings row非依存。無編集greenの対象。
- Personalization group 2行専用の既存automated testは
  （organizer instrumentation一覧からは）確認できていない。#366がT-06機能の
  testを追加する計画であり、#367はsettings側インスタンス不在のassertを追加する。

### #366 draftが確定する接続面（branch `issue-366-spec-plan` `bf00f96175`、実装前）

- route `HomeScreenOrganizer`（引数なし）、destination `OrganizerHubPreferences.kt`、
  入口row（label `organizer_hub_title` / subtitle `organizer_hub_summary`、
  General groupのmanual organization row付近）、status card領域（durable status →
  「整理を開始」CTA → 診断導線）、材料group（T-02/T-03/T-04への
  `NavigationActionPreference` + T-06共有composable
  （仮称`OrganizerUsageMaterialRows`、`HomeScreenPreferences`から
  behavior-identical抽出）、heading `organizer_hub_materials_heading`）。
- #366段階(a)では設定側row群はすべて維持され、Personalization groupの2行は
  共有composable呼び出しに置換される。本Issueはその上でsettings側インスタンスを
  取り除く。
- disposition文書（PR #378内`docs/product/organizer-disposition-migration.md`）は
  proposed（未merge）であり、本planでは段階(b)の参照情報としてのみ扱い、
  確定契約としては引用しない。

## Design

### Modules and interfaces

- 新しいmodule / seam / route / destinationは作らない（AGENTS.md: 単なる委譲moduleを
  増やさない）。本Issueの全変更は既存面の構成（compose呼び出しの削除）と
  既存文字列参照の1箇所更新、test更新、docs表記である。
- 唯一の契約面: 設定 → Home screenのorganizer導線はhub入口row 1件に集約される
  （spec MAT-AC-02）。材料4 routeとrun面routeは引数含め不変であり、
  navigation edgeの起点だけがhubへ移る。

### Data flow

- 変更なし。材料編集・記録toggle・Usage Access遷移・診断exportは既存の
  store / adapter / app-op seamを直接使い、hubはnavigationの起点になるだけである。
- 削除対象rowのbacking stateは存続するため、削除・移設で読み書きされるdataの
  集合は変化しない。

### Alternatives rejected

- **manual organization直行rowを残し、hub入口rowと併存させる**: Issue受入2の
  「入口rowのみ」とD-01の「run入口...hubへ集約し、設定側は入口rowだけを残す」、
  TO-BE §5.2「整理の開始...すべてhub起点」に反する。併存は材料集約後も
  設定に2つのorganizer rowを残し、F-01（役割混在）の残存を意味する。
- **manual organization rowをhub入口rowへ「付け替え」、#366の入口rowを削除**:
  観測可能なend state（organizer row 1件・hub遷移）は本planの採用案と同一であり、
  どちらのrow objectが残るかは#366実装との整合で決まる実装詳細である。本planは
  end stateのみを拘束し、#366が追加した入口rowを残す形を既定とする（#366の
  `organizer_hub_title` labelがTO-BE語彙「Organizer」と対応し、hint更新の参照先も
  自明になるため）。
- **T-06を独立destinationとして新設**: #366が材料セクション内に使用状況materialを
  組み込む計画であり、2行のために追加hopを作る利点がない（spec Contract notes 2）。
- **設定側削除と同時に`HomeScreen*` routeオブジェクトを削除**: hub・run面safe
  terminal・onboardingがdestinationとして使用し続けるため不可能かつ不要。
- **Personalization group heading stringを残置**: 未使用resourceはspec 123収束
  （孤立表記の排除）に反するため同PRで削除する。

## Change set

| Path | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/HomeScreenPreferences.kt` | Layout groupのorganizer row 4件を削除。Personalization group（heading + 共有composable呼び出し）を削除。General groupのmanual organization直行rowを削除。不要import（`HomeScreenCategoryOverrides`等のroute、`UsageAccess`、lifecycle系）を整理 | 本Issueの本体。navigation edgeの削除のみで、残る面の構成は不変 |
| `lawnchair/src/app/lawnchair/organizer/ui/OrganizationOnboardingProposal.kt` | `reentryBodyText`の第3引数を`manual_organization_title`から実在するhub入口row label（#366の`organizer_hub_title`）へ変更（spec Contract notes 1が否認された場合は不要） | spec 232のpath真実性契約。onboarding flow接続（427行目）は変更しない |
| `lawnchair/res/values/strings.xml`, `lawnchair/res/values-ja/strings.xml` | `organizer_personalization_section`を削除（未使用化）。`organization_onboarding_reentry_hint_body`はplaceholder数不変のため無編集の想定（label差し替えのみで完結） | 孤立したuser-visible resourceの排除（MAT-AC-09） |
| `lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceRoutes.kt`, `PreferenceNavigation.kt` | 無編集 | route・destination・登録はすべて現行のまま |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt`, `CategoryOverridePreferences.kt`, `CustomCategoryPreferences.kt`, `PlacementLockPreferences.kt`, `OrganizerDiagnosticsPreferences.kt` | 無編集 | 下位画面は既存実装をそのまま受け、画面・store・lease契約の変更をしない |
| `tests/organizer-instrumentation/app/lawnchair/ui/preferences/OrganizerDiagnosticsRouteInstrumentationTest.kt` | `homeScreenEntryNavigatesToDiagnosticsRouteShowingExportSurface`を更新: Home screenのdiagnostics row不在assert + hub起点（`HomeScreenOrganizer` → 診断導線click）への付け替え。safe terminal test（264行目以降）は維持 | 旧oracleがHome screen上の削除rowに依存するため（MAT-AC-05）。obsolete理由（D-01材料集約）をPRに記録 |
| `tests/organizer-instrumentation/app/lawnchair/organizer/ui/OnboardingOrganizationProposalInstrumentationTest.kt` | hint path label assert（373〜386行目）と`homeScreenSettingsShowsTheOrganizerEntryInGeneralAboveTheFold`（525行目）を実在する新構造（hub入口row 1件、General group、first viewport）へ更新 | 同上（MAT-AC-05/07）。#232の再発見性assertは入口rowに対して維持する |
| `tests/organizer-instrumentation/app/lawnchair/organizer/ui/OrganizerHubPreferencesInstrumentationTest.kt`（#366が新設）または#367新規test class | settings側の否定的観測（4 row・Personalization group・manual organization行の不在）とT-06単一インスタンスassertを追加 | MAT-AC-02/06の自動化。#366のtest構成に合わせ、同一class拡張か新classかは実装時に判断 |
| `docs/assessment/evidence/issue-123-ui-mapping.md` | 設定側organizer rowのinventory行を削除・hub集約の注記へ更新 | spec 123 AC-1 inventory（MAT-AC-04） |
| `specs/38-lock-authoring-unknown-review/spec.md`, `specs/99-user-authored-category-overrides/spec.md`, `specs/336-user-defined-categories/spec.md`, `specs/203-usage-implicit-preference-signals/spec.md`, `specs/271-organizer-durable-status-projection/spec.md`, `specs/138-diagnostics-export-settings-route/spec.md` | 入口表記の追記のみ（契約節は変更しない）: 管理画面・run面・常設rowの入口がsettings rowからhub（T-01配下）経由になった旨。spec 99の§User-facing entry point、spec 138の入口規定、spec 203 U-2のsettings常設row表記、spec 271の「re-opened Settings」表記が対象候補 | Issue本文（「specs 38/99/336/271相当の表示面の入口表記を更新」「specs 38/99/336は入口表記の追記（契約不変）、spec 123 inventory更新」、受入4の203回帰）。#365 Non-goals「spec改訂は各実装IssueのPRで行う」に従い実装PRで実施 |
| `specs/367-organizer-materials-relocation/{spec,plan}.md` | status/history更新（実装PRで） | specs README rule |

変更しない: `organizer/application/**`、`organizer/planning/**`、`organizer/rules/**`、
`organizer/diagnostics/**`、`organizer/integration/**`、Launcher3 bridge、
`LawnchairApp.kt`、`PreferenceManager2` / preference定義、既存stringの値（1件削除を除く）。

## Migration and recovery

- schema / rule migrationなし。persistent state変更なし。`favorites` への接触なし
  （ホームレイアウト安全規約の適用対象外）。
- 移行期間なし: hub導線（#366）が先行提供済みであり、設定row削除で到達不能に
  なる機能はない。手動バックアップ等のundo概念は関与しない。
- rollback: PR revertでsettings row群が復活する。本Issueが書き得る状態は
  既存preference（記録toggle）への既存経路の書込みのみであり、revert後に
  不整合は残らない。
- downgrade / backup-restore: 影響なし（新規永続データなし）。材料storeの
  formatはspecs 99/336/38の現行schemaのまま。
- failure handling: navigation失敗・route復元は既存preferences navigationの
  挙動に従う。材料画面の読取失敗は各spec（99 fail-closed、336 external-corruption
  fail-closed、271 fail-closed）が既に所有し、本Issueで変化しない。

## Risk assessment

- **risk: low**。UI構成の削除・参照差し替え・test更新・docs表記が主体。
  `organizer/application/**`、Launcher3 writer系を含まないため、workflowの
  high-risk evidence gate（`risk: layout-data` / `risk: migration`）の対象外。
- リスク点1: General groupのmanual organization直行row削除は、spec Contract notes 1の
  解釈に基づく。owner reviewで否認された場合はscope縮小（row維持）へ
  変更する（hint更新とテスト更新の一部が不要になる）。spec acceptance前に
  解消されるべき点であり、実装着手の前段として扱う。
- リスク点2: 削除rowへの依存が他にも潜む可能性（画面上のpath説明文、内部test、
 将来のdoc）。→ 本planのCurrent evidenceのgrep結果（route参照は
  `HomeScreenPreferences`・`PreferenceNavigation`・2 test file・onboardingの
  `HomeScreenManualOrganization`のみ）で把握済みだが、実装PRで
  `organizer_lock_screen_title`等のlabel参照を再grepして漏れを確認する。
- リスク点3: #366が未実装のため、本planの#366接続面（route名・composable名・
  string名）はdraft時点の計画である。→ snapshot re-entry ruleにより#366実装後に
  再確認し、相違があれば本spec/planを更新してから着手する。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| MAT-AC-01 | instrumentation: hub → T-02/T-03/T-04 navigation、T-06行機能、hub → diagnostics。emulator screenshot（ja/default × light/dark） | `connectedLawnWithQuickstepGithubDebugAndroidTest` 対象class filter（#366 hub test拡張 or #367新class） |
| MAT-AC-02 | instrumentation: settings → Home screenのorganizer row不在assert（4 row・Personalization・manual organization）+ hub入口row存在・遷移assert | 同上 |
| MAT-AC-03 | 既存test無編集green（`CategoryOverridePreferencesInstrumentationTest`、`CustomCategoryPreferencesInstrumentationTest`、`OrganizerLockScreenTest`、`ManualOrganizationPreferencesInstrumentationTest`）+ diff review（契約面無編集） | 同上 + `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` |
| MAT-AC-04 | specs 38/99/336/203/271/138の入口表記diff review + `issue-123-ui-mapping.md` diff | PR diff review |
| MAT-AC-05 | `OrganizerDiagnosticsRouteInstrumentationTest`・`OnboardingOrganizationProposalInstrumentationTest`更新diff + 実行結果 + obsolete理由のPR記録 | 対象class filter実行 |
| MAT-AC-06 | instrumentation: T-06 toggle ↔ preference一致、`ON_RESUME`再読取、settings側インスタンス不在 | 同上 |
| MAT-AC-07 | hint path label assert（更新後label）+ `organization_onboarding_reentry_hint_body`のEN/ja diff確認 | 同上 + string diff review |
| MAT-AC-08 | `OrganizerLockScreenTest`（long-press popup経由oracle）green | 同上 |
| MAT-AC-09 | `organizer_personalization_section`のreference grep（0件）+ values/values-ja双方からの削除確認 | `git grep organizer_personalization_section` + `./gradlew spotlessCheck` |

含めるべき観点: UI（navigation・行構造・否定的観測）、既存回帰（材料画面・run面の
無編集green）、accessibility（hint announcement、hub側は#366受入を維持）、
localization（削除stringのlocale一貫性、hint format resource）、
failure injection（対象外——本Issueは読取契約を変更しない。既存seam testが所有）。

## Dependencies and ordering

1. **spec/plan owner acceptance**（workflow Start gate。本taskはdraftまで。
   spec Contract notes 1の解釈確認を含む）。
2. **#365 merge（正本改訂、docs-only）**: 実装着手の前提（#366と同一判定）。
3. **#366実装のmerge**: hub destination・材料セクション・T-06共有composable・
   hub入口rowの実体が前提。merge後に本planのCurrent evidence（#366接続面）を
   再確認する（snapshot re-entry rule）。
4. **本Issue実装**（1 PR想定。source + test + specs入口表記 + spec 123 inventoryを
   同PRへ）: 手順はExecution checklist。
5. **後続**: #368（strategy移設。本Issue完了前提）、#371（JIT。T-06常設row前提）、
   #370（onboarding/hint接続変更。本Issueのhint更新を上書きし得る）。

## Explicitly unverified areas

- #366の最終実装形態（route名・`OrganizerHubPreferences`の構成・共有composableの
  配置と名称・入口row label）。本planはdraft `bf00f96175`時点の計画に基づく。
- Personalization group 2行に対する既存automated testの不在確認（一覧上は未発見だが、
  全test fileの網羅確認はしていない。#366がT-06 testを追加するため、不在でも
  本Issueのassertで補われる）。
- `HomeScreenPreferences`のtwo-pane（expanded）描画に対する削除の影響
  （既存規約に従うため影響想定なし。実装時のemulator evidenceで確認）。
- 削除により未使用化するstringが`organizer_personalization_section`のみであることの
  最終確認（実装PRのreference grepで確定）。

## Documentation updates

- [ ] `specs/367-organizer-materials-relocation/spec.md` / `plan.md` status・history（本PR）
- [ ] specs 38/99/336/203/271/138 入口表記追記（本PR。Issue本文の要求）
- [ ] `docs/assessment/evidence/issue-123-ui-mapping.md`（spec 123 AC-1。本PR）
- [ ] `CONTEXT.md`: 実施しない（材料語彙は#365のownership）
- [ ] `DESIGN.md`: 実施しない（§4.4 UI adapterの記述範囲内。新面を作らないため）
- [ ] ADR: 不要（IA決定の選択肢比較はorganizer-to-be-ux.md §4に記録済み）

## Execution checklist

1. [ ] 実装開始条件の確認: 本spec/plan `accepted`、#365 merge済み、#366実装merge済み、
       main再確認（Current evidenceの再検証。#366接続面の名称一致を含む）。
2. [ ] 失敗するtestを先に: settings側organizer row不在assertとhub → 材料/診断
       navigation assertを追加（MAT-AC-01/02を赤で固定）。
3. [ ] `HomeScreenPreferences.kt`からorganizer row 4件・Personalization group・
       manual organization直行rowを削除し、import整理（MAT-AC-02）。
4. [ ] hint label差し替え + `OnboardingOrganizationProposalInstrumentationTest` /
       `OrganizerDiagnosticsRouteInstrumentationTest`更新（MAT-AC-05/07）。
5. [ ] `organizer_personalization_section`削除 + reference grep（MAT-AC-09）。
6. [ ] specs 38/99/336/203/271/138の入口表記追記 + spec 123 inventory更新
       （MAT-AC-04）。contract節に触れていないことをdiff reviewで確認。
7. [ ] 既存test（材料画面直接compose系・run面系・lock popup系）が無編集でgreenである
       ことの確認（MAT-AC-03/08）。
8. [ ] full verification + evidence記録 + PR（`Refs #367`。最終PRのみ`Closes #367`を
       検討。workflow契約に従う）。
