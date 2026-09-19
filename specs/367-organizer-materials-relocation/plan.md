# Implementation Plan: 材料面のhub集約と設定側organizer row廃止

> Issue: #367
> Spec: [spec.md](./spec.md)
> Status: accepted（実装開始。最終review「追加指摘なし / Approve相当」
> https://github.com/nunu1733/NunuLauncher/issues/367#issuecomment-5741181088 、head `dd09ef1a18`）

## Current evidence

以下はすべてorigin/main `1285c13cc6`（2026-09-19、PR #381 merge後）で実測確認した
現行実装の事実である。起草時（baseline `3076bdae7e`）からの差分（PR #378 disposition、
PR #379 #365正本改訂、PR #380 #366実装、PR #381 docs）を再照合済みであり、#366の
接続面はdraft計画値ではなく実装実測値である。

### 設定 → Home screenの現行構造（削除対象）

- `lawnchair/src/app/lawnchair/ui/preferences/destinations/HomeScreenPreferences.kt`
  - General group内96〜110行目付近: manual organization直行row
    （`NavigationActionPreference`、label `manual_organization_title`、destination
    `HomeScreenManualOrganization()`、#232昇進コメント付き）と、hub入口row
    （label `organizer_hub_title` / subtitle `organizer_hub_summary`、destination
    `HomeScreenOrganizer`、#366コメント付き）。**本Issueではこの2行を変更しない**
    （Contract notes 1: manual rowは#370が廃止する）。
  - Layout group内168〜191行目付近のorganizer系row 4件（削除対象）:
    `HomeScreenPlacementLocks`（#38）、`HomeScreenOrganizerDiagnostics`（#138）、
    `HomeScreenCategoryOverrides`（#99）、`HomeScreenCustomCategories`（#336）。
    いずれも既存label/summary string resource付きの`NavigationActionPreference`。
  - Personalization group（209〜211行目付近、削除対象）:
    `PreferenceGroup(heading = organizer_personalization_section) { OrganizerUsageMaterialRows() }`。
    #366で2行のcompose内容は共有composable `OrganizerUsageMaterialRows`
    （`OrganizerUsageMaterialRows.kt`。記録toggle + Usage Access行、`ON_RESUME`再読取、
    spec 203 U-2）へ抽出済みであり、settings側の削除はこのgroupの呼び出し削除のみで
    完結する（row内部の再実装・移動は不要）。
  - 本fileの上記以外（grid・lock home screen・dot pagination・widget等）はorganizer外
    であり削除対象外。

### Hub（#366実装済み、変更しない面）

- `lawnchair/src/app/lawnchair/ui/preferences/destinations/OrganizerHubPreferences.kt`:
  status card第1段階（durable status行＋checking行＋開始CTA）→ 診断常設行
  （`organizer_diagnostics_title`、`HomeScreenOrganizerDiagnostics`へ）→ 材料group
  （heading `organizer_hub_materials_heading`、T-02/T-03/T-04への
  `NavigationActionPreference`＋`OrganizerUsageMaterialRows()`）。
  本Issueでhub destinationは無編集である（材料セクション・診断導線は実装済み）。
- `PreferenceRoutes.kt`（94〜107行目付近）・`PreferenceNavigation.kt`: organizer系route
  （`HomeScreenPlacementLocks` / `HomeScreenCategoryOverrides` /
  `HomeScreenCustomCategories` / `HomeScreenOrganizerDiagnostics` / `HomeScreenOrganizer` /
  `HomeScreenManualOrganization(entry)`）はすべて本Issueで無編集。材料4 destinationと
  diagnostics destinationへのnavigation edgeは、本Issue後はhubとrun面safe terminalのみが
  持つ（settings Layout groupのedgeを削除）。

### 下位画面（変更しない面）

- `CategoryOverridePreferences.kt` / `CustomCategoryPreferences.kt` /
  `PlacementLockPreferences.kt` / `OrganizerDiagnosticsPreferences.kt`
  （いずれも`ui/preferences/destinations/`配下）: 画面内部に相互navigationや
  `LocalNavController`直接操作を持たない。
- `ManualOrganizationPreferences.kt`: run面（spec 52/271/182/205/328のhost）。本Issueでは
  無編集。safe terminalの「診断を開く」（`onOpenDiagnostics` →
  `HomeScreenOrganizerDiagnostics`）は現行どおり機能し続ける。
- lockのworkspace長押しdialog（T-20相当）: spec 38の既存flow外入口。触れない。

### onboarding hintと設定labelの結合（本Issueでは触れない）

- `lawnchair/src/app/lawnchair/organizer/ui/OrganizationOnboardingProposal.kt`:
  427行目付近の「確認」CTA接続（`HomeScreenManualOrganization(OrganizationEntry.ONBOARDING)`）と、
  578〜583行目付近の`reentryBodyText`（path文言を`settings_button_text` →
  `home_screen_label` → `manual_organization_title`の3 labelから構成。spec 232の
  真実性契約）は**いずれも本Issueで無編集**である。#367段階では参照先の
  `manual_organization_title` rowがGeneral groupに存続するため、hintのpath文言は
  真実であり続ける。差し替えは#370（hub入口row labelへの更新）が所有する。

### 文字列

- `lawnchair/res/values/strings.xml` / `values-ja/strings.xml`:
  材料rowのlabel/summary（`organizer_lock_screen_title/summary`、
  `organizer_diagnostics_title/description`、`organizer_category_overrides_title/summary`、
  `organizer_custom_category_title/summary`）はhub導線label・画面titleとして
  引き続き使用される（削除しない）。
  Personalization系のうち、`organizer_personalization_recording_label/description`、
  `organizer_personalization_usage_access_label`、granted/not granted表記は
  `OrganizerUsageMaterialRows`（hub T-06）が使用し続ける（削除しない）。
  `organizer_personalization_section`（values 1011行目 / values-ja 33行目）のみ、
  設定側group削除後に使用者が存在しなくなる（reference grepで
  `HomeScreenPreferences.kt:209` の1箇所のみ確認済み）。対象localesはvaluesと
  values-jaのみ。
- `organization_onboarding_reentry_hint_body`（format resource、EN/ja）は
  placeholder数・文言とも無編集である（label参照先を本Issueでは変えないため）。

### testの現状

- `tests/organizer-instrumentation/app/lawnchair/organizer/ui/
  CategoryOverridePreferencesInstrumentationTest.kt`、
  `CustomCategoryPreferencesInstrumentationTest.kt`、
  `tests/organizer-instrumentation/app/lawnchair/organizer/locks/OrganizerLockScreenTest.kt`:
  settings階層を経由しない直接composeのため、navigation edge削除の影響を受けない。
  ただし`CustomCategoryPreferencesInstrumentationTest`は（CI lane未登録のため）
  test infrastructureの既存不備を抱えており、実装review対応でfakeのCreate報告と
  rename行matcherを修正した（Change set参照。production code・観測対象は無編集）。
  `CategoryOverride`・`OrganizerLockScreenTest`は無編集greenの対象。
- `tests/organizer-instrumentation/app/lawnchair/ui/preferences/
  OrganizerDiagnosticsRouteInstrumentationTest.kt`
  - 231〜260行目 `homeScreenEntryNavigatesToDiagnosticsRouteShowingExportSurface`:
    production navigation graph（`PreferenceNavigation(startDestination = HomeScreen)`、
    `LauncherAppState.getInstance(context)`でproduction環境をmirror、
    `LocalPreferenceInteractor`提供）でHome screenの`organizer_diagnostics_title` rowを
    scroll＋clickする旧oracle。本IssueでHome screenからこのrowが消えるため**更新必須**
    （hub経由への付け替え）。
  - 270〜317行目 `safeTerminalOpenDiagnosticsRoutesThroughProductionGraphToExportSurface`:
    run面safe terminal経由。本Issueでも有効のまま維持するoracle。
- `tests/organizer-instrumentation/app/lawnchair/organizer/ui/
  OnboardingOrganizationProposalInstrumentationTest.kt`
  - 351〜396行目付近 `laterTapShowsTheReentryHintAndPreservesTheDeferOutcome`:
    hint announcementが`settings_button_text` / `home_screen_label` /
    `manual_organization_title`を含むことのassert。**無編集で維持**（参照先row存続）。
  - 525行目付近 `homeScreenSettingsShowsTheOrganizerEntryInGeneralAboveTheFold`:
    #232 oracle（General group・first viewport）。**無編集で維持**（manual row存続）。
- `tests/organizer-instrumentation/app/lawnchair/organizer/ui/
  OrganizerHubPreferencesInstrumentationTest.kt`（#366で新設）: hub直接composeのため
  settings row削除の影響を受けない。
  `recordingToggleSharesOnePreferenceAcrossSurfaces`は共有composableをColumn内で
  2インスタンス合成するtestであり、`HomeScreenPreferences`への依存がないため
  無編集でgreenを維持する。
- `ManualOrganizationPreferencesInstrumentationTest.kt` /
  `ManualOrganizationProductionE2EInstrumentationTest.kt`: run面直接compose/harnessで
  settings row非依存。無編集greenの対象。
- 設定 → Home screen全体のproduction graph renderは、上記diagnostics route testの
  harness pattern（`PreferenceNavigation(startDestination = HomeScreen)` +
  `LauncherAppState.getInstance(context)` + `LocalPreferenceInteractor`）で実績があり、
  settings側の否定的観測（MAT-AC-02/06）はこのharnessを流用して追加する。

## Design

### Modules and interfaces

- 新しいmodule / seam / route / destinationは作らない（AGENTS.md: 単なる委譲moduleを
  増やさない）。本Issueの全変更は既存面の構成（compose呼び出しの削除）、未使用stringの
  1件削除、test更新、docs表記である。
- 唯一の契約面: 設定 → Home screenの材料系organizer導線が消え、材料・診断への
  navigation edgeがhubとrun面safe terminalに集約される（spec MAT-AC-02）。
  材料4 route・run面routeは引数含め不変であり、navigation edgeの起点だけが移る。
- manual organization直行rowとhub入口row（General group）は無編集である（暫定併存。
  Contract notes 1）。

### Data flow

- 変更なし。材料編集・記録toggle・Usage Access遷移・診断exportは既存の
  store / adapter / app-op seamを直接使い、hubはnavigationの起点であるだけである。
- 削除対象rowのbacking stateは存続するため、削除で読み書きされるdataの集合は
  変化しない。

### Alternatives rejected

- **General groupのmanual organization直行rowを本Issueで削除する**: owner reviewで
  否認された解釈（draft Contract notes 1）。accepted spec 232 AC-3を#370まで維持する
  ことがdisposition §2.3/§5のownershipであり、runtimeだけ先取りする形は不可
  （review明記）。#370でmanual row廃止＋spec 232/53改訂＋hint更新を同一PRで行う。
- **manual organization rowをhub入口rowへ「付け替え」**:
  観測可能なend stateは#370後の契約と同一だが、その移行は#370の責務であり、本Issueでは
  既存2行をそのまま残す（#366実装の入口row・#232昇進rowともに無編集）。
- **T-06を独立destinationとして新設**: #366実装済みの共有composable（材料セクション内
  組み込み）が現行であり、2行のために追加hopを作る利点がない（spec Contract notes 2）。
- **設定側削除と同時に`HomeScreen*` routeオブジェクトを削除**: hub・run面safe
  terminal・（#370までの）settings manual rowがdestinationとして使用し続けるため
  不可能かつ不要。
- **`organizer_personalization_section`を残置**: 未使用resourceはspec 123収束
  （孤立表記の排除）に反するため同PRで削除する。

## Change set

| Path | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/HomeScreenPreferences.kt` | Layout groupのorganizer row 4件を削除。Personalization group（heading＋`OrganizerUsageMaterialRows()`呼び出し）を削除。不要import（`HomeScreenCategoryOverrides`等のroute、`OrganizerUsageMaterialRows`等）を整理。**General groupの2行（manual row＋hub入口row）は無編集** | 本Issueの本体。navigation edgeの削除のみで、残る面の構成は不変 |
| `lawnchair/res/values/strings.xml`, `lawnchair/res/values-ja/strings.xml` | `organizer_personalization_section`を削除（未使用化）。`organization_onboarding_reentry_hint_body`は無編集（label参照先を変えないため） | 孤立したuser-visible resourceの排除（MAT-AC-08） |
| `lawnchair/src/app/lawnchair/organizer/ui/OrganizationOnboardingProposal.kt`, `PreferenceRoutes.kt`, `PreferenceNavigation.kt`, `OrganizerHubPreferences.kt`, `OrganizerUsageMaterialRows.kt`, 材料・診断・run面各destination | 無編集 | #370/#366の所有面。route・destination・登録・hintはすべて現行のまま |
| `tests/organizer-instrumentation/app/lawnchair/ui/preferences/OrganizerDiagnosticsRouteInstrumentationTest.kt` | `homeScreenEntryNavigatesToDiagnosticsRouteShowingExportSurface`を更新: （1）Home screenで材料系row（diagnostics含む4 label＋personalization 2 label）の不在assert（全list走査後）、（2）hub入口row click → hub render → hub材料セクションのdiagnostics row click → export surface表示、への付け替え。safe terminal test（270行目以降）は維持 | 旧oracleがHome screen上の削除rowに依存するため（MAT-AC-02/05）。obsolete理由（D-01材料集約・TO-BE §5.2）をPRに記録 |
| `tests/organizer-instrumentation/app/lawnchair/organizer/ui/` 配下（新規class。#366の`OrganizerHubPreferencesInstrumentationTest`拡張か#367新classかは実装時に判断） | settings側の否定的観測（Layout group 4 row・Personalization group 2行の不在、hub入口row・manual rowの残存）とT-06単一インスタンスassert（Home screen走査後にT-06行不在→hubでT-06行機能・preference一致）を追加 | MAT-AC-02/06/07の自動化。既存production graph harness patternを踏襲 |
| `tests/organizer-instrumentation/app/lawnchair/organizer/ui/OnboardingOrganizationProposalInstrumentationTest.kt` | **無編集**（hint oracle・#232 oracleの維持を回帰証拠とする） | spec 232 AC-3は#370まで不変（MAT-AC-07） |
| `tests/organizer-instrumentation/app/lawnchair/organizer/ui/CustomCategoryPreferencesInstrumentationTest.kt` | test-infrastructure修正のみ: `FakeCatalogStore.mutate`のCreate commitがverified-Create契約（Committedはminted entryを報告）どおりminted entryを返すよう修正。rename行matcherをvisible text → `onNodeWithContentDescription`へ。production code・oracle観測対象は無編集 | 同classはCI lane未登録のためbaseでも失敗していた既存不備。実装review指摘（MAT-AC-03の実装対応とplan整合）に基づき本PRで修正し、class全体を安定greenにした |
| `docs/assessment/evidence/issue-123-ui-mapping.md` | 設定側organizer rowのinventory行を削除・hub集約の注記へ更新 | spec 123 AC-1 inventory（MAT-AC-04） |
| `docs/product/organizer-disposition-migration.md` | 段階ownershipの明確化（本branchで実施済み。§2.3 spec 232行、§3.10二段階所有、§5 rows 4/5、§4.1 spec 203行、§7.2(b)、status追記）: #367=材料rowのみ、#370=manual organization直行row廃止＋spec 232 AC-3改訂＋hint更新（D-01の完成）、spec 203=配置改訂（#367）→JIT（#371）の二段階 | #367 re-review指摘の解消。正本側でownershipを閉じないと段階契約（MAT-AC-02）が根拠を欠く |
| `specs/38-lock-authoring-unknown-review/spec.md`, `specs/99-user-authored-category-overrides/spec.md`, `specs/336-user-defined-categories/spec.md`, `specs/138-diagnostics-export-settings-route/spec.md` | 入口表記の追記のみ（契約節は変更しない）: 38/99/336はdisposition割当の「入口はhub経由になった旨」、138はLayout group入口 → hub経由の注記（destination・route不変） | Issue本文（「specs 38/99/336は入口表記の追記（契約不変）」）＋実態との整合。#365 Non-goals「spec改訂は各実装IssueのPRで行う」に従い実装PRで実施 |
| `specs/203-usage-implicit-preference-signals/spec.md` | U-2の常設row配置の限定的改訂: 「settingsのOrganizerセクションに常設」（Permission and fallback behavior表のopt-in行とU-2 decision noteの両方）を「hub T-06に常設」へ改訂。no-JIT・fallback・`ON_RESUME`再読取・拒否時section availability等の他規定は不変。JIT要求には触れない（#371が所有） | U-2のplacementはnormative記述であり、settings row削除後に旧文言を残すとspec 203が自己矛盾する（re-review指摘）。disposition §3.10の二段階所有（配置= #367、JIT= #371）に基づく。旧settings配置をnormative stateとして残さないことをdiff reviewで確認 |
| `specs/271-organizer-durable-status-projection/spec.md` | 原則無編集。実装PRで「run面のdurable status表示契約は本Issueで不変」を確認し、文言drift（re-opened Settings表記等）が見つかった場合のみ入口注記を検討 | spec 271の契約面（run面・初期化経路）は本Issueで無変更のため |
| `specs/367-organizer-materials-relocation/{spec,plan}.md` | status/history更新（実装PRで） | specs README rule |

変更しない: `organizer/application/**`、`organizer/planning/**`、`organizer/rules/**`、
`organizer/diagnostics/**`、`organizer/integration/**`、Launcher3 bridge、
`LawnchairApp.kt`、`PreferenceManager2` / preference定義、
`OrganizationOnboardingProposal.kt`、既存stringの値（1件削除を除く）。

## Migration and recovery

- schema / rule migrationなし。persistent state変更なし。`favorites` への接触なし
  （ホームレイアウト安全規約の適用対象外）。
- 移行期間なし: hub導線（#366）が先行提供済みであり、設定row削除で到達不能になる
  機能はない。手動バックアップ等のundo概念は関与しない。
- rollback: PR revertでsettings row群が復活する。本Issueが書き得る状態は
  既存preference（記録toggle）への既存経路の書込みのみであり、revert後に
  不整合は残らない。
- downgrade / backup-restore: 影響なし（新規永続データなし）。材料storeのformatは
  specs 99/336/38の現行schemaのまま。
- failure handling: navigation失敗・route復元は既存preferences navigationの挙動に
  従う。材料画面の読取失敗は各spec（99 fail-closed、336 external-corruption
  fail-closed、271 fail-closed）が既に所有し、本Issueで変化しない。

## Risk assessment

- **risk: low**。UI構成の削除・未使用string削除・test更新・docs表記が主体。
  `organizer/application/**`、Launcher3 writer系を含まないため、workflowの
  high-risk evidence gate（`risk: layout-data` / `risk: migration`）の対象外である。
- リスク点1: 設定 → Home screenの削除rowに対する否定的観測は、`PreferenceLazyColumn`
  の未compose nodeがsemantics treeに現れないため、全list走査（末尾の既存nodeまで
  scroll）を先に実行したうえで`assertDoesNotExist()`を行う必要がある。走査漏れがあると
  削除rowの検証が形だけになる。→ 走査はlist末尾の既存node（widget group等）到達を
  終端条件とし、test内で明示する。
- リスク点2: 削除rowへの依存が他に潜む可能性（画面上のpath説明文、内部test、将来doc）。
  → 本planのCurrent evidenceでroute参照・label参照のgrep結果（route参照は
  `HomeScreenPreferences`・`PreferenceNavigation`・diagnostics route test・onboardingの
  `HomeScreenManualOrganization`のみ、`organizer_personalization_section`参照は
  `HomeScreenPreferences.kt`の1箇所のみ）で把握済み。実装PRで
  `organizer_lock_screen_title`等のlabel参照を再grepして漏れを確認する。
- リスク点3: diagnostics route testの更新でhub destinationをproduction graph経由で
  composeする際、`OrganizerHubPreferences`が`ManualOrganizationModule.get(context)`を
  解決する。既存diagnostics testのharness（`LauncherAppState.getInstance(context)`の
  mirror＋必要なら`installProcessLocalRunner` fixture）を踏襲し、production wiringと
  同一の初期化経路を通す。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| MAT-AC-01 | instrumentation: hub → T-02/T-03/T-04 navigation、T-06行機能、hub → diagnostics（#366 hub oracleの再実行含む）。emulator screenshot（ja/default × light/dark） | `connectedLawnWithQuickstepGithubDebugAndroidTest` 対象class filter |
| MAT-AC-02 | instrumentation: settings → Home screenの材料row不在assert（4 row＋Personalization group 2行、全list走査後）＋hub入口row・manual row存在assert＋hub遷移assert | 同上 |
| MAT-AC-03 | 既存testのgreen（材料画面直接compose系、run面系。CustomCategoryのみtest-infrastructure修正あり。#232/hint oracleと他の材料画面classは無編集green）+ diff review（契約面無編集。spec 203はU-2配置改訂のみ例外で他規定無変更） | 同上 + `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` |
| MAT-AC-04 | specs 38/99/336/138の入口表記diff review + spec 203のdiff review（U-2配置のみ改訂、JIT/fallback/`ON_RESUME`規定無変更、旧settings配置のnormative記述が残っていないこと）+ `issue-123-ui-mapping.md` diff + disposition改訂diff（本branch分。段階ownershipの記載確認） | PR diff review |
| MAT-AC-05 | `OrganizerDiagnosticsRouteInstrumentationTest`更新diff + 実行結果 + obsolete理由のPR記録 | 対象class filter実行 |
| MAT-AC-06 | instrumentation: Home screen走査後にT-06行不在、hubでT-06行機能・preference一致・`ON_RESUME`再読取 | instrumentation |
| MAT-AC-07 | `OnboardingOrganizationProposalInstrumentationTest`無編集green + General group行残存assert | 同上 |
| MAT-AC-08 | `OrganizerLockScreenTest`（long-press popup経由oracle）green + `organizer_personalization_section`のreference grep（0件）+ values/values-ja双方からの削除確認 | `git grep organizer_personalization_section` + `./gradlew spotlessCheck` |

含めるべき観点: UI（navigation・行構造・否定的観測）、既存回帰（材料画面・run面・
#232/hint oracleの無編集green）、accessibility（hub側は#366受入を維持。hint announcement
の真実性は参照先row存続で維持）、localization（削除stringのlocale一貫性）、
failure injection（対象外——本Issueは読取契約を変更しない。既存seam testが所有）。

## Dependencies and ordering

1. **spec/plan owner acceptance**（workflow Start gate。本taskはdraftまで）。
2. **前提（充足済み）**: #365 merge済み（PR #379、2026-09-19。`CONTEXT.md` 語彙確定）、
   #366実装merge済み（PR #380、merge commit `32c72094a4`）。本planのCurrent evidenceは
   origin/main `1285c13cc6`で再検証済み（re-entry rule充足）。
3. **本Issue実装**（1 PR想定。source + test + specs入口表記 + spec 123 inventoryを
   同PRへ）: PR作成時にIssue #367へ段階契約の記録（MAT-AC-02の解釈）を行う。
   #370への責務明記は本branchで完了済み（disposition改訂＋#370本文編集＋記録コメント）。
4. **後続**: #368（strategy移設。本Issue完了前提）、#371（JIT。T-06常設row前提）、
   #370（manual row廃止＋specs 232/53改訂＋hint更新。段階契約の完成）。

## Explicitly unverified areas

- 設定 → Home screenのproduction graph harnessでhub destinationまで遷移した際の
  `ManualOrganizationModule.get(context)`の動作（既存diagnostics testの
  `LauncherAppState` mirrorで充足するか、fixture runnerのinstallが必要か）。実装時に
  既存harness patternを確認して踏襲する。
- `HomeScreenPreferences`のtwo-pane（expanded）描画に対する削除の影響（既存規約に
  従うため影響想定なし。実装時のemulator evidenceで確認）。
- 削除により未使用化するstringが`organizer_personalization_section`のみであることの
  最終確認（実装PRのreference grepで確定）。
- spec 271の入口表記に関する文言driftの有無（実装PRで確認。原則無編集）。

## Documentation updates

- [ ] `specs/367-organizer-materials-relocation/spec.md` / `plan.md` status・history（本PR）
- [x] `docs/product/organizer-disposition-migration.md` 段階ownership明確化（本branch。
       re-review指摘1の解消）
- [ ] specs 38/99/336/138 入口表記追記、spec 203 U-2配置改訂（本PR。Issue本文＋
       disposition割当）
- [ ] `docs/assessment/evidence/issue-123-ui-mapping.md`（spec 123 AC-1。本PR）
- [x] Issue #370本文編集＋記録コメント（本branch。re-review指摘1の解消）
- [ ] Issue #367への段階契約記録（MAT-AC-02の解釈。PR作成時）
- [ ] `CONTEXT.md`: 実施しない（材料/hub語彙は#365で確定済み）
- [ ] `DESIGN.md`: 実施しない（§4.4 UI adapterの記述範囲内。新面を作らないため）
- [ ] ADR: 不要（IA決定の選択肢比較はorganizer-to-be-ux.md §4に記録済み）

## Execution checklist

1. [x] 実装開始条件の確認: 本spec/plan `accepted`、main再確認
       （Current evidenceはorigin/main `1285c13cc6`で検証済み。実装着手時に再確認）。
2. [x] 失敗するtestを先に: settings側材料row不在assertとhub起点navigation assertを
       追加（MAT-AC-02/05/06を赤で固定。source差分をstashした赤実行で
       `assertDoesNotExist`失敗を確認→source適用で緑）。
3. [x] `HomeScreenPreferences.kt`からorganizer row 4件・Personalization groupを削除し、
       import整理（MAT-AC-02）。General groupの2行は無編集であることをdiffで確認。
4. [x] `OrganizerDiagnosticsRouteInstrumentationTest`のsettings entry oracleを
       hub起点へ更新（MAT-AC-05）。
5. [x] `organizer_personalization_section`削除 + reference grep（MAT-AC-08。0件確認）。
6. [x] specs 38/99/336/138の入口表記追記、spec 203のU-2配置改訂（2箇所）、
       spec 123 inventory更新（MAT-AC-04）。
7. [x] 既存testのgreen確認（MAT-AC-03/07/08）: run面系・lock popup系・#232/hint
       oracle・`CategoryOverride`は無編集でgreen。`CustomCategory`のみ
       test-infrastructure修正（fake Create報告＋rename matcher）のうえgreen。
       下記Implementation notes参照。
8. [ ] full verification + evidence記録 + PR（`Closes #367`）＋ Issue #367への
       段階契約記録（PR本文とIssue comment）。

## Implementation notes（実装時の確定事項、2026-09-19）

- **CI lane追加**: `app.lawnchair.ui.preferences.OrganizerDiagnosticsRouteInstrumentationTest`を
  CI `organizer-instrumentation-issue52-tests` jobのclass listへ追加した（#366と同一
  のevidence要件。本classは従来lane未登録であったため、以後CIで常時実行される）。
- **safe terminal oracleの修正（test-only）**: 同classの
  `safeTerminalOpenDiagnosticsRoutesThroughProductionGraphToExportSurface`が、
  viewport高さの低いAVDでdecision pair（confirm/cancel）がbelow-the-foldで未composeの
  ためclick不能であった（base codeでも再現。本classがlane未登録のため未発見だった）。
  #366のc2efd2178aと同型のscroll-into-view修正（`performScrollToNode` → click）を
  施し、診断rowも同様にscroll後clickへ変更した。観測対象（route遷移とexport surface
  表示）は不変。
- **新testはfixture runner経由**: `homeScreenMaterialsRelocationRoutesDiagnosticsThroughHub`は
  `installProcessLocalRunner`で軽量fixture（Idle/never-organized）をinstallしてから
  production route graphをcomposeする。production module（実reconciliation起動）を
  同一processで作らないことで、他testとの環境干渉を避ける。diagnostics destinationが
  `layoutApplicationModule`を直接読むため`LauncherAppState.getInstance(context)`の
  mirrorは維持（旧oracleと同一）。
- **材料row不在assertの方法**: `PreferenceLazyColumn`の未compose rowはsemantics treeに
  現れないため、全list走査（末尾の`force_widget_resize_label`行をsentinelに段階scroll、
  各stepで6 labelの`assertDoesNotExist`）で行う。T-06単一インスタンスはhub側で
  `assertCountEquals(1)`。
- **検証結果（local AVD `issue209_pixel_7_pro`、CI issue-52 lane相当）**:
  CI lane class list 8 class（E2E/run面/hub/strategy picker/missing app/exchange
  import/strategy freeze/本PR更新のdiagnostics route）を1 invocationでgreen。
  onboarding lane（`OnboardingOrganizationProposalInstrumentationTest`＋
  `InjectedInputEnvironmentStateInstrumentationTest`）green（#232 oracle・hint oracle
  無編集維持=MAT-AC-07）。`OrganizerLockScreenTest` green（MAT-AC-08）。
  `CategoryOverridePreferencesInstrumentationTest` green。unit gate・spotlessCheck green。
  emulator screenshot 6枚（EN/ja × light/dark。`docs/assessment/evidence/issue-367/`）。
- **既存のpre-existing failure → 実装review対応で修正**:
  `CustomCategoryPreferencesInstrumentationTest`が、`FakeCatalogStore.mutate`（test
  infrastructure）がcoordinatorのverified-Create契約（Committed Createはminted entryを
  報告。spec 336）に追従せず`Committed(..., created = null)`を返すため、
  `create` → `verified Create must report the minted entry`で失敗し`rename`も連鎖失敗
  していた（base `dd09ef1a18`でも再現。同classはCI lane未登録）。実装review指摘を
  受け、fakeのCreate報告を正すtest-infrastructure修正（production code・oracle観測対象
  は無編集）と、rename行のmatcher修正（rename affordanceはvisible textではなく
  contentDescriptionであるため`onNodeWithContentDescription`へ）を施し、class全体が
  安定greenになった。
- **MAT-AC-01/06 oracle拡充（実装review対応）**:
  `OrganizerDiagnosticsRouteInstrumentationTest`に2 testを追加した（同classはCI
  issue-52 laneに登録済みのため、以後CIで常時実行される）:
  - `homeScreenHubMaterialsRoutesToEachAuthoringDestination`: hub材料セクション →
    T-02（category overrides）/ T-03（custom categories）/ T-04（placement locks）の
    各row activation → destination到達（backstackの`hasRoute` assert。T-03/T-04は
    さらに固有UI markerのdisplay assert）。T-02はtest環境でapp listが空になり得るため
    route assertのみとする。
  - `homeScreenHubTogglesRecordingPreferenceAndRereadsUsageAccessOnResume`: production
    graphのhub上でT-06 recording toggle ↔ switch semantics一致（同一preferenceの
    反映）、Usage Access行click時にsystem usage-access設定へのintent
    （`ACTION_USAGE_ACCESS_SETTINGS`）が発行されること（instrumentation monitorで
    拦截）、Usage Access行の表示がapp-op付与状態と一致、UiAutomation shellによる
    grant/revoke + `ON_RESUME`再読取（test制御のLifecycleOwnerでresume cycleをdispatch）で
    行のstate textが更新されること。

## Re-entry notes（起草→本revisionの差分、2026-09-19）

起草baseline `3076bdae7e`（branch `issue-367-spec-plan` `a50f074a`）→ 本revision
baseline `1285c13cc6`の差分と本planへの影響:

| 差分 | 影響 |
|---|---|
| PR #378: accepted disposition（organizer-disposition-migration.md）merge | draft時「proposed（未merge）」扱いだったdispositionを、ordering/ownershipの正式根拠（§7.2段階(b)、§8）として参照するよう更新 |
| PR #379: #365正本改訂 merge（`CONTEXT.md` 語彙・requirements FR-006/FR-017 hub表記） | 「材料語彙は#365が追加予定」を実測参照へ更新。Domain languageを`CONTEXT.md`引用に変更 |
| PR #380: #366実装 merge（merge commit `32c72094a4`） | draft時の#366計画値（`bf00f96175`）を実装実測へ置換: route `HomeScreenOrganizer`、`OrganizerHubPreferences.kt`、`OrganizerUsageMaterialRows.kt`（共有composableは`destinations/`配下に配置）、hub文字列4件、`OrganizerHubPreferencesInstrumentationTest`（settings row非依存）を実測確認 |
| PR #381: #366 implemented docs merge | 本spec/planの前提記述を更新（#366 status implemented） |

Current evidenceの核心（HomeScreenPreferencesのrow構造・route構造・test構造）は
差分により変化していないことを確認した。実質的な新規情報は#366実装による
`OrganizerUsageMaterialRows`共有composable化（Personalization groupの2行が
groupごと削除可能になった）であり、本planはこれを前提に書かれている。

## Re-entry notes round 2（2回目re-review対応、2026-09-19）

2回目のre-review（[review](https://github.com/nunu1733/NunuLauncher/issues/367#issuecomment-5741019899)）
の2指摘への対応と、本branchに含めた変更:

| 指摘 | 対応 |
|---|---|
| 中: manual organization row廃止の所有先が正本・#370側で閉じていない | 本branchで (1) accepted `organizer-disposition-migration.md` の§2.3/§3.10/§5/§4.1/§7.2とstatus追記に責務分割を明記、(2) Issue #370本文（Outcome/Scope/AC/Depends on）を編集し記録コメント投稿（[comment](https://github.com/nunu1733/NunuLauncher/issues/370#issuecomment-5741073763)）、(3) 本spec Contract notes 1に解決状況を記録し、Open questions 2を削除。#370 spec/plan（draft）のre-entry整合は#370側の次回taskとして正本へ記録済み |
| 中: spec 203 U-2が「入口注記のみ・契約節無変更」では自己矛盾する | 本Issueの対応を「U-2常設row配置の限定的改訂（settings → hub T-06）」へ修正。disposition §3.10/§5/§4.1を二段階所有（配置= #367、JIT= #371）へ更新し、MAT-AC-04とTest oracleに「旧settings配置をnormative stateとして残さない」「JIT/fallback/`ON_RESUME`規定無変更」の確認を追加（Contract notes 3）。spec 203の変更対象箇所はPermission and fallback behavior表のopt-in行とU-2 decision noteの2箇所（plan Change setに記載） |

Change setの追加変更: disposition正本の改訂は本branchで実施（実装PRでは含まれない。
実装PRのdiff reviewで記載内容の整合のみ確認する）。
