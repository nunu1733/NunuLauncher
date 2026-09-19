# Implementation Plan: Organizer hub（T-01）の新設

> Issue: #366
> Spec: [spec.md](./spec.md)
> Status: draft

## Current evidence

以下はすべてmain `3076bdae7e`（2026-09-19）で確認した現行実装の事実である。

### 入口とnavigation

- `lawnchair/src/app/lawnchair/ui/preferences/destinations/HomeScreenPreferences.kt`
  - General group内（103〜110行目付近）にmanual organization入口row
    （`NavigationActionPreference`, label `manual_organization_title`、destination
    `HomeScreenManualOrganization()`）。#232のコメント付きでLayout groupより先頭側へ昇進済み。
  - Layout group内（167〜190行目付近）に`HomeScreenPlacementLocks`（#38）、
    `HomeScreenOrganizerDiagnostics`（#138）、`HomeScreenCategoryOverrides`（#99）、
    `HomeScreenCustomCategories`（#336）の4つのorganizer row。
  - Personalization group（206〜238行目付近、heading `organizer_personalization_section`）に
    記録toggle（`prefs2.organizerPersonalizationRecording` adapterの`SwitchPreference`）と
    Usage Access行（`UsageAccess.isGranted`を`ON_RESUME`で再読取する
    `ClickablePreference`、spec 203 U-2）。このgroupは独立destinationではなく
    `HomeScreenPreferences`のinline構成である。
- `lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceRoutes.kt`
  - organizer系route: `HomeScreenPlacementLocks` / `HomeScreenCategoryOverrides` /
    `HomeScreenCustomCategories` / `HomeScreenOrganizerDiagnostics`（いずれも引数なし
    `data object`）。`HomeScreenManualOrganization(entry: OrganizationEntry)`のみ引数を持ち、
    `MANUAL`/`ONBOARDING`を`Trigger.MANUAL_FULL`/`Trigger.ONBOARDING_PROPOSAL`へ対応させる。
- `lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceNavigation.kt`
  - 113〜128行目付近で`composable<HomeScreenManualOrganization>`が
    `ManualOrganizationPreferences(trigger = route.trigger, onOpenDiagnostics = { navigate(HomeScreenOrganizerDiagnostics) })`
    を構成する。他のorganizer destinationは引数なしcomposable直指定。
- `lawnchair/src/app/lawnchair/organizer/ui/OrganizationOnboardingProposal.kt:427` —
  onboarding提案は`HomeScreenManualOrganization(OrganizationEntry.ONBOARDING)`で既存run面へ
  接続する（#370まで変更しない）。
- 設定search indexはpreference画面には存在しない（`components/search/`はlauncher側app
  searchのprovider設定）。新destinationのsearch登録は不要。

### Run面とdurable status seam（spec 271、implemented）

- `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt`
  - 169〜207行目付近: durable status読取effect。`showDurableStatus = state is Idle || Cancelled`、
    `readinessState`のcollectとともに`LaunchedEffect(showDurableStatus, readinessState)`で
    `coordinator.readDurableOrganizerStatus()`を読む（IO dispatcher）。checking行の
    表示条件（未既知、または`UNAVAILABLE`かつgate `IDLE`/`RECONCILING`）も同所。
  - 331〜373行目付近: `Idle`/`Cancelled`分岐がchecking行 → `durableStatusItems(...)` →
    開始行（spec 328の`importAttemptActive`でfreeze）の順でrender。
  - 848〜890行目付近: `durableStatusItems` — `ORGANIZED_RESTORABLE` /
    `RESTORED_OR_EXPIRED` / `UNRESOLVED`（＋既存safe-supportの診断導線）を
    string resource `manual_organization_durable_status_*` で表示。`NEVER_ORGANIZED` /
    `UNAVAILABLE`は行なし。
  - 同面にはspec 182のstrategy picker、spec 205/328の`ExchangeFlowStateHolder` /
    `StrategyWriteArbiter` / import freezeが同居する（本Issueでは一切触れない）。
- `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt`
  - 71〜116行目: `ManualOrganizationApplication` façade（`readDurableOrganizerStatus()`
    107行目、`readinessState: StateFlow<ReadinessGate.State>` 115行目）。
  - 161〜180行目付近: `ManualOrganizationModule.get(context)` singleton。`LauncherAppState`
    構成保証と`app.ensureOrganizerStartupReconciliation()`（cold-process settings entry、
    spec 271 DS-AC-10）を担う。hubがこの取得経路を再利用すればcold-process安全性は継承される。
  - 365行目 `stateFlow`、377行目 `start(trigger)`、1007行目付近
    `readDurableOrganizerStatus()` delegate。
- `lawnchair/src/app/lawnchair/organizer/application/protocol/LayoutApplicationModule.kt:315` —
  `durableOrganizerStatus()`（readiness gate + run mutex + fence付きsnapshot読取、
  fail-closed）。本Issueでは`organizer/application/**`を一切変更しない。
- 文字列: `lawnchair/res/values/strings.xml` / `values-ja/strings.xml` に既存organizer行の
  label/summary（`manual_organization_title`="Organize home layout"/「ホームレイアウトを整理」、
  `organizer_lock_screen_title`="Placement locks"/「配置ロック」、`organizer_diagnostics_title`、
  `organizer_category_overrides_title`、`organizer_custom_category_title`、
  `organizer_personalization_section`、`manual_organization_durable_status_*`）がEN/ja揃いで
  存在する。導線行の再利用にそのまま使える。
- test: `tests/organizer-instrumentation/app/lawnchair/organizer/ui/
  ManualOrganizationPreferencesInstrumentationTest.kt` ほか
  （`CategoryOverridePreferencesInstrumentationTest`、`CustomCategoryPreferencesInstrumentationTest`）。
  spec 271のdurable status render oracleを含むrun面testは無編集で温存する。
- spec 123 AC-1 inventory: `docs/assessment/evidence/issue-123-ui-mapping.md` が存在する
  （hubのmapping行を追記する対象）。

### 判定: #365はspec/plan執筆をblockしない

依存#365はOPENだが、その対象は正本文書のAmend（product-brief / requirements /
organization-run-ux入口表記 / DESIGN gate 2参照先 / CONTEXT語彙）であり、新規契約を
作らない。#366のspecに必要なTO-BE契約（T-01、D-01/D-02第1段階、§5.2、§8.1、§9、§10、
§13-1段階(a)、§13-5）はすべてaccepted済みの
`docs/product/organizer-to-be-ux.md`（main `3076bdae7e`にmerge済み）に存在する。
よってspec/planはdraftとして作成可能である。実装着手は「正本を先に」（AGENTS.md、
organizer-to-be-ux.md §11要求更新表）により#365のmerge後とする。disposition文書
（PR #378内`docs/product/organizer-disposition-migration.md`）はproposed（未merge）であり、
本planでは実装順序の参照情報としてのみ扱い、確定契約としては引用しない。

## Design

### Modules and interfaces

1. **新route `HomeScreenOrganizer`（`PreferenceRoutes.kt`に追加）**
   引数なし`@Serializable data object : PreferenceRoute`。run状態・書込み権限を
   持たない（既存organizer destinationと同型）。#366を示す近傍コメントを付す。

2. **新destination `OrganizerHubPreferences`（`lawnchair/src/app/lawnchair/ui/preferences/destinations/OrganizerHubPreferences.kt`）**
   既存命名規約（`ManualOrganizationPreferences` / `OrganizerDiagnosticsPreferences`）に
   従う。`PreferenceScaffold` + `PreferenceLazyColumn` + 既存preference componentのみで
   構成する（spec 123収束、two-paneは`LocalIsExpandedScreen`規約に従う）。
   構成（上から）:
   - status card領域: durable status行群 → 「整理を開始」CTA → 診断導線。
   - 材料group: 分類（→`HomeScreenCategoryOverrides`）、ユーザー定義カテゴリ
     （→`HomeScreenCustomCategories`）、配置ロック（→`HomeScreenPlacementLocks`）の
     `NavigationActionPreference`（既存label/summary stringを再利用）、使用状況ヒント
     （共有composable、後述）。

3. **status card（`OrganizerHubPreferences`内に実装）**
   - seamは既存のもののみ: `ManualOrganizationModule.get(context)` singleton から
     `stateFlow`（run状態）、`readinessState`、`readDurableOrganizerStatus()`を使用する。
     新規module・新規adapter・新規seamは作らない（AGENTS: 単なる委譲moduleを増やさない）。
   - 読取effectはrun面と同じkeying（`(state is Idle/Cancelled, readinessState)`）と
     checking行条件を再現する。`UNAVAILABLE`・`NEVER_ORGANIZED`は行なし。
     `UNRESOLVED`は既存safe-supportの診断導線（`HomeScreenOrganizerDiagnostics`へ）。
   - string resourceはrun面と同一の`manual_organization_durable_status_*`を再利用する
     （語彙の一元維持。spec 271の語彙契約がそのままhubにも適用される）。
   - run進行中（`Idle`/`Cancelled`以外）はdurable行・checking行ともrenderしない。
     `stateFlow`監視によりIdle/Cancelledへ戻った時点で再読取する。
   - **run面のrefactorはしない**: `ManualOrganizationPreferences.kt`は本Issueで無編集とし、
     status cardのcompose実装はhub側に新規に書く（同一seam・同一resource・同一条件式の
     再実装であり、実装PRで両面の挙動一致をtestで固定する）。run面からの共有composable
     抽出は、implemented済みspec 271のoracleを触るrefactorになるため本Issueでは行わず、
     必要なら後続の独立refactor Issueで行う。
   - 「整理を開始」CTAは`NavigationActionPreference`（destination =
     `HomeScreenManualOrganization()`）で実装し、hubから`coordinator.start()`を呼ばない。
     開始は既存run面の開始行のみが発行する（開始authorityの一元化。spec 328のimport
     freeze等のgateが存在するのはrun面であり、hubで再実装・迂回すると契約driftの
     リスクがある）。

4. **使用状況ヒントmaterial（`HomeScreenPreferences`から共有composableへ抽出）**
   - Personalization groupの2行（記録toggle + Usage Access行）を、既存のcompose内容を
     behavior-identicalに共有composable（例: `OrganizerUsageMaterialRows`）へ抽出し、
     `HomeScreenPreferences`とhubの両方から呼ぶ。
   - 同一preference adapter（`prefs2.organizerPersonalizationRecording`）、同一
     `UsageAccess.isGranted`読取、同一`ON_RESUME`再読取を維持する。第二の永続化は
     作らない。settings側の表示（group heading、row順、文字列）は不変である。
   - 抽出は`HomeScreenPreferences.kt`のinline code移動のみであり、spec 203 U-2の
     常設row契約はsettings側のrowが残ることで満たされ続ける。

5. **`HomeScreenPreferences.kt`への入口row追加**
   General groupのmanual organization row付近（#232昇進位置の隣）へ
   `NavigationActionPreference`（label `organizer_hub_title`、subtitle
   `organizer_hub_summary`、destination `HomeScreenOrganizer`）を追加する。
   既存rowはすべて残す（段階(a)）。

6. **`PreferenceNavigation.kt`へのcomposable登録**
   `composable<HomeScreenOrganizer> { OrganizerHubPreferences() }`。
   診断・材料導線はhub内で`NavigationActionPreference`のdestination指定により解決するため
   callback引数は不要（run面の`onOpenDiagnostics` patternを必要としない）。

7. **文字列（`values/strings.xml` / `values-ja/strings.xml`）**
   新規は最小集合: `organizer_hub_title`（入口row label、例: "Organizer"）、
   `organizer_hub_summary`（入口row subtitle）、`organizer_hub_label`
   （hub画面title）、`organizer_hub_materials_heading`（材料group heading）。
   status card行・CTA・材料導線labelは既存stringを再利用する。複合文が必要になった場合は
   format resourceで構成する。最終文言は実装PRのreviewで確定（spec Open questions 2）。

### Data flow

- hub表示 → `ManualOrganizationModule.get(context)`（必要なら`LauncherAppState`構成 +
  reconciliation trigger、cold-process安全はここで継承）→ `stateFlow`監視 /
  `readinessState`監視 / `readDurableOrganizerStatus()`（IO）→ 閉域enum → render mapping。
- 「整理を開始」→ navControllerで`HomeScreenManualOrganization()`へ → 既存run面が
  現行どおりcoordinatorの現状態（Idleなら開始行、進行中なら進行中表示）をrender。
- 材料導線 → 既存destination routeへnavigation。使用状況material → 既存adapterと
  app-op読取を共有composable経由で使用。
- hub経由の新規書込み経路は、既存記録preferenceの既存adapter経由の変更のみ。
  recovery store・Launcher DB・run coordinatorへの書込みは存在しない。

### Alternatives rejected

- **hub CTAから直接`coordinator.start(MANUAL_FULL)`を発行してrun面へ遷移**（1 tap開始）:
  spec 328のimport-attempt freezeや`StartOutcome` single-flight handlingがrun面のUI層に
  実装されているため、hubで同じgateを再実装するか迂回するかのどちらかになり、
  契約driftまたは迂回のリスクがある。1 tap開始はT-07前置き面（#369）で契約ごと
  実現する。本段階では開始authorityをrun面に一元化する。
- **status cardのrun面との共有composable抽出（本Issueで実施）**:
  implemented済みspec 271のrender面（`ManualOrganizationPreferences`）をrefactorにより
  触ることになり、無編集greenを契約とするspec 271 oracleの保護と競合する。本Issueは
  新規追加に限定し、抽出が必要になれば別Issueで行う。
- **使用状況materialを#367へ先送りし、hubには置かない**: Issue #366のscopeが
  「分類/カテゴリ/lock/診断/使用状況への導線」をminimumに含めるため、共有composable
  抽出（behavior-identical）で本Issue内で満たす。
- **診断・材料導線を`LocalNavController`直接操作で実装**: 既存の
  `NavigationActionPreference`がdestination指定のnavigationを抽象化しており、規約に従う。

## Change set

| Path | Change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceRoutes.kt` | add `HomeScreenOrganizer` route（引数なし） | 既存organizer destinationと同型のroute。navigationのみで契約stateを持たない |
| `lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceNavigation.kt` | register `composable<HomeScreenOrganizer>` | 既存登録規約に従う |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/OrganizerHubPreferences.kt` | new: hub destination（status card第1段階 + 開始CTA + 診断・材料導線 + 使用状況material） | 新surface。既存component/seamの消費のみ |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/HomeScreenPreferences.kt` | add hub入口row; Personalization groupの2行を共有composable呼び出しに置換（behavior-identical） | 入口rowは本Issueのprimary entry。抽出はhub/設定の単一の真実のため |
| 共有composable配置（`OrganizerHubPreferences.kt`内 or `organizer/ui/`配下の新file） | usage material rowsの抽出先 | `HomeScreenPreferences`とhubの双方から使用。実装時にco-location判断（規約上、`organizer/ui`はUI部品の既存置き場） |
| `lawnchair/res/values/strings.xml`, `lawnchair/res/values-ja/strings.xml` | 4新規string（title/summary/screen label/材料heading） | EN/ja必須（HUB-AC-08）。status行等は既存string再利用 |
| `tests/organizer-instrumentation/app/lawnchair/organizer/ui/OrganizerHubPreferencesInstrumentationTest.kt` | new: hub render/navigation/a11y test | spec Test oracleの自動化対象。既存harness patternを踏襲 |
| `docs/assessment/evidence/issue-123-ui-mapping.md` | hubのinventory行追加（surface→参照component→差分） | spec 123 AC-1の更新（Issue受入6） |
| `specs/366-organizer-hub-shell/{spec,plan}.md` | status/history更新（実装PRで） | specs README rule |

変更しない: `ManualOrganizationPreferences.kt` / `ManualOrganizationRun.kt` /
`organizer/application/**` / `organizer/planning/**` / diagnostics /
`LawnchairApp.kt` / Launcher3側source / 既存string resourceの値。

## Migration and recovery

- migrationなし（新規永続化なし、schema変更なし）。`favorites` への接触なしのため
  ホームレイアウト安全規約の適用対象外。
- rollback: PR revertで現行挙動へ戻る。hubが書き得る状態は既存記録preferenceのみであり、
  revert後に不整合は残らない。
- failure挙動: durable status読取の全failure modeはspec 271と同一のfail-closed mapping
  （行なし/checking行）。hubのnavigation失敗・route復元は既存preferences navigationの
  挙動に従う。
- backup/restore互換: 影響なし（新規永続データなし）。

## Risk assessment

- **risk: low**。navigation追加とUI消費のみ。高リスクpath
  （`organizer/application/**`、Launcher3 writer系）を含まないため、workflowの独立audit
  gate（high-risk-evidence）の対象外である。`risk:` labelは不要。
- リスク点1: `HomeScreenPreferences`のPersonalization group抽出はimplemented surfaceへの
  behavior-identical refactorであり、意図しない表示変更の混入が考え得る。→ 抽出前後の
  row構造assertとscreenshot、既存instrumentation lane greenで抑止する。
- リスク点2: hubとrun面でdurable status render条件を二重に実装することのdrift。→
  同一string resource・同一条件式の使用と、両面の挙動一致を固定するinstrumentation test
  で抑止する。抽出は別Issueに分離済み。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| HUB-AC-01 | `OrganizerHubPreferencesInstrumentationTest`: 入口row・hub表示・行構造; emulator screenshot（ja/default × light/dark） | `connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=...OrganizerHubPreferencesInstrumentationTest` |
| HUB-AC-02 | 同test: durable status行のstatus別render、checking行とgate回復、run進行中の隠蔽とIdle復帰再表示; cold-process emulator evidence（Launcher未開でhubからdurable status到達） | 同上 + 手動emulator手順をPR記録（spec 271 DS-AC-10 evidenceスタイル） |
| HUB-AC-03 | 同testの否定的観測 + PR diff review | review |
| HUB-AC-04 | 同test: hub CTA → run面 → 既存開始行でrun開始; 既存`ManualOrganizationPreferencesInstrumentationTest`無編集green; unit gate green | instrumentation class filter + `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` |
| HUB-AC-05 | 設定側row群の存在assert + 既存lane green | 同上 |
| HUB-AC-06 | 同test: hub側toggle ↔ 設定側rowの状態一致、resume再読取 | instrumentation |
| HUB-AC-07 | Compose semantics assertion（読み順/role/state/traversal）、focus restoration、200% font scale到達 | instrumentation + organization-run-ux §6表に基づく確認記録 |
| HUB-AC-08 | 新規string集合の`required ⊆ values-ja`機械確認 + placeholder一致 + hardcoded literal grep | 実装PR内手順を記録（spec 123 AC-5方式の新規string限定適用） |
| HUB-AC-09 | `issue-123-ui-mapping.md`のdiff + component再利用確認 | PR diff review |
| HUB-AC-10 | `./gradlew spotlessCheck`、organizer unit gate、対象class instrumentation、CI `final-status` green | 上記command + CI run URL |

含めるべき観点: UI/unit（render mapping、navigation）、accessibility（§6）、
localization（ja/en/en-XAの代表capture、spec 123 AC-8方式のhub限定matrix）、
failure injection（durable status fail-closedは既存seam testが所有し、hub側はrenderのみ再確認）。

## Dependencies and ordering

1. **owner review**: 本spec/planの`accepted`化（workflow Start gate。本taskはdraftまで）。
2. **#365 merge（正本改訂、docs-only）**: 実装着手の前提。`CONTEXT.md`語彙（hub/材料）の
   追加は#365が所有し、本IssueのPRでは実施しない。
3. **本Issue実装**（1 PR想定）: 実装着手時点の最新mainに対し、本planのCurrent evidenceが
   仍然成立するかを再確認してから着手する（snapshot re-entry rule）。
4. **後続**: #367（材料移動・設定row廃止）、#368（strategy）、#369（T-07/run統合）、
   #374/#375（status card拡張）、#376（復元CTA）。本Issueのdiffがこれらの契約面
   （strategy picker、run面統合、durable store）に触れないことをPR reviewで確認する。

## Explicitly unverified areas

- `ManualOrganizationPreferencesInstrumentationTest`のfake application harnessの内部構造
  （新hub testがどの程度再利用できるか）。実装時にharnessを確認して踏襲する。
- `HomeScreenPreferences`のPersonalization groupに対する既存automated testの有無
  （organizer instrumentation一覧では未確認）。存在しない場合は抽出の回帰を新規assertで
  補う。
- two-pane（expanded）画面でのhub描画。既存destination規約に従う前提であり、実装時の
  emulator evidenceで確認する。
- 新規stringの最終文言（ja含む）。実装PRのreviewで確定（spec Open questions）。

## Documentation updates

- [ ] `specs/366-organizer-hub-shell/spec.md` / `plan.md` status・history（本PR）
- [ ] `docs/assessment/evidence/issue-123-ui-mapping.md`（HUB-AC-09）
- [ ] `CONTEXT.md`語彙は実施しない（#365のownership。本IssueのPRへ混ぜない）
- [ ] `DESIGN.md`: 変更不要の想定（§4.4 UI adapterに新destinationを1行追記する価値が
      あるかは実装PRで判断。hubは既存UI adapter記述の範囲内）
- [ ] ADR: 不要（IA決定の選択肢比較はorganizer-to-be-ux.md §4に記録済みであり、ADR
      3条件を満たさない）

## Execution checklist

1. [ ] 実装開始条件の確認: spec/plan `accepted`、#365 merge済み、main再確認
       （Current evidenceの再検証）。
2. [ ] `HomeScreenOrganizer` route + `OrganizerHubPreferences`骨格 + 入口row
       （HUB-AC-01の失敗testを先に追加）。
3. [ ] status card第1段階（durable status render + checking + run進行中隠蔽）
       （HUB-AC-02/03）。
4. [ ] 開始CTA・診断・材料導線（HUB-AC-04/05のtestを先に追加し、既存flow回帰を確認）。
5. [ ] 使用状況material抽出とhub側実装（HUB-AC-06）。
6. [ ] 文字列EN/ja + a11y assertion + 200% font scale（HUB-AC-07/08）。
7. [ ] spec 123 inventory更新（HUB-AC-09）。
8. [ ] full verification（HUB-AC-10）+ evidence記録 + PR（`Refs #366`）。
