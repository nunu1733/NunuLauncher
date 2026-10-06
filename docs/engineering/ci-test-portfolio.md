# CI Test Portfolio and Runtime Baseline

> Status: Implemented
> Scope: source-changing Pull Request CI、main / scheduled regression sweep（Issue #422 の impact-based portfolio 再編後の正本）
> Updated: 2026-10-01（Issue #477/#479: manual-organization-ui lane の issue372 touch oracle quarantine を解除 — root cause を production 側で修正し、lane に復帰（本PR）。Issue #458 で unrouted instrumentation/JVM class の semantic 監査と routing を実施。Issue #438 で全 10 instrumentation lane を live failure capture に統一。Issue #422 で監査・impact-based gate 化。Issue #96 時代の記録は履歴として末尾に残す）

この文書は CI portfolio の監査表（contract・分類・起動条件・実行費用・過去 failure 分類）の
正本である。lane↔surface 対応（edge）の normative 正本は
[tools/repo-contract/ci_portfolio_map.yml](../../tools/repo-contract/ci_portfolio_map.yml)
であり、本文書は同じ対応の human-readable mirror である。両者が食い違う場合は map file が
正である（`validate_ci_portfolio.py` が map↔workflow の edge 完全一致を検証する）。

## Portfolio model（Issue #422）

| 分類 | 対象 | どこで走るか |
|---|---|---|
| Permanent PR gate | `validate-repo-contract`、`check-style`、`build-debug-apk`、`organizer-unit-tests` | `validate-repo-contract` は全 run（docs-only 含む）。残り 3 つは `permanent_run`（source \|\| ci \|\| full \|\| smoke） |
| Conditional PR gate | 10 本の instrumentation lane | `instrumentation_enabled && (full \|\| own surface)`。surface は変更 diff から `changes` job が判定 |
| Main / scheduled regression | 同一 portfolio 全量 | main push・週次 schedule・`workflow_call`・`workflow_dispatch(full-portfolio=true)` は path にかかわらず全 lane（Permanent gate を含む） |
| Smoke | repository contract + Permanent gate のみ | `workflow_dispatch(full-portfolio=false)`。paths 判定に依存しない決定的実行（instrumentation 全 skip） |
| Diagnostic only | planner stress matrix、#418 系の診断 run | merge gate に恒久追加しない。`planner-stress.yml`（週次 / manual）と個別診断 branch |

起動判定の正本は `tools/ci/compute_ci_gating.py` が適用する次の規則である。

```text
unmapped_files = source_files - (∪ surface マッチ file)   # per-path fail-closed
smoke          = workflow_dispatch && full-portfolio == false
full           = !smoke && ( ci || unmapped あり || schedule || workflow_call
                            || main push || dispatch-full )
permanent_run  = source || ci || full || smoke
instrumentation_enabled = !smoke
```

- **per-path fail-closed**: diff に 1 件でも未 mapping の source file（`quickstep/**`、
  `src/` 直下の未 mapping file、build script、`Android.bp` 等）があれば全 source lane が
  起動する。mapped / unmapped 混在でも発火する。mapping の隙間が gate の静かな skip と
  して現れないための保守 default である。
- **event ごとの差分評価**: 増分単位の conditional 判定は `pull_request` event が所有
  する（PR files API）。`push` event（`*-dev` branch）では dorny/paths-filter は
  branch と default branch の差分を評価する（増分の上位集合のため常に安全側へ働く。
  実測: 2026-09-24 の `422-demo-dev` run）。main push は常に全量、schedule /
  workflow_call / workflow_dispatch は paths 判定を使わない。
- test path は directory 粒度で surface 割り当てており、隣接 lane の過剰起動（over-trigger）
  を意図的に許容する（list 二重管理による取りこぼしより安全側である）。ただし各 lane が
  実行する test class の path はその lane の surface に必ず含み、**test のみの変更でも
  当該 lane が自己検証される**（db-migration の schema 4 test file は広い glob と重複
  しても明示指定する）。
- `source` filter は docs / specs / `.github` / `tools/repo-contract` 等を除外する広い
  母集合（Issue #8 由来）。未知の上流 module は自動的に unmapped → full となる。

## lane↔surface mapping（map file の mirror）

| Lane（job ID） | 起動する surface | 守る contract（概要） |
|---|---|---|
| organizer-instrumentation-shared-writer-tests | surface_layout_write | coordinator / transaction / reload / restore-lease seam（#113/#117/#119/#120/#156）+ direct-edit write shape（#448: folder作成2行transaction rollback・admission内stage-2検証の順序）+ direct-edit production seam（#448: `DirectEditModelWriterTest`。実`ModelWriter` direct-edit操作のadmission・stage-2検証・DB+model+`FolderInfo.contents`同期・失敗注入rollback・ORGANIZER lease defer）+ edit surface適用統合（#449: `EditSurfaceApplyInstrumentationTest`。セッション→plan→実adapter適用の1 transaction、manifest欠落行の物理DELETE（削除pass）、無題フォルダINSERT+子UPDATE、失敗注入rollback、stale零書込み）+ direct-edit undo逆操作（#450: `DirectEditUndoModelWriterTest`。逆操作3種のadmission内stage-2検証・削除前行payload capture忠実度・availability fail-closed零書込み・1 transaction rollback・stale零書込み）+ undo契約oracle（#450: `EditSurfaceUndoInstrumentationTest`/`HomeEditUndoAvailabilityInstrumentationTest`。実confirm→record→executor→recoveryの契約oracle、availability零書込み）+ targetSdk 37編集面契約oracle（#526: `EditSurfaceTarget37ContractInstrumentationTest`。再作成×{selection-only, 計画あり, 未操作}の破棄案内有無・InFlight適用中のシステムback握り潰し（非適用時back=cancelの対照含む）・再作成と適用の二重適用禁止（InFlight/Correlating中のbeginApply拒否と相関capture完了までのConfirm不許可）・破棄案内のliveRegion=Polite。spec 526の必須oracle。gate状態機械の純粋遷移はJVMの`EditSurfaceApplyGateTest`が一次owner）。UI操作の証跡（AC-1/AC-9）は `HomeEditUndoEvidenceToolingTest`（CI lane外のon-demand evidence tooling。#376 cold-process evidenceと同一扱い）が生成する |
| organizer-instrumentation-db-migration-tests | surface_db_schema | schema upgrade/downgrade transaction ownership（#118/#115/#14、rollback32）。#458 で grid-migration success path（#458 R-2a）、commit-aware preferences primitive（#59）、Deck retirement startup migration 冪等性（#57）、restore profile remap の lock 保持（#58）を追加。#461 で corrupt durable-recovery source の fail-closed 契約（`GridMigrationFailureTest`、test 本体は変更なし）を routing |
| organizer-instrumentation-restore-capture-tests | surface_backup_restore | Nova restore → capture 契約（#299、cross-process 2 stage）。#458 で cleanUpDatabases restore lease guard（#168）を独立 connected invocation として追加 |
| organizer-instrumentation-production-input-tests | surface_production_input, surface_layout_write | production input composer / 実 adapter 互換（#83、API 35）+ nested transaction の API 版依存回帰 |
| organizer-instrumentation-manual-organization-ui-tests | surface_organizer_ui | manual organization E2E / hub / strategy picker / exchange import success / diagnostics route（#52 系の広い UI sweep）。#458 で diagnostics export timestamp + recreation 契約（#288）を co-occupant 追加。#441 で editing-burden benchmark fixture seeding 契約（同一入力→同一fixture・hotseat/予約領域保持・identity構成）を co-occupant 追加 |
| organizer-instrumentation-reservation-recovery-tests | surface_layout_write | QSB reservation / recovery store / overlap gate / #265/#269 の実 writer oracle（#155 系）。#458 で recovery store inspection/publication/durable-status/chunked-manifest（#84/#89/#271/#174）、#265 gate-FAILED fail-closed routes、page capture ordering、実 DB lock authoring（#38）を追加（focused validation で順序非依存を確認） |
| organizer-instrumentation-category-override-tests | surface_organizer_ui | category override authoring UI（#99）+ custom category management UI（#336/#342）。#458 で lock UI semantics/focus/font-scale（#38/#211）を co-occupant 追加 |
| organizer-instrumentation-exchange-import-ui-tests | surface_organizer_ui | exchange import surface UI（#332/#345） |
| organizer-instrumentation-method-choice-journey-tests | surface_organizer_ui | method-choice face の connected journey（scope確定後の AI依頼作成・取り込み・attach、#417 AC-8 (g)-(v)） |
| organizer-instrumentation-onboarding-proposal-tests | surface_organizer_ui | onboarding proposal lifecycle / 実入力 environment（#53/#300） |

surface 定義（path filter）は `ci.yml` の `changes` job が所有する:

| Surface | 主な path | 備考 |
|---|---|---|
| surface_layout_write | `LayoutWriteCoordinator.java`、`ModelWriter.java`、`ModelDbController.java`、`organizer/application/**` + 該当 test 群。#458 で `tests/organizer-instrumentation/app/lawnchair/organizer/locks/**` を追加（LockAuthoringInstrumentationTest の自己起動。surface_organizer_ui との重複は安全側）。#449 で `lawnchair/src/app/lawnchair/homeedit/**` と `tests/organizer-instrumentation/app/lawnchair/homeedit/**` を追加（編集画面が同一適用経路を駆動するための自己起動） | apply / recovery / store seam。fan-out 先 2 lane + production-input lane |
| surface_db_schema | `provider/**`、`DatabaseHelper.java`、`GridSizeMigrationUtil.java`、`lawnchair/src/app/lawnchair/migration/**` + 該当 test 群（schema 4 test file は明示指定で self-trigger を保証。`com/.../organizer/` 直下は surface_layout_write の広い glob と重複するため）。#458 で `LauncherPrefsCommitTest.java` と `RestoreProfileRemapTest.java` を明示追加（#458 で lane class list に加わったため）。#532 で `RestoreDbTaskSuccessPathTest.java`、`RealZipRestoreE2E.kt`、`PrefsLegacyXmlMigrationTest.kt` を明示追加（G4 T4/T5/T7 oracle が db-migration lane class list に加わったため。`RealZipRestoreE2E` は surface_backup_restore との重複を許容する安全側） | |
| surface_backup_restore | `lawnchair/src/app/lawnchair/backup/**`、`LauncherBackupAgent.java` + 該当 test 群。#532 で G4 T8 の converter 境界 oracle（`NovaConverterBoundaryTest` / `NovaConverterBoundarySmartspaceOffTest` / scenario base）がこの glob 配下に加わった。`RealZipRestoreE2E`（T5）は db-migration lane に routing するため surface_db_schema 側へも明示登録（重複は安全側） | |
| surface_production_input | `organizer/integration/**` + 該当 test 群 | API 35 互換契約 |
| surface_organizer_ui | `organizer/ui/**`、`organizer/*`（root file 群）、`lawnchair/src/app/lawnchair/ui/**`、`organizer/personalization/**`、`lawnchair/res/**` + 該当 test 群。#458 で `tests/organizer-instrumentation/app/lawnchair/organizer/locks/**` と `.../organizer/diagnostics/export/**` を追加（OrganizerLockScreenTest / OrganizerDiagnosticsExportTimestampInstrumentationTest の自己起動） | v1 は UI 4 lane を同一 group とする（画面単位分割は後続） |
| surface_jvm | `organizer/planning/**`、`organizer/rules/**`、`organizer/diagnostics/**`、`organizer/locks/**`、`tests/unit/**` | instrumentation lane なし。`organizer-unit-tests`（Permanent gate）が所有。planner 契約は JVM corpus / property test が oracle であり、PR での emulator 起動は要求しない（main / scheduled sweep は通過検証する） |

## 監査表（全 job・補助処理）

監査項目は Issue #422 の定義による: 守る production regression、低層 test では不足する理由、
必要 impact surface、fan-out、重複、独立実行要件、過去 failure 分類、PR gate 必要性、
費用、分類。

| Job / 処理 | 監査結果 | 分類 |
|---|---|---|
| changes | path filter + per-path fail-closed 集約（`compute_ci_gating.py`）。0.4 分。自己検証は `validate-repo-contract` job 内の self-test（15 case、悪意ある filename・smoke・混在 diff を含む）と workflow 変更 PR の全量自己実行 | Permanent（全 run） |
| validate-repo-contract | 文書 link・Issue form・repo contract 各種 validator + 本 portfolio validator。0.9 分。全 run で必要（docs-only も対象）。低層での代替なし | Permanent（全 run） |
| check-style | spotlessCheck。1.1 分。source の最低 gate。style は JVM test より低層で判定可能な契約 | Permanent |
| build-debug-apk | assembleLawnWithQuickstepGithubDebug。5.3 分。buildability の独立 evidence。artifact reuse は未検証のため独立維持（#96 判断を踏襲） | Permanent |
| organizer-unit-tests | organizer JVM contract / property / unit 一式。5.8 分。planner・rules・diagnostics・locks・personalization import・bugreport・backup unit の oracle。`surface_jvm` 領域の一次 gate。#458 で `app.lawnchair.migration.*`（#57 artifact names 契約）と `DeviceProfileOverridesPresetResolutionTest`（#134 preset 解決契約）を filter 追加（旧来 unrouted だった純 JVM class）。#452 で `app.lawnchair.ui.popup.*`（popup option order の位置補完・書込みguard条件・lock filter契約）を filter 追加（同じく旧来 unrouted だった純 JVM class。`tests/unit/**` は surface_jvm に path-mapped済み）。#532（plan §7 G4 T7）で `app.lawnchair.preferences2.SharedPreferencesLegacyKeyMigrationTest`（legacy XML→DataStore key変換ロジックの JVM oracle。device readback 半分は db-migration lane）を filter 追加。#443 で凍結対象のAI交換UI suite（`app.lawnchair.organizer.ui.exchange.*`、6 class / 139 test、#352 oracleを含む）を `-Pnunu.excludeAiExchangeUnitTests=true` のproperty-gated filter（build.gradle）でmerge gateから除外。pipeline/contract層（`integration.exchange`、`personalization.exchange`）はgateに維持。除外の根拠はFR-017凍結（#439、#352は「lane外し後にclose」の取り決め）。凍結suiteは自動CI（main/weekly/scheduled含む）では実行されず、local/manual実行のみで観測する | Permanent |
| shared-writer lane | 8.9 分。`MODEL_WRITER` deadlock・lease admission・Binder future・nested transaction・reload supersession・restore lease・Hotseat admission・direct-edit write shape + production seam（#448）。JVM で再現不能な実 framework transaction / binder / loader threadaffinity に依存するため emulator 必須（#113〜#120 の実績）。isolated fixture DB で UI と状態分離。過去 failure: production regression として捉えた実績（compile-only escape #115 は db-migration 側） | Conditional（surface_layout_write） |
| db-migration lane | 9.1 分。schema upgrade/downgrade の失敗意味論・rollback32 binary。実 SQLite / platform 依存。#115 で「compile-only のまま実契約下で失敗し続けていた」実績があり emulator 実行が本質。#458 で +4 class（grid-migration success path、commit-aware prefs、Deck retirement migration 冪等性、restore profile remap lock 保持）。#458 追加分の実測は本再編 PR の CI run で更新。#532（plan §7 G4）で +3 class: `RestoreDbTaskSuccessPathTest`（T4: performRestore 成功入口の実DB assert。既存3 classはlease/remap/reentryを所有し重複しない）、`RealZipRestoreE2E`（T5: real ZIP32/33 fixture を通常 restore 経路で復元し row/lock/migration_src prefs と cold-start の recovery store Pristine を確認）、`PrefsLegacyXmlMigrationTest`（T7 device readback 半分。JVM 半分は organizer-unit-tests）。#532 追加分の実測は G4 実行 PR の CI run で更新 | Conditional（surface_db_schema） |
| restore-capture lane | 9.5 分。Nova restore の cross-process 2 stage・widget window・unknown provider。実 backup 成形と process death が必須。class ごとに独立 `am instrument`（#299 手順書が正本）。#458 で NovaRestoreGridApplicationTest（#168 cleanUpDatabases lease guard）を独立 connected invocation として末尾に追加。#458 追加分の実測は本再編 PR の CI run で更新。#532（plan §7 G4 T8）で Nova converter 通常入口の境界 fixture oracle（`NovaConverterBoundaryTest` / `NovaConverterBoundarySmartspaceOffTest`。fractional 四フィールド / smartspace ON-OFF / clamp・skip。1 class = 1 restore = 1 プロセスのため ON/OFF は別 invocation）を script 末尾の per-class invocation に追加。#532 追加分の実測は G4 実行 PR の CI run で更新 | Conditional（surface_backup_restore） |
| production-input lane | 9.3 分（API 35）。実 platform での production input composer 互換（#83）。API 36 で代替できない根拠は未取得（#96 からの繰越判断）。category override atomic file の restart writer/reader も実 filesystem 必須 | Conditional（surface_production_input + surface_layout_write） |
| manual-organization-ui lane | 10 分（#479 review round 3 で drag-guard oracle を独立 instrumentation invocation に分離し +1.3 分）。manual organization の縦切り E2E（capture→plan→apply→recovery→UI）。DB heavy fixture を他 lane と共有しない。UI evidence 画像を常時 upload。#458 で diagnostics export timestamp + recreation 契約（#288）を co-occupant 追加。#441 で editing-burden benchmark fixture seeding 契約（DB reload + 実 package 解決が必須）を co-occupant 追加。#477/#479 quarantine は解除済み（2026-10-01）: touch oracle restored to the lane — #479 root cause: hub exchange row could be laid out in the app-bar contentPadding gap by a LazyListState anchor race → fixed in OrganizerHubPreferences; test-side strategy-row scroll discipline added. 分離済み a11y oracle と診断/失敗瞬間スクリーンショットは lane 内に維持。#479 review round 3: `OrganizerHubDragGuardInstrumentationTest`（AC-2 drag-guard saveable-restore oracle）は lane 内で独立 instrumentation invocation（別プロセス）で実行 — その system-level drag injection が同一プロセスの後続 keyboard/focus テストの配信を乱すため（3/3 CI runs で再現、oracle-ignored variant との bisect で確定）。#441/#458 追加分の実測は本再編 PR の CI run で更新 | Conditional（surface_organizer_ui） |
| reservation-recovery lane | 9.4 分。QSB 予約・recovery store lifecycle・overlap acceptance gate・#265/#269 の実 writer/recovery oracle。独立 storage を扱うため clean emulator 必須。#458 で +7 class（recovery store inspection/publication/durable-status/chunked-manifest、#265 gate-FAILED routes、page capture、実 DB lock authoring）。focused local validation で co-occupancy の順序非依存を確認（audit §8.6）。#458 追加分の実測は本再編 PR の CI run で更新 | Conditional（surface_layout_write） |
| category-override lane | 8.1 分。authoring UI の semantics / focus / font-scale / touch target。#342 で #336 管理UI（`CustomCategoryPreferences`）を co-occupant として追加（同一 surface 内 class 追加のため map edge 変更なし）。#458 で lock UI semantics/focus/font-scale（#38/#211 `OrganizerLockScreenTest`）を同様の co-occupant として追加（fake module・DB なし）。Compose UI 検証は JVM で代替不能。#458 追加分の実測は本再編 PR の CI run で更新 | Conditional（surface_organizer_ui） |
| exchange-import-ui lane | 10.2 分。exchange import surface の Compose 検証（#345 で local-only から昇格。CI green の実績あり） | Conditional（surface_organizer_ui） |
| method-choice-journey lane | method-choice face の connected journey（#417 AC-8 (g)-(v) evidence。scope-first で凍結した scope 上の AI依頼作成 → 取り込み → attach を固定する per-class lane） | Conditional（surface_organizer_ui） |
| onboarding-proposal lane | 9.7 分。proposal lifecycle / Back / focus / recreation / review admission。実入力注入は focus 観測を前提とする（#300 accepted、#304/#418 で環境系 failure 実績） | Conditional（surface_organizer_ui） |
| failure-time evidence capture | 全 10 lane が `capture-emulator-failure-evidence.sh`（#315: bounded・continue-on-error）+ 14 日 artifact。失敗の原因分類を rerun 前に可能にする補助処理。Issue #438 で全 10 lane が live emulator-runner wrapper内 capture に統一された（#437 が manual-organization-ui / category-override / onboarding-proposal、#438 が残り 7 lane）。restore-capture と production-input の複数 stage 列は per-lane helper script（`run-restore-capture-instrumentation.sh` / `run-production-input-instrumentation.sh`）に保持され、最初の失敗 stage で teardown 前に capture する。production-input は tee→grep oracle の既存 failure semantics を保持するため `set -eu`（pipefail なし）。validator が「live wrapper 必須・runner 外 capture step（timeout 記法や step 名に依存しない token 単位の検出）拒否」を機械検証 | 補助（各 lane） |
| per-class instrumentation invocation | #532 G4（replay-log `specs/516-16-rebase-phase2/replay-log.md` §6.4）で判明した anchor AGP（Gradle 9.8 内蔵 test runner）の制約への対応。runner は `-Pandroid.testInstrumentationRunnerArguments...` を `k=v,k=v` ペアとして parse するため、comma 区切りの複数 class filter は **最初の 1 class しか dispatch せず** 残りが静かに欠落する。複数 class を持つ 5 lane（shared-writer / db-migration / reservation-recovery / category-override / onboarding-proposal）を `tools/ci/run-instrumentation-per-class.sh`（1 class = 1 connected invocation、fail-fast。#299 の per-class 原則と同一方式）経由に変更した。lane・surface・class 集合は不変（新規恒久 lane は増やさない）。per-class 化による lane 実測時間の更新は G5 の CI run で行う | 補助（各複数 class lane） |
| failure capture lifecycle self-test | `validate-repo-contract` で全 run（docs-only 含む）に起動する 0.1 分未満の契約test。既存のcapture helper smoke testが各adb commandのtimeout・budget・出力上限を検証するのに対し、本testは `android-emulator-runner@v2` のrunner `script`が物理行単位で実行される境界、failure captureがemulator teardown前に走ること、元command statusの保持、success時の無capture、全 10 lane（#437 の 3 lane + #438 の 7 lane、helper 経由 lane の実行可否を含む）のworkflow wiringを検証する。分類は CI wrapper / artifact handling。10 laneのimpact surfaceを新設せず、既存のfailure-time capture補助処理の全lane契約を検査するため、既存testとの重複はない | 補助（`validate-repo-contract` 全 run） |
| final-status | 「当該 run に必要と判定された gate が完了したこと」を集約。skip は成功扱い、failure/cancelled のみ fail。needs の必須集合は validator が map file と突き合わせ | 集約（branch protection required check） |
| planner-stress.yml | 8 seed × 512 case の exploration matrix。週次 / manual のみで PR gate でない（#46 の時点から分類適合） | Scheduled / Diagnostic |
| high-risk-gate.yml | risk label / 高リスク path PR への独立 audit 記録検証。job ID 結合（`organizer-unit-tests` / `check-style` / `build-debug-apk` / `final-status`）は map file の `permanent_gates` 固定点として validator が保護 | Permanent（label/path 条件付き） |

### 未 routing test の disposition（Issue #458 semantic 監査）

`tests/organizer-instrumentation/` には CI lane の class list に含まれない instrumentation
test が 32 candidate 存在した（tracked 31 class + #265 が untracked working-tree evidence
として保持していた harness 1 class。各 lane の Gradle invocation は明示 class filter のため、
未 routing class は full portfolio でも実行されない。#422 で記録、routing 判断は後続
Issue に deferred）。Issue #458 が全 32 candidate を semantic 監査し、正本は
[specs/458-semantic-test-audit/audit.md](../../specs/458-semantic-test-audit/audit.md)
である。結果の要約:

- **Route（14 class → 既存 5 lane）**: recovery store inspection / publication /
  durable-status / chunked-manifest（#84/#89/#271/#174）、#265 gate-FAILED routes、page
  capture、実 DB lock authoring（#38）→ reservation-recovery lane。grid-migration success
  path（#458 R-2a）、commit-aware prefs（#59）、Deck retirement migration 冪等性（#57）、
  restore profile remap（#58）→ db-migration lane。NovaRestoreGridApplicationTest
  （#168）→ restore-capture lane（独立 invocation）。OrganizerLockScreenTest（#38/#211）
  → category-override lane。OrganizerDiagnosticsExportTimestamp（#288）→
  manual-organization-ui lane。focused local validation（audit §8.6）で初回 baseline を
  取得済み。
- **Route（JVM 2 class → organizer-unit-tests filter）**:
  `app.lawnchair.migration.DeckRetirementArtifactNamesTest`、
  `app.lawnchair.DeviceProfileOverridesPresetResolutionTest`。
- **Move boundary（1 class）**: `BackupExclusionTest`（recovery DB の backup allowlist
  外 guard）は JVM test（`RecoveryDbBackupExclusionTest`）へ移管し、instrumentation class
  は削除。
- **Diagnostic-local-only（16 class）**: smoke/evidence driver 群（Deck retirement
  upgrade/downgrade walk、organizer process-death smoke、#376 cold-process evidence、
  #368 T-05 evidence、#203 probes、#108/#134 evidence、GridChangeUnknownLockRecovery、
  #57 retirement guards）。恒久 lane へ昇格しない。個別の理由は audit §3.2。
- **Routing 分離（1 class）**: `GridMigrationFailureTest` — focused validation で corrupt
  durable-recovery source に対する fail-closed 契約違反（category 6 調査）を初検出。
  production 調査・修正と routing は [#461](https://github.com/nunu1733/NunuLauncher/issues/461)
  が所有し、#461 で db-migration lane class list へ routing 完了（test 本体は変更なし）。
- **前提修復（3 class、test のみ・契約 assertion 構造は不変）**: #417 scope-first flow への
  整合 + #371 granted fast path 前提（#265GateFailedRoute）、#155 first screen 契約への
  期待値整合（PageCapture）、active DB 生成前提の追加（DeckRetirementMigration）。
  併せて #265GateFailedRoute の report-only writerBusy 観測は #265 の
  will-not-investigate disposition に従い、同 file 内の別 class
  `Issue265WriterBusyObservationTest`（CI 非routing・diagnostic）へ分離した。

`tests/organizer-instrumentation/com/android/launcher3/model/**` の `surface_db_schema`
mapping は、#458 で `GridMigrationSuccessTest`、#461 で `GridMigrationFailureTest` が
db-migration lane class list に加わったため over-trigger のみの mapping ではなくなった。

## intermittent failure の分類と evidence・retry 方針

正本は [quality-strategy.md](./quality-strategy.md) の該当 section。要約:

- 失敗は (1) product regression、(2) deterministic test defect、(3) test synchronization /
  test harness defect、(4) CI wrapper / artifact handling defect、(5) emulator / runner /
  platform environment defect、(6) unknown のいずれかに分類してから再実行する。
- rerun で green になっても、分類と（失敗時）capture 証拠なしに merge evidence としない。
- 一時的 failure = production 無関係とは扱わない。#304/#418 の環境系 signature は調査
  Issue が所有し、merge gate からの無条件除外は行わない。

### #418 系 thread-affinity signature の分類（2026-10-03、Issue #418）

[Issue #418](https://github.com/nunu1733/NunuLauncher/issues/418) が追跡してきた
thread affinity違反系signatureの分類と、spec
[418-organizer-run-publication-confinement](../../specs/418-organizer-run-publication-confinement/spec.md)
による対応:

| Signature | 観測 | 分類 | 対応 |
|---|---|---|---|
| T2: `LifecycleRegistry.removeObserver must be called on the main thread`（worker上のdialog dispose） | [run 35990634088](https://github.com/nunu1733/NunuLauncher/actions/runs/35990634088) | production off-main publication軸（`ManualOrganizationRun`がcaller worker上でUI状態を公開）を静的に追えた唯一のsignature | **本変更（spec 418）で除去対象**。CI非再現は修正の証明としない（統計観察は#418継続） |
| T1: `Detected multithreaded access to SnapshotStateObserver` | [run 35886970989](https://github.com/nunu1733/NunuLauncher/actions/runs/35886970989) | category 6（unknown）。静的監査によりfailure後のrun操作は説明要因から除外済み | **本変更の対象外**。#418で原因調査継続 |
| T3: `CalledFromWrongThreadException`（IO worker上のinline applyChanges） | [run 36251746356](https://github.com/nunu1733/NunuLauncher/actions/runs/36251746356) | category 6（unknown）。run面を経由しないhub画面のDataStore + Compose test環境のtiming raceと分類 | **本変更の対象外**。#418で原因調査継続 |

本変更の回帰oracleは、既存 `organizer-unit-tests` gate内の
`ManualOrganizationRunPublicationConfinementTest`（全UI状態書込みの実行thread検証と、publication hop完了までcallerがunwindしない uninterruptible join契約）と
`ManualOrganizationRunAdmissionPublicationTest`（spec 375 gate内完結・journal順序の維持）が
所有する。instrumentation回帰は既存manual-organization-ui laneの
`UsageAccessJitInstrumentationTest`（生thread軸）が担当し、新laneは追加しない。
- Issue / PR の acceptance evidence は「full workflow N 連続 green」を機械的に要求せず、
  変更 risk と対象 surface に対応して選択する（[github-workflow.md](../project/github-workflow.md)
  の evidence 選択原則）。

### Failure capture lifecycle self-test の追加判定

`tools/ci/test_emulator_failure_capture_lifecycle.sh` は `validate-repo-contract` job 内の
全run self-testであり、新しいinstrumentation lane、production surface、artifact routing edgeを
追加しない。ただしquality-strategyの新規test規則に従い、`tools/repo-contract/ci_portfolio_map.yml`
の`contract_tests` metadataにpath・command・owner・trigger・impactを記録し、validatorがworkflow
invocationと一致することを検査する。

| 審査項目 | 判定 |
|---|---|
| 既存testで不足する理由 | `test_capture_emulator_failure_evidence.sh` は fake `adb` によるsnapshot収集契約だけを検査する。runner actionが emulator をteardownする前に wrapper が capture を呼ぶこと、元のcommand statusを保持すること、workflowの実script/upload wiringを検査できないため、runner境界のlifecycle oracleを別に置く。 |
| 分類・impact・所有 | deterministic repository contract self-test、impactは `ci-wrapper-artifact-handling`、ownerは `validate-repo-contract`。instrumentation laneやproduction behaviorのcoverageを所有しない。 |
| 重複 | helper smokeはbounded snapshotのtimeout/budget/truncationを担当し、本testはwrapperのstatus保持、device-gone、runner action identity、実scriptの`--`、failure-time upload pathを担当する。別emulator laneのtestとは重複しない。 |
| 起動条件 | `contract_tests` metadataの`trigger: every_run`に固定し、docs-onlyを含む全runで既存repo-contract jobから実行する。emulator laneの再実行は発生させない。 |

残り7 laneのlive化は Issue #438 で完了した（#437 の 3 lane とあわせ全 10 lane が live
wrapper capture に統一され、runner 外 capture step は削除済み）。validator 側も live
capture 必須契約へ引き上げられており、runner 外 capture への退行は repo-contract gate
で機械阻止される。

## 実測（参考値）

- source 変更 PR の全量 run（旧構成、run 35864884049 / PR #420）: wall 約 10.7 分、
  runner 合計約 96 分（9 emulator lane 並列）。本再編後は、mapped-only の PR では
  起動 lane が減り、planner-only 変更（`surface_jvm`）は emulator 0 本になる。
- docs-only PR: 約 1〜2 分（repo contract のみ）。この挙動は本再編でも不変。
- 週次 schedule は planner-stress（日曜 18:30 UTC）と時間をずらし 19:30 UTC に設定。

## 新規 test / CI lane 追加時の審査ルール

正本は [quality-strategy.md](./quality-strategy.md)。追加時は (1) 既存 lane / test で
cover できないか、(2) より低く速く決定的な層に置けないか、(3) どの impact surface に
属し何时起動するか、(4) 既存 lane と重複しないか、(5) scheduled では不足する理由、を
PR に記載し、`ci_portfolio_map.yml` と本監査表を同じ PR で更新する（validator が整合を
強制する）。Issue 完了時の一時 diagnostic test をそのまま恒久 PR gate にしない。

## 履歴（Issue #96 時代の記録）

2026-08-21 時点の baseline（PR #95: 15分14秒 wall / 単一 instrumentation job 14分33秒）から
PR #97（9分02秒、lane 分割による直列待ち解消、runner 総量は増加）への計測と判断の経緯は
git 履歴（Issue #96 実装時点の本文）を参照。API 35/36 統合・artifact reuse・path filter
狭小化の保留判断は本監査が引き継いだ（上記の監査表の通り、統合は見送り・reuse は独立維持）。
