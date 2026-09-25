---
issue: "#458"
status: draft
phase: "Phase 1 (audit)"
method: "test-audit skill / Portfolio campaign"
evidence-date: 2026-09-25
baseline: "7508bbf0d5 (main)"
updated: 2026-09-25
---

# 既存テスト/CI資産の semantic 再監査記録（Phase 1）

本書は Issue #458 Phase 1 の監査記録である。`.agents/skills/test-audit/SKILL.md` の
Portfolio campaign 手順に従い、読み取り専用の調査で収集した証拠に基づいて disposition を
提案する。test 数の削減は目的ではなく（Issue 明記）、regression confidence / CI cost /
maintenance cost の最適化が判断基準である。

証拠源:

- `.github/workflows/ci.yml` の 10 instrumentation lane の class filter と
  `organizer-unit-tests` の `--tests` filter（実行の正本）。
- `tools/ci/run-restore-capture-instrumentation.sh`、`run-production-input-instrumentation.sh`、
  `run-manual-organization-ui-instrumentation.sh`（helper 経由 lane の実 class list）。
- `tests/organizer-instrumentation/` 87 file、`tests/unit/` 180 file の全読込による
  契約・fixture・Issue 由来の確認。
- `docs/engineering/ci-test-portfolio.md`（#422 監査表・未 routing 記録）、
  `docs/engineering/quality-strategy.md`（flaky taxonomy）、
  `specs/422-impact-based-ci-portfolio/spec.md`（deferred disposition）。
- `docs/assessment/**` と git log の failure 履歴。

---

## 1. CI 実行の実態（class filter 全量）

### 1.1 instrumentation: 実行される class

| Lane | 実行 class（helper script の `am instrument` 分を含む） |
|---|---|
| shared-writer (API 36) | ModelWriterTransactionReentryTest, LayoutWriteCoordinatorTest, BinderOperationFutureTest, NestedTransactionTest, OrganizerReloadCompletionOrderingTest, OrganizerReloadSupersessionTest, RestoreLeaseSerializationTest, RestoreLeaseDeferredLoaderThreadAffinityTest, HotseatRestoreAdmissionTest（9） |
| db-migration (API 36) | MigrationTransactionOwnershipTest, DatabaseHelperSchema33Test, DowngradeSchema33Test, InactiveGridDbNormalizationTest, rollback32.Schema32RollbackBinaryTest（5） |
| restore-capture (API 36) | NovaRestoreCaptureControlTest, WidgetWindowTest, UnknownProviderTest, NoCallbacksTest（gradle connected 4 回）+ CrossProcessStageATest/StageBTest（`am instrument` 2 回。両 class は `NovaRestoreCaptureCrossProcessTest.kt` 内）（6） |
| production-input (API 35) | ProductionOrganizationInputInstrumentationTest, CategoryOverrideAtomicFileInstrumentationTest, NestedTransactionTest（二重 routing）, TwoPanelOrientationCaptureInstrumentationTest（gradle connected）+ CategoryOverrideAtomicFileRestartWriter/ReaderInstrumentationTest（`am instrument`。`CategoryOverrideAtomicFileInstrumentationTest.kt` 内）（6） |
| manual-organization-ui (API 36) | ManualOrganizationProductionE2E, ManualOrganizationPreferences, OrganizerHubPreferences, StrategyPicker, MissingAppSelection, UsageAccessJit, exchange.ExchangeImportSuccess, StrategyPickerFreeze, OrganizerDiagnosticsRoute（9） |
| reservation-recovery (API 36) | ProductionPublicSeam, RealAdapterRowMatrix, Sanitizer, RecoveryStoreLifecycle, OverlapAcceptanceGateSeam, planning.LoaderCursorOverlapAcceptanceContract, Issue265ManualEditRecovery（7） |
| category-override (API 36) | CategoryOverridePreferences, CustomCategoryPreferences（2） |
| exchange-import-ui (API 36) | exchange.ExchangeImportSurface（1） |
| method-choice-journey (API 36) | exchange.MethodChoiceConnectedJourney（1） |
| onboarding-proposal (API 36) | OnboardingOrganizationProposal, InjectedInputEnvironmentState（2） |

計 54 class invocation（NestedTransactionTest の二重実行を含む）。

### 1.2 instrumentation: unrouted class（CI で一切実行されない）

`tests/organizer-instrumentation/` 87 file のうち test class は 69（support 16:
TestBase / Runner / fixture / helper）。routed 54 class を除く **unrouted 32 class** が
次の通り。各 lane は明示 class filter で実行するため、これらは full portfolio・
per-path fail-closed でも実行されない（#422 記録の通り）。個別監査は §3。

backup/migration: NovaRestoreGridApplicationTest, DeckRetirementBackupRestore,
DeckRetirementDowngradeFixture, DeckRetirementMigration, DeckRetirementOldTargetCompat,
DeckRetirementProcessIsolation（6）

organizer 直下: UsageAccessTransitionProbe, UsageStatsIntervalProbe（2）

application: Issue265GateFailedRoute, OrganizerRecovery, PageCapture（3）

application/store: OrganizerDurableStatus, RecoveryInspectionSnapshotPublication,
RecoveryStoreInspection（3）

diagnostics/export: OrganizerDiagnosticsExportTimestamp（1）

integration: Issue108DeviceEvidence, Issue108GridEvidence, Issue134GridPreset（3）

locks: GridChangeUnknownLockRecovery, LockAuthoring, OrganizerLockScreen（3）

ui: OrganizerRestoreColdProcessEvidence, StrategyT05ProductionNavigation,
StrategyT05VisualEvidence（3）

launcher3 直下: DeckRetirementDeleteRegression, DeckRetirementPackageRegression,
LauncherPrefsCommit（3）

model: GridMigrationFailure, GridMigrationSuccess（2）

organizer: BackupExclusion, RecoveryStoreChunkedManifest, RestoreProfileRemap（3）

注: `DeckRetirementTestRunner.kt` は module 全体の instrumentation runner
（AndroidManifest 登録）であり test class ではない。restore-capture / production-input
helper の `am instrument` と local smoke script（`tools/deck-retirement-*-smoke.sh`、
`tools/organizer-recovery-smoke.sh`）が明示利用する。

### 1.3 JVM: `organizer-unit-tests` filter coverage

job の filter は `app.lawnchair.organizer.*` / `app.lawnchair.ui.preferences.navigation.*` /
`app.lawnchair.bugreport.*` / `app.lawnchair.backup.*`。`tests/unit/` は 165 test class +
15 support file。このうち次の **2 class が filter 外**（CI で実行されない）:

| Class | 契約 | production caller |
|---|---|---|
| `app.lawnchair.migration.DeckRetirementArtifactNamesTest` | `DeckRetirementMigration.recognizedArtifactNames()` の純粋な名前導出（bk_*.db / lawndeck_*.db の順序・完全性） | `DeckRetirementMigration.kt:93`（production 実在） |
| `app.lawnchair.DeviceProfileOverridesPresetResolutionTest` | `DeviceProfileOverrides.resolveEnabledPresets` / `ceilingMatchPreset` の純粋な preset 解決契約（Issue #134） | 同一 production file 内 call chain（getGridName → enabledPresets → resolveEnabledPresets） |

Gradle `*` は package 区切りを跨ぐため、`app.lawnchair.organizer.*` 等は subpackage を
含む（quality-strategy.md も同様に記述）。上記 2 件は package が filter 外のため実行され
ない。

### 1.4 map ↔ workflow 整合

`ci_portfolio_map.yml` の 10 edge と `ci.yml` の lane `if` 条件は一致している
（`validate_ci_portfolio.py` が機械検証）。surface filter（`changes` job）と
`ci_portfolio_map.yml` の surface 語彙も一致。test path mapping と実 class filter の
乖離は §1.2 / §3 に記録した通り:

- `tests/organizer-instrumentation/com/android/launcher3/model/**`（GridMigration 系）は
  `surface_db_schema` に mapping されているが、db-migration lane は当該 class を実行して
  いない（over-trigger のみ。#422 記録の繰り返し）。
- `tests/organizer-instrumentation/app/lawnchair/organizer/locks/**`、
  `.../organizer/diagnostics/export/**`、`.../com/android/launcher3/LauncherPrefsCommitTest.java`
  などは test path としても surface glob に含まれず、test-only 変更が unmapped（fail-closed
  で full 起動＝安全側だが自己検証されない）。

### 1.5 他 workflow

`.github/workflows/` で test を実行するのは `ci.yml` と `planner-stress.yml`
（`PlannerGeneratedPropertyTest` 8 seed matrix、Scheduled/Diagnostic）のみ。
`high-risk-gate.yml` は audit 記録検証のみ、release 系は assemble のみ、`workflow_call`
の利用者は存在しない。

---

## 2. CI-routed instrumentation class の契約と owner（AC-458-P1-01）

lane 単位の契約・分類・費用は `docs/engineering/ci-test-portfolio.md` 監査表が正本であり、
本監査で再確認した。以下は class 単位の protected contract と owner boundary の記録
（重複分析は §4/§5、重複が見つからなかった経緯を含む）。

| Class（lane） | Protected contract / credible regression | Owner boundary が必要な理由（JVM 不可能な失敗モード） |
|---|---|---|
| ModelWriterTransactionReentryTest（shared-writer） | #113: 開いた transaction 内の同 thread 再入 mutation（folder-bind ANR） | 実 ModelWriter / binder / loader thread affinity |
| LayoutWriteCoordinatorTest（shared-writer） | lease 排他・owning kind のみ再入可 | 実 coordinator singleton + thread |
| BinderOperationFutureTest（shared-writer） | #60 AC-05: Binder operation future が MODEL_EXECUTOR 自己待ちしない | 実 Binder callback thread |
| NestedTransactionTest（shared-writer + production-input API 35） | #60 AC-06: nested SQLiteTransaction は全体 unit で commit / inner 未 commit で全体 rollback、SAVEPOINT 分離なし | 実 SQLite nested transaction 挙動。API 35 二重実行は API 依存 SAVEPOINT 期待の再発防止（#422 で明示）。重複 routing は意図的 |
| OrganizerReloadCompletionOrderingTest（shared-writer） | #150/#152: reload 完了 callback が transaction barrier 前に発火しない | 実 Launcher model / loader |
| OrganizerReloadSupersessionTest（shared-writer） | #119: reload supersession | 実 model reload 生成 |
| RestoreLeaseSerializationTest（shared-writer） | #58: restore 系 lease 直列化 | 実 lease 競合 |
| RestoreLeaseDeferredLoaderThreadAffinityTest（shared-writer） | #298: tokenless LoaderTask が Looper 無し thread で走らない | 実 thread affinity |
| HotseatRestoreAdmissionTest（shared-writer） | #156: Hotseat restore の決定的 MODEL_EXECUTOR admission | 実 model thread（sleep oracle 不使用） |
| MigrationTransactionOwnershipTest（db-migration） | #118: onUpgrade/onDowngrade 内 transaction 所有 | 実 SQLiteOpenHelper |
| DatabaseHelperSchema33Test（db-migration） | #14: schema 33 生成・32→33 無 wipe migration | 実 SQLite |
| DowngradeSchema33Test（db-migration） | 33→32 downgrade recipe 行保持 | 実 SQLite |
| InactiveGridDbNormalizationTest（db-migration） | inactive grid 正規化が schema 33 source を書き換えない | 実 SQLite |
| Schema32RollbackBinaryTest（db-migration） | #118 AC-4: 実 33→32 rollback binary | 実 SQLiteOpenHelper + 実 recipe |
| NovaRestoreCaptureControl/WidgetWindow/UnknownProvider/NoCallbacks/CrossProcessStageA/B（restore-capture） | #299: Nova restore → capture の cross-process 2 stage・widget window・unknown provider | 実 backup 成形・process death・fresh process |
| ProductionOrganizationInputInstrumentationTest（production-input） | #83: production input composer の実 platform 互換（API 35） | API 35 実 platform |
| CategoryOverrideAtomicFile(+RestartWriter/Reader)（production-input） | category override atomic file の restart writer/reader | 実 filesystem + process restart |
| TwoPanelOrientationCaptureInstrumentationTest（production-input） | two panel orientation capture | 実 display 構成 |
| ManualOrganizationProductionE2E（manual-org-ui） | #52/#155/#210/#228/#230/#231/#417: capture→plan→apply→recovery の縦切り、stale 確認 zero-write、A7 reload 検証 | 実 favorites DB + 実 writer + reload。UI lane で唯一の DB heavy fixture |
| ManualOrganizationPreferences（manual-org-ui） | #417 AC-8 (a)-(f)、#271 durable status、preview card、recovery 決定、DPAD/live region、200% font | Compose 実 surface（#368/#308 の focus 同期修正実績） |
| OrganizerHubPreferences（manual-org-ui） | #366/#374/#376/#368: hub status card、restore CTA fail-closed/直列化、session row TTL/TalkBack、focus restore | Compose 実 surface + 実 durable store |
| StrategyPicker（manual-org-ui） | spec 182/#368/#235/#398/#218/#228: T-05 catalog/radio a11y/validated write | Compose 実 surface |
| MissingAppSelection（manual-org-ui） | #228/#417/#369: 選択面 AC-2/3、scope 凍結、zero-write 中断 | Compose 実 surface |
| UsageAccessJit（manual-org-ui） | #371: JIT dialog 契約（decline 継続・resume・fallback） | 実 app-op + lifecycle |
| exchange.ExchangeImportSuccess（manual-org-ui） | #328/#374/#375: 取り込み成功面 rendering・Back 契約・ImportReview | Compose 実 surface |
| StrategyPickerFreeze（manual-org-ui） | #368: frozen reason / retry notice の live region・無効化 | Compose 実 surface（picker item 単体） |
| OrganizerDiagnosticsRoute（manual-org-ui） | #138/#67/#373/#367/#372/#370/#203: diagnostics route、recreation 生存、production route 経由の session 生存 | Compose 実 nav graph + 実 store |
| ProductionPublicSeam（reservation-recovery） | 受入済み public seam 経由の実 DB apply/recover | 実 Launcher DB |
| RealAdapterRowMatrix（reservation-recovery） | #150/#164/#152: schema 33 row 往復（canonical 順・persistent reference） | 実 SQLite |
| Sanitizer（reservation-recovery） | organizer lease token 付き LoaderTask が sanitizer 3 family を skip | 実 model/reload |
| RecoveryStoreLifecycle（reservation-recovery） | production RecoveryStore API の close/reopen 耐久 | 実 SQLite（JVM は Fake） |
| OverlapAcceptanceGateSeam（reservation-recovery） | #185/ADR-0010: 実 write path 上の overlap gate | JVM は gate 判断のみ（OverlapAcceptanceGateTest） |
| LoaderCursorOverlapAcceptanceContract（reservation-recovery） | #185 §4: LoaderCursor 経由の QSB overlap 形状 | 実 cursor |
| Issue265ManualEditRecovery（reservation-recovery） | #265: 手動編集→再整理の 2 path 実 writer oracle | 実 writer/recovery |
| CategoryOverridePreferences / CustomCategoryPreferences（category-override） | #99/#336/#342: authoring UI semantics/focus/font-scale/touch target | Compose 実 surface |
| exchange.ExchangeImportSurface（exchange-import-ui） | #332/#327/#372/#373/#348: import 入力面・remedy 投影・Back・lease 非拒否 | Compose 実 surface |
| exchange.MethodChoiceConnectedJourney（method-choice-journey） | #417 AC-8 (g)-(v): scope 凍結後の AI 依頼・取り込み・attach・gate 並行 | 実 holder/gate/durable store（KDoc が (a)-(f) は manual-org-ui 側と明示分担） |
| OnboardingOrganizationProposal（onboarding-proposal） | #300/#137/#232/#142/#370/spec 53: proposal lifecycle・実 touch 注入・recreation | 実 launcher activity + focus 観測（#304/#418 環境系実績あり・§6） |
| InjectedInputEnvironmentState（onboarding-proposal） | #300: 環境 state machine 分類 | 純 JUnit 相当だが lane 共有 process の隔離を保証 |

上位層重複の有無は §5（UI lane 間）と §4（JVM ↔ instrumentation）に記録した。結論:
routed class 内に strict duplicate は存在しない。`NestedTransactionTest` の API 35 二重
routing は意図的で契約が文書化されている。`RecoveryManifestChunksTest`（JVM）との分割、
`OverlapAcceptanceGateTest`（JVM）との分割などは「同一 class の契約分割」であり重複では
ない。

---

## 3. unrouted instrumentation 32 class の個別監査（AC-458-P1-03）

記録項目は Issue の audit record requirements に従う。runnability（単一 gradle class 実行
で意味を成すか）は routing 可否の前提条件として先に確認した。

### 3.1 Group R（Route 提案）: standing regression かつ実 framework 契約

#### R-1 `app.lawnchair.backup.NovaRestoreGridApplicationTest`

- protected contract: #168 回帰。`LawnchairApp.cleanUpDatabases` が staged `restored.db`
  存在中・BACKUP_RESTORE lease 保持中に `launcher*` DB file を削除しない。無関係 file は
  従来通り削除する。`InvariantDeviceProfile.applyGridInfo` の決定性。
- credible regression: restore 中に launcher DB を消す data loss（#168 実災害）。
- owner boundary: 実 app process + 実 filesystem。JVM 不可能。
- overlap: routed NovaRestoreCapture* は capture/restore flow で本契約を守らない。なし。
- surface: `surface_backup_restore`（app/lawnchair/backup/**）。
- 履歴: #168 回帰 test。CI 履歴なし。
- disposition: **Route → restore-capture lane**（追加 connected stage 1 回に 3 class
  まとめて実行。§8）。
- routing 後に残る confidence: 新規（0 → 実行）。付加費用は 1 stage（〜1分）。
- validation: 適用前に実 emulator で当該 class 実行。
- residual: なし。

#### R-2 `com.android.launcher3.model.GridMigrationFailureTest` / R-3 `GridMigrationSuccessTest`

- protected contract: `ModelDbController.tryMigrateDB` の failure semantics（21 test:
  delegate-then-throw 各 phase、SimulatedProcessDeath 後の fresh entry 復帰、durable
  fixture fail-closed、digest mismatch、commit=false 補正、冪等 reconcile）と success
  path（target 未公開で source 保持、fast path、lockStates/journal/tmp table 状態）。
- credible regression: grid migration による layout data 消失・recovery 不能。repository
  の quality order 第 1 条（layout を失わない）の中心契約。
- owner boundary: 実 SQLite + 実 LauncherPrefs grid-state。実 process death は in-process
  シミュレーションだが、DB/pref 永続化は実物。
- overlap: なし（GridMigrationTestSupport は routed db-migration class と共有するが契約
  は別）。
- surface: `surface_db_schema`（`com/android/launcher3/model/**` は既に mapping 済み）。
- 履歴: issue #59/#86 系（git 96281bdcd7 / f8bddc7944 / 115549f6fe）。#422 が
  over-trigger mapping だけ記録し実行は未処理。
- disposition: **Route → db-migration lane**（inline class list 追加 2 件）。既存 mapping
  を実行実態と一致させる。
- validation: 実 emulator で 2 class 実行。
- residual: 実 process death ではなく in-process シミュレーションである点は将来の
  process-death integration test 課題（既存限界、本監査で新規に生じない）。

#### R-4 `com.android.launcher3.LauncherPrefsCommitTest`

- protected contract: commit-aware preferences（putSync が全 editor を commit、失敗時
  false 返却）。
- credible regression: grid-migration recovery path が依存する primitive の沈黙的破壊。
- owner boundary: instrumentation context のみで動作（DB/UI 不要）。
- overlap: なし。
- surface: test path が surface glob 外。routing 時に
  `tests/organizer-instrumentation/com/android/launcher3/LauncherPrefsCommitTest.java` を
  `surface_db_schema` へ明示追加（schema test file の前例と同じ）。
- 履歴: issue-59 fixture（git 0b49fafb7f）。
- disposition: **Route → db-migration lane**。
- validation: emulator 実行（低リスク）。
- residual: なし。

#### R-5 `app.lawnchair.migration.DeckRetirementMigrationInstrumentationTest`

- protected contract: DeckRetirementMigration.run の冪等性（2 回連続成功）と active grid
  DB 存在維持。 unrecognized custom grid は inert（ADR-0006）。
- credible regression: startup migration の非冪等化・DB 削除（upgrade user の data 影響）。
- owner boundary: 実 DataStore + 実 SQLite favorites DB。
- overlap: なし（schema 33 契約は別 test）。
- surface: `surface_db_schema`（`app/lawnchair/migration/**` 済み）。
- 履歴: #57 / ADR-0006。
- disposition: **Route → db-migration lane**。
- validation: emulator 実行。
- residual: なし。

#### R-6 `app.lawnchair.organizer.application.store.RecoveryStoreInspectionInstrumentationTest`

- protected contract: #84 inspection 契約。missing DB で schema/-wal/-shm/snapshot dir を
  作らない、checkpoint inspection で物理 file set が byte-identical、corrupt/unknown
  fence/user_version 不整合で fail-closed かつ無修復。
- credible regression: 読み取り専用 inspection が状態を壊す／壊れた store を黙って修復する。
- owner boundary: 実 SQLite + 実 filesystem（`FakeRecoveryStore` では観測不能。KDoc も
  production SQLite adapter 前提を明示）。
- overlap: JVM の RecoveryStartup*Test は分類・decision table のみ。RecoveryStoreLifecycle
  は lifecycle で inspection 副作用契約を持たない。なし。
- surface: `surface_layout_write`（application/store/** 済み）。
- disposition: **Route → reservation-recovery lane**（RecoveryStoreLifecycleTest の隣）。
- validation: emulator 実行（1 test が latched writer thread を使用、同期は latch ベース
  で sleep oracle なし）。
- residual: なし。

#### R-7 `...store.RecoveryInspectionSnapshotPublicationInstrumentationTest`

- protected contract: #89 publication failure 契約（publish 失敗で old final 保持・
  `.new` 残骸なし・fence DIRTY・失敗 final を成功 source にしない）。
- owner boundary: 実 AtomicFile protocol。
- overlap: JVM codec test なしの契約。なし。
- surface: `surface_layout_write`。
- disposition: **Route → reservation-recovery lane**。
- validation: emulator 実行。
- residual: なし。

#### R-8 `...store.OrganizerDurableStatusInstrumentationTest`

- protected contract: #271 durable status projection の restart 涵盖（close/reopen +
  reconcileAtStart で NEVER_ORGANIZED / ORGANIZED_RESTORABLE / UNAVAILABLE fail-closed /
  RESTORED_OR_EXPIRED / UNRESOLVED / 同一 module 再読）。
- owner boundary: 実 DB + 実 module reconcile（process-death surrogate）。JVM
  OrganizerDurableStatusDeriverTest は閉じた mapping のみで reconcile/persist を観測しない。
- surface: `surface_layout_write`。
- disposition: **Route → reservation-recovery lane**。
- validation: emulator 実行。
- residual: なし。

#### R-9 `com.android.launcher3.organizer.RecoveryStoreChunkedManifestInstrumentationTest`

- protected contract: #174（CW-AC-02/03/04/05/10）: 2,247,054 byte record が 2 MB
  CursorWindow 下で checkpoint Ready・reopen 生存・chunk 境界・child-first retention・
  schema 2→3 migration byte-for-byte・corrupt chunk 隔離。
- credible regression: 大きい recovery record による checkpoint 破壊（#171 device class
  の poisoned record）。KDoc が「2 MB CursorWindow は実 Android SQLite のみが強制」と明示
  — 本 group で唯一の hard runnability 要件。
- owner boundary: 実 Android SQLite。JVM RecoveryManifestChunksTest が assemble 純粋核を
  所有し、KDoc が「SQL write/assembly path は instrumentation」と明示分割済み。
- surface: `surface_layout_write`。
- disposition: **Route → reservation-recovery lane**。
- validation: emulator 実行（重め。class 内 test 数は中程度）。
- residual: なし。

#### R-10 `app.lawnchair.organizer.application.Issue265GateFailedRouteInstrumentationTest`

- protected contract: #265 AC-R1/R2: startup reconciliation が ReadinessGate を FAILED に
  する 3 route（checksum 毀損 RESTORING → CORRUPT、NULL-span row、recovery DB 欠如 +
  snapshot 残存 poison pair）で compose が InputUnavailable になる fail-closed。
- credible regression: 壊れた recovery 状態で再整理を誤開始する。
- owner boundary: 実 model + 実 recovery DB（store 直接 tampering は test 内 helper）。
- overlap: routed Issue265ManualEditRecovery は手動編集・再整理 path で gate FAILED route
  は本 class 固有。
- surface: `surface_layout_write`。
- disposition: **Route → reservation-recovery lane**（同 Issue 系の co-occupant。#342 が
  category-override lane へ CustomCategoryPreferences を co-occupant 追加した前例と同じ
  形）。
- validation: emulator 実行（model reload 待ちがあり、lane 内で最重量の追加。§8 の費用
  見積に含める）。
- residual: なし。

#### R-11 `app.lawnchair.organizer.application.PageCaptureInstrumentationTest`

- protected contract: `LauncherLayoutAdapter.captureCurrent` が desktop row 由来 PageId を
  昇順で返し、model-only 空 page を排除し、再 capture で決定的に同一。
- credible regression: page 構造の取りこぼし／順序不安定。
- owner boundary: 実 model + reload latch。RealAdapterRowMatrix は row matrix で page
  ordering 契約を持たない。
- surface: `surface_layout_write`。
- disposition: **Route → reservation-recovery lane**。
- validation: emulator 実行。
- residual: なし。

#### R-12 `app.lawnchair.organizer.locks.LockAuthoringInstrumentationTest`

- protected contract: #38 behavior scenarios + #287 AC-3: lock/unlock 往復の column 検証、
  UNKNOWN review intent 必須・batch 解決、stale/tampered 拒否が無 mutation、WRITER_BUSY、
  `markOrganizerLocksUnknown` が reviewable 化、TEMP trigger による mid-batch rollback。
- credible regression: lock 状態の破壊・batch 部分適用（quality order 第 2 条）。
- owner boundary: 実 DB + 実 coordinator。JVM LockAuthoringDecisionTest（49 test）は
  decision matrix を所有するが commit/rollback を実 DB で観測しない（契約分割、重複で
  はない）。
- surface: test path が surface glob 外 → routing 時に
  `tests/organizer-instrumentation/app/lawnchair/organizer/locks/**` を
  `surface_layout_write` filter へ追加（test-only 変更の自己検証規則を満たす）。
- disposition: **Route → reservation-recovery lane**。
- validation: emulator 実行。
- residual: なし。

#### R-13 `app.lawnchair.organizer.locks.OrganizerLockScreenTest`

- protected contract: #38 accessibility/localization + #211 P1: UNKNOWN banner、確認
  dialog 前の 0 write、dialog 効果注記、同一名 row の disambiguation、WRITER_BUSY 表示。
  evidence capture test は `Assume` で既定 skip。
- owner boundary: Compose UI（fake module・DB なし）。lock UI の JVM 代替は存在しない
  （repository に Robolectric なし）。
- overlap: category-override / custom-category UI test は別 surface。なし。
- surface: lock UI production は `organizer/ui` 配下 → `surface_organizer_ui`。test path
  （locks/**）を `surface_organizer_ui` filter へ追加（surface_layout_write への追加と
  併存。重複 mapping は安全側）。
- disposition: **Route → category-override lane**（authoring UI semantics/focus/font-scale
  family の co-occupant。#342 前例）。
- validation: emulator 実行。
- residual: なし。

#### R-14 `app.lawnchair.organizer.diagnostics.export.OrganizerDiagnosticsExportTimestampInstrumentationTest`

- protected contract: #288: SAF suggested filename と JSONL header
  `exportedAtWallMillis` が同一 captured instant、単一読み出し、replayed/cancel/stale OK
  で再書込しない、StateRestorationTester による picker-open recreation 後の session 生存。
- credible regression: export 時刻の不整合・recreation による session 消失。
- owner boundary: Compose + 実 production SAF contract + 実 recreation。JVM
  ExportWriterTest / DiagnosticsExportFilenameTest は writer/filename 契約のみ（§4-D）。
  recreation 生存は Compose instrumentation 固有。
- overlap: routed OrganizerDiagnosticsRoute は route 存在のみ。success 面との重複なし。
- surface: test path を `surface_organizer_ui` filter へ追加。lane は diagnostics UI の
  co-occupant が既にいる manual-organization-ui。
- disposition: **Route → manual-organization-ui lane**。
- validation: emulator 実行。
- residual: diagnostics/export の production path は `surface_jvm`（JVM gate が logic を
  所有）であり、UI 側 recreation 契約だけが本 routing で守られる。整合は §8 の surface
  filter 追加で保つ。

#### R-15 `com.android.launcher3.organizer.BackupExclusionTest`

- protected contract: organizer recovery DB が `LauncherFiles.ALL_FILES`（backup
  allowlist）に含まれない。
- credible regression: recovery DB の backup 混入（privacy + restore 整合性）。
- owner boundary: 静的 list 検査。runnability は最小。
- surface: test path を `surface_backup_restore` filter へ明示追加。
- disposition: **Route → restore-capture lane**（R-1 と同じ追加 stage）。
- validation: emulator 実行（自明に近いが routing コストもほぼ 0）。
- residual: diagnostics journal 側の backup exclusion は unit
  `diagnostics/integration/BackupExclusionTest`（AC-67-11）が所有し、recovery DB 側の本
  class とは補完。

#### R-16 `com.android.launcher3.organizer.RestoreProfileRemapTest`

- protected contract: `RestoreDbTask.migrateProfileId` が organizerLockState を保持し、
  unavailable profile の行削除が動く。
- credible regression: restore 時の lock 状態消失（profile remap）。
- owner boundary: 実 SQLite（自前 throwaway db、launcher DB 不要）。
- surface: test path を `surface_backup_restore` filter へ明示追加。
- disposition: **Route → restore-capture lane**（R-1 と同じ追加 stage）。
- validation: emulator 実行。
- residual: なし。

### 3.2 Group D（Diagnostic-local-only 提案）: evidence tooling / smoke driver / 重い E2E

共通記録: 全 16 class に @Ignore はなく、KDoc に「CI lane に入れない」理由が明記されて
いる class と、driver として host script と対でしか意味を成さない class が多い。分類は
「明示的な diagnostic 分類の記録」であり、恒久 lane へ昇格しない。test 本体の変更は
行わない（KDoc 注記の追記は Phase 2 で必要最小限のみ行うか、行わない方針。portfolio 文書
への記録を正本とする）。

| Class | 契約の要約 | Diagnostic とする理由（routing しない根拠） | residual risk / follow-up |
|---|---|---|---|
| UsageStatsIntervalProbeTest | #203 U-5: 実 UsageStatsManager interval 挙動の logcat 記録（assertion は assertNotNull のみ） | evidence gathering そのもの。KDoc に「behavioral assertion suite ではない」と明記。DST 条件は手動 adb が必要 | なし（docs/assessment/pr-321-u5-probe-evidence.md が成果物） |
| UsageAccessTransitionProbeTest | #203 re-review: app-op grant/revoke で production reader が追従する platform 遷移 | platform 遷移の 1 回調査 probe（`appops` shell + sleep 依存）。routed UsageAccessJit が JIT UX 契約を、#203 の JVM 契約 test が reader logic を所有 | reader の app-op 追従 regression は CI 未検出。低頻度環境依存のため follow-up 候補として §9 に記録 |
| Issue108DeviceEvidenceTest | #108: capture の profile identity 保持 + host shape parity | evidence + assertion 混在。IDP/UserManager 収束待ちが環境敏感 | capture parity の一部は routed RealAdapterRowMatrix / TwoPanelOrientation が保護 |
| Issue108GridEvidenceTest | #108: 代替 grid 切替後の capture/composer parity + STALE_REVISION 拒否 | ≥2 grid option 前提・IDP 収束待ちが環境敏感。phase 引数あり | grid 変更 capture parity は R-2/R-3 routing で migration 側を保護。capture 側 parity は follow-up 候補 |
| Issue134GridPresetTest | #134: preset 拒否 seam + phased restart durability | tablet 型 AVD 前提・phase driver 必須 | preset 解決 logic は JVM gate への追加（§8-JVM）で恒久保護 |
| OrganizerRecoveryInstrumentationTest | organizer process-death smoke（fault injection → restart → reconcile Clean） | fault mode が設計上戻らない（host script が process kill）。単一 gradle 実行で意味を成さない | process-death reconcile 契約は R-8/R-10 routing で部分保護。full smoke は tools/organizer-recovery-smoke.sh が正 |
| OrganizerRestoreColdProcessEvidenceTest | #376 RS-AC-01: cold process での hub restore CTA | KDoc「Evidence tooling, deliberately NOT part of any CI lane」、2 phase 手動 orchestration 前提 | hub CTA 契約は routed OrganizerHubPreferences が process 内で保護。cold process 版は follow-up 候補（重い） |
| StrategyT05ProductionNavigationTest | #368: 遷移 ordering oracle（mid-transition 状態遷移） | KDoc「Evidence tooling」、3 AVD で production shell が compose 不可という実測限界を記録 | frozen window 契約は routed StrategyPickerFreeze が保護 |
| StrategyT05VisualEvidenceTest | #368: 視覚 evidence PNG capture（+ row 幅 assertion） | KDoc「evidence tooling」。PNG capture が主目的 | 200% reflow 契約は routed StrategyPicker が保護 |
| DeckRetirementBackupRestoreTest | #57 AC-006: typed new-target driver（marker 出力、host script が oracle） | preflight が旧/新 binary と nonce 引数を要求。単体実行不可 | upgrade/downgrade walk は tools/deck-retirement-*-smoke.sh が正 |
| DeckRetirementOldTargetCompatTest | #57: 旧 binary 上の reflection driver | 同上（旧 binary 前提） | 同上 |
| DeckRetirementDowngradeFixtureTest | #57: new_pause handshake fixture | `.paused`/`.ack` marker 検証のみで単体では無意味 | 同上 |
| DeckRetirementProcessIsolationTest | #57 AC-009: `:bugReport` process が migration を開かない | 実 multi-process + `am start-foreground-service` + process polling（10s）を merge gate に入れる flake surface が費用に見合わない。migration は既に完了し安定 | AC-009 の regression は CI 未検出。ADR-0006 契約の変更時は手動実行。follow-up 候補（§9） |
| GridChangeUnknownLockRecoveryTest | #287 AC-4: 整理→apply→recovery→実 grid 変更→UNKNOWN 化→review→再整理の全 loop | 40 member folder + 実 GridSizeMigrationUtil + 60s reload 待ちの最重量 E2E。merge gate への追加は費用に見合わず、かつ契約の触れる surface が複数（locks=JVM surface + db_schema）で lane 起動条件の設計が必要 | full loop は CI 未検出。LockAuthoring（R-12 routing）が grid 変化→UNKNOWN 化→review の部分契約を保護。lane/surface 設計を follow-up issue として §9 に記録 |
| DeckRetirementDeleteRegressionTest | #57 後: favorites insert/delete の基本動作 | retirement 完了後の low-value guard。実 DB 往復は routed 各 writer test が常時保護 | なし |
| DeckRetirementPackageRegressionTest | #57 後: LawndeckManager 削除済み guard（reflection） | 実装形状 guard（Class.forName / field scan）。re-introduction は review で捕捉する性質。3 つ目の test は tautology | なし |

### 3.3 Remove 提案

**なし。** unrouted 32 class のうち strict duplicate・assertion-free・無意味なものは
存在しなかった（§3.1/§3.2）。DeckRetirementPackageRegressionTest の第 3 test
（loadable 確認）は tautology だが、1 test method の削除で契約が守られないため、class
全体を diagnostic として残す。test 数削減を目的としない（Issue 明記）ため、曖昧な削除は
行わない。

---

## 4. JVM（tests/unit）監査（AC-458-P1-01/02 補完）

### 4.1 filter coverage

165 test class 中 163 が `organizer-unit-tests` の filter で実行される。 unrouted 2
class（§1.3）は純粋 JVM 契約で production caller が実在する。→ **Route**
（`organizer-unit-tests` filter を `app.lawnchair.*` 単一 pattern へ統一し、tests/unit
tree 全体を auto-include。§8）。統一により 2 class が gate に加わり、今後の package
追加も自動包含される（#41 の auto-include 原則と同じ）。

### 4.2 JVM ↔ instrumentation の重複分析（AC-458-P1-04 の契約レベル確認）

同一 production class を両層から検証する対は多いが、いずれも失敗モードが分割されており
（skill の retention bar に適合）、Consolidate/Remove 対象は無かった:

- RecoveryStore: JVM は codec/checksum/chunk 数学と decision table、instrumentation は
  実 SQLite persistence・sidecar・inspection 副作用・process death。明示分割（KDoc 記載）。
- Diagnostics export: JVM は writer/filename 契約、instrumentation は #288 session
  生存。1 shared assertion（timestamp pairing）があるが recreation 側は JVM 到達不能。
- Locks: JVM は 49 test の decision matrix（fake）、instrumentation は実 DB
  commit/rollback。
- Exchange: JVM は state machine / codec / controller（fake store）、instrumentation
  MethodChoice は「real holder/gate/store、transport のみ fake」を KDoc で明示し、unit
  oracle は early-warning と自己定義。
- Backup: JVM は critical section ordering（#187/ADR-0011）と純粋 summary、instrumentation
  は実 restore pipeline。

## 5. surface_organizer_ui lane 間 overlap 監査（AC-458-P1-04）

対象: manual-org-ui（9 class）/ category-override（2）/ exchange-import-ui（1）/
method-choice-journey（1）/ onboarding-proposal（2）。

### 5.1 繰り返されている journey と、それぞれの上位層 risk

| 契約 | 関与 class | 判定 |
|---|---|---|
| import journey（生成→取り込み→attach） | ExchangeImportSuccess（面 rendering）, MethodChoiceConnectedJourney g/h/o/q（state/gate/durability）, ManualOrgPreferences journey (a)（attach→preview + applyCalls==0）, ExchangeImportSurface（入力面 + failure のみ）, OrganizerHub（row 導線のみ） | 各 class の oracle level が異なる（rendering / durability / terminal state / 入力面 / 導線）。MethodChoice KDoc が (a)-(f) との分担を明示。**Retain**（上位層重複として契約あり） |
| 取り込み成功状態 | ExchangeImportSuccess（rendering + Back 契約）, MethodChoice（state + entryKind）, ManualOrgPreferences（面到達） | 同上。**Retain** |
| hub→request row→T-15→Back | OrganizerHub（minimal graph、State.Idle 断言、proposal row 変体）, OrganizerDiagnosticsRoute #372（production graph、実 durable store での session 生存 + strategy write） | hub 側は hub 面の導線契約、diagnostics 側は生存契約。near-subset だが host level が異なる。**Retain**（統合は hub 契約を弱める） |
| empty-cut → ScopeConfirmed | MissingAppSelection（本契約の primary owner）, ManualOrgPreferences ×2, UsageAccessJit, ManualOrgProductionE2E | primary owner は MissingAppSelection。他は各 journey の fail-closed gate 断言（inline step）。**Retain**（手術コストが契約を上回る） |
| scope-bound discard 確認 | ManualOrgPreferences journey (c)（rendered dialog）, MethodChoice m/s/v（gate/epoch level） | rendering と gate level の分割。**Retain** |
| strategy 選択 write | StrategyPicker（T-05 + validated write）, OrganizerDiagnosticsRoute #372（production route の arbiter）, ExchangeImportSurface（lease 非拒否） | **Retain** |
| #271 durable status / checking-row recovery | ManualOrgPreferences（run 面）, OrganizerHub（hub 面） | ほぼ同型 oracle が 2 surface。失敗モードは「どの面が status を壊すか」で別。**Retain**（将来の統合候補として記録） |
| #370 hub 唯一の入口 | OrganizerDiagnosticsRoute, OnboardingOrganizationProposal | 後者が above-the-fold geometry を追加。**Retain** |
| 200% font-scale | 10 class | 各面固有の reachability 契約（#218/#368 の方針）。**Retain** |
| focus restore / lifecycle | 複数 class（CategoryOverride 3, CustomCategory, ExchangeImportSurface/Success, ManualOrgPreferences 2, OrganizerHub 2, Onboarding, #308/#342/#375/#376 実績） | 面毎の focus 契約。recreation oracle は Onboarding（実 launcher recreate）と OrganizerDiagnosticsRoute（scenario.recreate）のみで分散していない。**Retain** |

### 5.2 subset 関係

- `StrategyPickerFreeze` は `StrategyPicker` の subset ではない（frozen reason / retry
  notice は Freeze 固有）。逆も同様。**両方 Retain**。
- strict subset は `OrganizerHub.hubRequestRowOpensTheRunFaceWithTheRequestFlowPreOpened`
  が diagnostics-route #372 test の 1 step に対して近いが、hub は `State.Idle` 断言と
  proposal row 変体を持つ。test method 単位の統合は hub 面契約を失うため見送し。

### 5.3 共有 fixture と重複 helper

- `InjectedInputEnvironment`（#300）の利用者は Onboarding / ManualOrgPreferences /
  OrganizerHub / StateMachine test の 4 class。他 11 class は raw shell / 独自 fake。lane
  起動単位（1 invocation = 1 process）の隔離設計どおり。
- `ensureWindowFocusedForComposeHost()` が ManualOrgPreferences と OrganizerHub に verbatim
  重複、back dispatcher helper が 5 class に複製、`validDigest` 定数が 2 file で重複、
  `ManualOrganization*Application` double が 10 class で各再宣言。→ **test-support 重複で
  あり契約重複ではない**。統合は maintenance 改善だが、instrumentation source set 内の
  共有 helper 抽出は test 挙動を変えない範囲で将来作業とし、本 Issue の変更リストには
  入れない（bounded 変更の原則。§9 follow-up）。

### 5.4 lane grouping（4→N 分割）

`surface_organizer_ui` の画面単位分割は #422 で意図的に v1 deferred。本監査でも、lane 間
契約重複が merge gate に害を与えている証拠（無関係 failure が merge を阻害した実績の
方向）は確認できなかった（§6 の環境系 failure は lane 分割ではなく #422 の分類・capture
政策で対応済み）。**分割不要・Retain**。逆に、本監査で R-13/R-14 の追加（locks UI、
diagnostics export UI）は co-occupant 前例（#342）に従う。

---

## 6. flaky / intermittent 分類（AC-458-P1-05）

#422 taxonomy（quality-strategy.md 正本）による分類。demotion/removal の前提記録であり、
本監査で demotion は提案しない。

| 対象 | 分類 | 証拠 |
|---|---|---|
| #352 `ExchangeFlowStateHolderTest`（JVM, 5s poll flake） | 3（test synchronization / harness defect）— screen-state race、tracking Issue 進行中 | docs/assessment/pr-425 / pr-412、tracking #352 |
| #304 onboarding lane focus-gate 不安定 | 5（emulator/runner/platform environment） | portfolio 監査表、tracking 進行中 |
| #418 SystemUI ANR / Compose timeout（issue52/99 lane） | 5（environment signature、#418 が代表追跡） | quality-strategy.md §intermittent、tracking 進行中 |
| ReadinessGateTest 既知 timing flake | 3（test harness defect、既知・記録済み） | docs/assessment/pr-314 / pr-319 / pr-325 |
| #372 focus-assert 環境適応（OrganizerHub） | 3（test harness defect）→ headless CI で node focus が得られない環境では断言を skip する修正済み。CI 上 focus oracle が弱まることを既知事項として記録 | git 31c2e1445e / f8f37a6261 |
| #308 Compose focus 同期（ManualOrgPreferences） | 3 → 修正済み | git 3aa6e83a1f |
| pr-325 StrategyPicker timeout | 実 UI regression（category 1 product regression）として捕捉された実績 | docs/assessment/pr-325 |

未分類の新規 flake は本監査では報告されなかった（routed lane の直近 CI は green 実績）。
routing で追加する class は CI 履歴がなく、§8 の validation で初回分類を行う。

---

## 7. test-only production/support seam インベントリ（AC-458-P1-06）

### 7.1 test のみが呼ぶ production member（non-test caller: なし）

| Seam（production path） | 何を露出するか | 利用 test | 判定 |
|---|---|---|---|
| `LayoutApplicationModule.mutexForTest(): RunMutex`（application/protocol/LayoutApplicationModule.kt:131、internal、issue #187 注記） | operation mutex への test 直参照 | unit RunMutexRestoreSuspensionTest | **Retain**。drain 状態は public seam から観測できず、seam は internal + 由来注記付き。削除は #187 drain 契約の coverage を失わせる |
| `CategoryOverrideCategoryPresentations.mappedIdsForTest(): Set<String>`（organizer/ui/CategoryOverridePresentation.kt:67、internal） | 内部 mapping の ID 集合 | unit 2 class | **Retain**。assertion 専用の internal accessor。削除時は presentation 公開面の拡大と引き換えで confidence は増えない |
| `ExchangeImportFailureDiagnostics.resetForTests()`（organizer/ui/exchange/ExchangeImportFailureDisplay.kt:279、@VisibleForTesting） | process-global diagnostics holder の reset | unit 1 + instrumentation 2 class | **Retain**。process-global 状態の test isolation に必須（production one-way semantics を壊さない標準型） |
| `UsageAccessJitGateProvider.resetForTests()`（organizer/ui/UsageAccessJitRequest.kt:219、test-only isolation seam と自己文書化） | process singleton + attempt token の reset | instrumentation 1 class | **Retain**。同上 |

いずれも「remaining rationale が test-only」だが「now-unused」ではない（§8 の Remove 条件
は now-unused seam のみ）。移動先 owner boundary も存在しない（mutex/diagnostics は内部
synchronization・global 状態であり、より低い層に faithful な観測点がない）。production
側に `*Seam*` / `*Fake*` / `*Fixture*` / `*Probe*` 名の class は存在しない
（PublicSeam は test 名のみ。`ProductionPublicSeamInstrumentationTest` は受入済み public
seam の使用 test であり production seam ではない）。

### 7.2 dual-use seam（production NOOP / default 引数）

`FaultInjector` + `RecoveryStoreFaultPort.NOOP`（production 常時 NOOP、test が recording
double — 文書化済み）、`ExchangeFlowUi` / `ManualOrganizationRun` / `StrategyWriteArbiter`
の default gate/store 引数（production wiring が実体を注入）、
`overlapAcceptanceHolds`（production caller 実在）— いずれも production design reason
あり。**Retain、変更なし**。

### 7.3 test-support（test source set 内）

`GridMigrationTestSupport`（routed db-migration 5 class と unrouted GridMigration 2 class
の共有 fixture — **CI load-bearing**）、`DeckRetirementTestRunner`（module runner +
smoke script 用 mode）、`InjectedInputEnvironment`、tests/unit の harness/fake 群
（16 file、全て covered test から利用）— 全て利用中。unused support file は存在しない。

---

## 8. Phase 2 変更リスト（bounded）（AC-458-P1-07）

**目的: unrouted standing regression の恒久実行化と JVM gate の穴埋めのみ。** test 削除・
lane 削除・UI lane 統合・production seam 削除・test 本体の挙動変更は含まない。

### 8.1 instrumentation routing（16 class → 既存 4 lane）

| Lane（file） | 追加 class |
|---|---|
| db-migration（ci.yml inline） | GridMigrationFailureTest, GridMigrationSuccessTest, LauncherPrefsCommitTest, DeckRetirementMigrationInstrumentationTest（5→9） |
| reservation-recovery（ci.yml inline） | RecoveryStoreInspectionInstrumentationTest, RecoveryInspectionSnapshotPublicationInstrumentationTest, OrganizerDurableStatusInstrumentationTest, RecoveryStoreChunkedManifestInstrumentationTest, Issue265GateFailedRouteInstrumentationTest, PageCaptureInstrumentationTest, LockAuthoringInstrumentationTest（7→14） |
| restore-capture（run-restore-capture-instrumentation.sh） | NovaRestoreGridApplicationTest + BackupExclusionTest + RestoreProfileRemapTest を 1 つの追加 connected stage として末尾に追加（#299 手順書の既存 stage 順序は変更しない） |
| category-override（ci.yml inline） | OrganizerLockScreenTest（2→3、#342 co-occupant 前例） |
| manual-organization-ui（run-manual-organization-ui-instrumentation.sh） | OrganizerDiagnosticsExportTimestampInstrumentationTest（9→10） |

費用見積: 追加分の合計でおよそ +4〜6 分（lane 並列のため wall への影響は最大 lane 分）。
main/scheduled full sweep の runner 合計は同程度増加。regression confidence は「常時
未実行だった 16 class の契約」分が 0 から 1 へ。

### 8.2 surface filter 追加（test path の自己検証規則を満たすため）

`ci.yml` `changes` job:

- `surface_layout_write` へ `tests/organizer-instrumentation/app/lawnchair/organizer/locks/**`
  （LockAuthoring の自己起動）
- `surface_organizer_ui` へ 同 locks glob（OrganizerLockScreen の自己起動）と
  `tests/organizer-instrumentation/app/lawnchair/organizer/diagnostics/export/**`
  （ExportTimestamp の自己起動）
- `surface_backup_restore` へ `tests/organizer-instrumentation/com/android/launcher3/organizer/BackupExclusionTest.java`
  と `.../RestoreProfileRemapTest.java`（schema test file の明示指定前例と同じ）
- `surface_db_schema` へ `tests/organizer-instrumentation/com/android/launcher3/LauncherPrefsCommitTest.java`

lane↔surface edge（map file）は不変。`ci_portfolio_map.yml` の変更不要、
`docs/engineering/ci-test-portfolio.md` の surface path 表と lane 監査表を同じ PR で更新。
GridMigration model/** mapping は over-trigger から実行を伴う mapping になる（文書の
注記更新）。

### 8.3 JVM gate

`organizer-unit-tests` の filter を 4 pattern から `app.lawnchair.*` 単一へ統一
（DeckRetirementArtifactNamesTest + DeviceProfileOverridesPresetResolutionTest が加わり、
tests/unit tree 全体が auto-include）。quality-strategy.md と portfolio 文書の filter
記述を同じ PR で更新。統一 pattern は現行 4 pattern の上位集合であり、tests/unit は
`app.lawnchair` root のみであるため、既存実行集合の縮小はない（統一前後の実行 class 数を
local で比較して検証する）。

### 8.4 文書記録（diagnostic 分類の明示）

- `docs/engineering/ci-test-portfolio.md`: unrouted inventory section を「routing 後の
  残存 diagnostic 16 class の分類記録」へ更新。lane 監査表に追加 class と契約を反映。
  UI lane overlap 監査の結論（§5）と flake 分類（§6）を追記。
- 本 `audit.md` に Phase 2 実施結果と検証証跡を追記（status 更新）。

### 8.5 validation 計画（AC-458-P2-04/05/06）

1. `python3 tools/repo-contract/validate_ci_portfolio.py` /
   `test_validate_ci_portfolio.py` / `validate_repo_contract.py` /
   `test_validate_repo_contract.py`
2. `./gradlew spotlessCheck`
3. JVM: filter 統一前後で `testLawnWithQuickstepGithubDebugUnitTest` の実行 class 数比較
   （既存集合の縮小なし確認 + 2 class 追加確認）
4. 実 emulator（local）で追加 16 class を実行し、初回 CI 投入前の green を確認
5. PR 上の実 GitHub Actions run で対象 lane（db-migration / reservation-recovery /
   restore-capture / category-override / manual-organization-ui）+ JVM gate が green
   （AC-458-P2-06 の正本証跡）
6. routing で失敗した class があれば、修正（category 2/3）または disposition を
   Diagnostic-local-only へ戻して PR 内に記録

---

## 9. residual risk / follow-up（AC-458-P2-07）

| 項目 | 状態 |
|---|---|
| GridChangeUnknownLockRecovery の full loop（grid 変更→UNKNOWN lock→review→recovery） | CI 未検出のまま。lane/surface 設計（locks test path が複数 surface に跨る）が必要 → follow-up issue で起動条件を決める |
| DeckRetirementProcessIsolation（AC-009 二次 process gate） | CI 未検出のまま。process polling の flake surface を理由に diagnostic 継続 |
| UsageAccessTransitionProbe / Issue108GridEvidence の platform 遷移・capture parity | diagnostic 継続。reader app-op 追従の regression は CI 未検出 |
| organizer UI instrumentation の helper 重複（focus helper 5 class、Application double 10 class、validDigest 2 file） | 契約重複ではない。test-support 統合は将来の maintenance Issue |
| surface_organizer_ui の画面単位分割 | #422 からの意図的 deferred を維持（根拠 §5.4） |
| API 35 lane の代替不能性根拠未取得 | #422/#96 からの繰り越し。本監査でも新証拠なし（Non-goal: reopen しない） |
| #352 / #304 / #418 tracking | 既存 tracking issue が所有（§6） |
| cold-process restore CTA（#376 RS-AC-01）の CI 検出 | diagnostic 継続。重い 2 phase orchestration |
| routed 16 class の初回 CI 履歴不在 | Phase 2 の validation（§8.5）で初回 green を取得。失敗時は分類して修正または diagnostic 返し |
