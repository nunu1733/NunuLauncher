# CI Test Portfolio and Runtime Baseline

> Status: Implemented
> Scope: source-changing Pull Request CI、main / scheduled regression sweep（Issue #422 の impact-based portfolio 再編後の正本）
> Updated: 2026-09-24（Issue #422 で監査・impact-based gate 化。Issue #96 時代の記録は履歴として末尾に残す）

この文書は CI portfolio の監査表（contract・分類・起動条件・実行費用・過去 failure 分類）の
正本である。lane↔surface 対応（edge）の normative 正本は
[tools/repo-contract/ci_portfolio_map.yml](../../tools/repo-contract/ci_portfolio_map.yml)
であり、本文書は同じ対応の human-readable mirror である。両者が食い違う場合は map file が
正である（`validate_ci_portfolio.py` が map↔workflow の edge 完全一致を検証する）。

## Portfolio model（Issue #422）

| 分類 | 対象 | どこで走るか |
|---|---|---|
| Permanent PR gate | `validate-repo-contract`、`check-style`、`build-debug-apk`、`organizer-unit-tests` | `validate-repo-contract` は全 run（docs-only 含む）。残り 3 つは `permanent_run`（source \|\| ci \|\| full \|\| smoke） |
| Conditional PR gate | 9 本の instrumentation lane | `instrumentation_enabled && (full \|\| own surface)`。surface は変更 diff から `changes` job が判定 |
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
| organizer-instrumentation-shared-writer-tests | surface_layout_write | coordinator / transaction / reload / restore-lease seam（#113/#117/#119/#120/#156） |
| organizer-instrumentation-db-migration-tests | surface_db_schema | schema upgrade/downgrade transaction ownership（#118/#115/#14、rollback32） |
| organizer-instrumentation-restore-capture-tests | surface_backup_restore | Nova restore → capture 契約（#299、cross-process 2 stage） |
| organizer-instrumentation-production-input-tests | surface_production_input, surface_layout_write | production input composer / 実 adapter 互換（#83、API 35）+ nested transaction の API 版依存回帰 |
| organizer-instrumentation-manual-organization-ui-tests | surface_organizer_ui | manual organization E2E / hub / strategy picker / exchange import success / diagnostics route（#52 系の広い UI sweep） |
| organizer-instrumentation-reservation-recovery-tests | surface_layout_write | QSB reservation / recovery store / overlap gate / #265/#269 の実 writer oracle（#155 系） |
| organizer-instrumentation-category-override-tests | surface_organizer_ui | category override authoring UI（#99） |
| organizer-instrumentation-exchange-import-ui-tests | surface_organizer_ui | exchange import surface UI（#332/#345） |
| organizer-instrumentation-onboarding-proposal-tests | surface_organizer_ui | onboarding proposal lifecycle / 実入力 environment（#53/#300） |

surface 定義（path filter）は `ci.yml` の `changes` job が所有する:

| Surface | 主な path | 備考 |
|---|---|---|
| surface_layout_write | `LayoutWriteCoordinator.java`、`ModelWriter.java`、`ModelDbController.java`、`organizer/application/**` + 該当 test 群 | apply / recovery / store seam。fan-out 先 2 lane + production-input lane |
| surface_db_schema | `provider/**`、`DatabaseHelper.java`、`GridSizeMigrationUtil.java`、`lawnchair/src/app/lawnchair/migration/**` + 該当 test 群（schema 4 test file は明示指定で self-trigger を保証。`com/.../organizer/` 直下は surface_layout_write の広い glob と重複するため） | |
| surface_backup_restore | `lawnchair/src/app/lawnchair/backup/**`、`LauncherBackupAgent.java` + 該当 test 群 | |
| surface_production_input | `organizer/integration/**` + 該当 test 群 | API 35 互換契約 |
| surface_organizer_ui | `organizer/ui/**`、`organizer/*`（root file 群）、`lawnchair/src/app/lawnchair/ui/**`、`organizer/personalization/**`、`lawnchair/res/**` + 該当 test 群 | v1 は UI 4 lane を同一 group とする（画面単位分割は後続） |
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
| organizer-unit-tests | organizer JVM contract / property / unit 一式。5.8 分。planner・rules・diagnostics・locks・personalization import・bugreport・backup unit の oracle。`surface_jvm` 領域の一次 gate | Permanent |
| shared-writer lane | 8.9 分。`MODEL_WRITER` deadlock・lease admission・Binder future・nested transaction・reload supersession・restore lease・Hotseat admission。JVM で再現不能な実 framework transaction / binder / loader threadaffinity に依存するため emulator 必須（#113〜#120 の実績）。isolated fixture DB で UI と状態分離。過去 failure: production regression として捉えた実績（compile-only escape #115 は db-migration 側） | Conditional（surface_layout_write） |
| db-migration lane | 9.1 分。schema upgrade/downgrade の失敗意味論・rollback32 binary。実 SQLite / platform 依存。#115 で「compile-only のまま実契約下で失敗し続けていた」実績があり emulator 実行が本質 | Conditional（surface_db_schema） |
| restore-capture lane | 9.5 分。Nova restore の cross-process 2 stage・widget window・unknown provider。実 backup 成形と process death が必須。class ごとに独立 `am instrument`（#299 手順書が正本） | Conditional（surface_backup_restore） |
| production-input lane | 9.3 分（API 35）。実 platform での production input composer 互換（#83）。API 36 で代替できない根拠は未取得（#96 からの繰越判断）。category override atomic file の restart writer/reader も実 filesystem 必須 | Conditional（surface_production_input + surface_layout_write） |
| manual-organization-ui lane | 8.7 分。manual organization の縦切り E2E（capture→plan→apply→recovery→UI）。DB heavy fixture を他 lane と共有しない。UI evidence 画像を常時 upload | Conditional（surface_organizer_ui） |
| reservation-recovery lane | 9.4 分。QSB 予約・recovery store lifecycle・overlap acceptance gate・#265/#269 の実 writer/recovery oracle。独立 storage を扱うため clean emulator 必須 | Conditional（surface_layout_write） |
| category-override lane | 8.1 分。authoring UI の semantics / focus / font-scale / touch target。Compose UI 検証は JVM で代替不能 | Conditional（surface_organizer_ui） |
| exchange-import-ui lane | 10.2 分。exchange import surface の Compose 検証（#345 で local-only から昇格。CI green の実績あり） | Conditional（surface_organizer_ui） |
| onboarding-proposal lane | 9.7 分。proposal lifecycle / Back / focus / recreation / review admission。実入力注入は focus 観測を前提とする（#300 accepted、#304/#418 で環境系 failure 実績） | Conditional（surface_organizer_ui） |
| failure-time evidence capture | 全 9 lane が `capture-emulator-failure-evidence.sh`（#315: bounded・continue-on-error）+ 14 日 artifact。失敗の原因分類を rerun 前に可能にする補助処理。validator が装備を機械検証 | 補助（各 lane） |
| final-status | 「当該 run に必要と判定された gate が完了したこと」を集約。skip は成功扱い、failure/cancelled のみ fail。needs の必須集合は validator が map file と突き合わせ | 集約（branch protection required check） |
| planner-stress.yml | 8 seed × 512 case の exploration matrix。週次 / manual のみで PR gate でない（#46 の時点から分類適合） | Scheduled / Diagnostic |
| high-risk-gate.yml | risk label / 高リスク path PR への独立 audit 記録検証。job ID 結合（`organizer-unit-tests` / `check-style` / `build-debug-apk` / `final-status`）は map file の `permanent_gates` 固定点として validator が保護 | Permanent（label/path 条件付き） |

### 監査で確認した未 routing test（follow-up 候補）

`tests/organizer-instrumentation/` には CI lane の class list に含まれない instrumentation
test が存在する（例: `application/store/*Inspection*`、`locks/*`、`diagnostics/export/*`、
`DeckRetirement*`、usage probe 系、`NovaRestoreGridApplicationTest` 等）。

重要な制約: 各 lane の Gradle invocation は明示的な class filter で実行するため、
**未 routing の class は full portfolio でも実行されない**。per-path fail-closed が保証
するのは「既存 9 lane の全起動」であり、未 routing test 自身の oracle ではない。した
がってこれらの test は現状 CI では一切実行されず、local / 手動実行のみの diagnostic
扱いである。恒久 lane への routing（または明示的な diagnostic 分類の記録）は後続 Issue
が所有する（本 Issue の Non-goal: test の移動・追加は行わない）。

なお `tests/organizer-instrumentation/com/android/launcher3/model/**`（GridMigration 系）
は `surface_db_schema` へ mapping されているが、db-migration lane はこれらを class list
に含まない。この mapping は「変更時に schema 契約 lane を起動する」over-trigger であり、
当該 class 自身の実行を意味しない（上記の未 routing 制約と同じ）。

## intermittent failure の分類と evidence・retry 方針

正本は [quality-strategy.md](./quality-strategy.md) の該当 section。要約:

- 失敗は (1) product regression、(2) deterministic test defect、(3) test synchronization /
  test harness defect、(4) CI wrapper / artifact handling defect、(5) emulator / runner /
  platform environment defect、(6) unknown のいずれかに分類してから再実行する。
- rerun で green になっても、分類と（失敗時）capture 証拠なしに merge evidence としない。
- 一時的 failure = production 無関係とは扱わない。#304/#418 の環境系 signature は調査
  Issue が所有し、merge gate からの無条件除外は行わない。
- Issue / PR の acceptance evidence は「full workflow N 連続 green」を機械的に要求せず、
  変更 risk と対象 surface に対応して選択する（[github-workflow.md](../project/github-workflow.md)
  の evidence 選択原則）。

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

<!-- 422 demo: docs-only surface verification -->
