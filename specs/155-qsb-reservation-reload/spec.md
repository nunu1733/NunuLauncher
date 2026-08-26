---
issue: "#155"
status: draft
requirements:
  - FR-002
  - FR-004
  - FR-005
  - FR-006
  - NFR-002
  - NFR-005
  - NFR-007
  - NFR-011
risk:
  - layout-data
updated: 2026-08-27
---

# QSB予約領域を考慮した全体整理と相関再読込の整合

## Problem

手動全体整理の既定ワークスペースでは、planner が first screen の `(0,0)` を空きセルとして選べる一方、Launcher の `LoaderCursor` は Smartspace/QSB が有効な場合に first screen の先頭 `numSearchContainerColumns × 1` セルを占有済みとして扱う。この不一致により、A6 で Google folder を `(0,0)` に移した直後の相関再読込で Loader が当該 row を重複と判定して `markDeleted` し、`commitDeleted()` が organizer transaction 外で `favorites` を削除する。A7 の DB 再取得は intended manifest と一致せず、既存の自動 recovery は pre-state を戻せても、正常な検証済み適用には到達できない。[1] [2] [3]

この削除は planner が許可した明示的な `Delete` ではなく、また相関再読込で後段 sanitizer を抑止しても解消しない。`LoaderCursor.checkAndAddItem` の重複判定と `commitDeleted()` は sanitizer より前の workspace load 本体で実行されるためである。[2] [3]

## Outcome

`FullOrganization` の production input は、実際の Launcher と同じ条件で導出した QSB の予約済み workspace region を、**配置アイテムではない occupancy constraint** として planner に渡す。planner は予約領域を保存済み配置と同様に allocation と overlap validation の初期占有として扱い、予約領域を含む target を生成しない。適用後の相関再読込と A7 の独立 recapture は intended manifest を維持して A8 へ進み、default workspace における #150 の AC-150-04 を unblock する。[1] [2]

`OrganizationPlanner.plan(OrganizationInput)`、`LayoutApplicationModule.apply(ValidatedLayoutPlan)`、結果の closed algebra、および Loader の正常な sanitation policy は変更しない。予約制約を読めない・不正にしか表現できない場合は、planner を呼ばず、checkpoint、Launcher DB write、recovery point、reload を開始しない typed non-write outcome とする。

## Scope

本仕様は、manual full organization の canonical capture/composition、pure planner の occupancy initialization、plan materialization、application revision/precondition、A7 verification、ならびに必要な unit・instrumentation evidence を対象とする。予約条件は `FeatureFlags.topQsbOnFirstScreenEnabled(context)` が true の場合に限り、`Workspace.FIRST_SCREEN_ID` の `(0,0)` から幅 `InvariantDeviceProfile.numSearchContainerColumns`、高さ `1` の矩形として導出する。[2]

| 対象 | 必須の振る舞い |
|---|---|
| 予約領域の導出 | feature flag、first-screen ID、columns、`numSearchContainerColumns` を同一 Launcher authority から一回の capture context として読み、QSB 無効時は空集合にする。 |
| planner 入力 | 予約は `CapturedItem`、`TargetSet`、分類 signal、または DB row へ偽装せず、canonical かつ duplicate-free な workspace occupancy constraint として入力へ含める。 |
| planner | reserved rectangle を初期 occupancy として mark し、movable item・既存 folder unit・new folder・new page allocation のいずれも overlap させない。planner は Android/DB/I/O を読まない。 |
| application | reservation identity を capture revision と exact precondition に含め、capture 後に QSB の有効状態または span が変われば stale/non-write とする。A7 は同じ reservation context を持つ DB/model convergence を確認する。 |
| user-visible behavior | 通常の成功表示、preview、confirmation、result、recovery UX は変更しない。reservation conflict または capture failure は既存の non-write/retry surface で説明し、座標・profile・row ID を露出しない。 |

## Non-goals

本仕様は QSB/Smartspace の UI、QSB preference の意味、search-container の描画、`LoaderCursor` の一般的な overlap sanitation、通常 reload の削除規則、grid migration、folder policy、target membership policy、DB schema、recovery format、権限、通信、telemetry を変更しない。既存の不正 row、失われた profile、widget failure、dangling folder/app-pair の sanitizer を organizer reload だけ特別扱いすることも対象外である。

この修正は retry-until-match、A7 比較の緩和、`favorites` の全削除・再挿入、手動 backup を undo と見なすこと、または `LoaderCursor` の overlap deletion を無条件に無効化することを許可しない。[1] [4]

## Domain language

新しいユーザー向け用語は追加しない。ここでいう **予約済み workspace region** は、DB row ではなく、platform が first screen 上で占有するセル矩形を表す planner input の技術的な constraint である。したがって `CONTEXT.md` は更新しない。

## Behavior scenarios

### Scenario: QSB有効のdefault workspaceを全体整理する

**Given** QSB が有効で、first screen に有効な予約矩形があり、Google folder を含む canonical layout capture と policy inputs が安定している。  
**When** ユーザーが manual full organization を確認する。  
**Then** planner は予約矩形を初期占有として扱い、Google folder を含む全 workspace unit の target は予約セルと重ならない。  
**And** A6 commit 後の相関 reload は organizer が意図した folder row を `LoaderCursor` の overlap として削除せず、A7 recapture は intended manifest と一致する。  
**And** A8 の既存不変条件、transaction/recovery、truthful result はそのまま適用される。[1] [2] [4]

### Scenario: QSBが無効である

**Given** 同一の device/grid/layout で QSB feature condition が false である。  
**When** composer が `FullOrganization` input を構成する。  
**Then** reserved region は空集合であり、既存の planner fixture と同じ値等価 input/result を得る。  
**And** QSB 固有の item、DB row、target membership、diagnostic は追加されない。

### Scenario: reservationが既存rowと衝突する、または不正である

**Given** enabled QSB の予約矩形が bounds 外、幅ゼロ、first-screen context 不明、canonical に重複、または capture 内の既存 workspace item と重なる。  
**When** composer が input を構成する。  
**Then** typed `NotReady(InvalidCanonicalCapture)` を返す。  
**And** planner、checkpoint、apply、recovery point、model reload、Launcher DB mutation はすべて 0 回である。  
**And** composer は row を削除、移動、保存済み item への変換、または予約の黙殺を行わない。[5] [6]

### Scenario: capture後にQSB条件が変わる

**Given** preview に使った input が QSB 有効状態または予約 span を含む revision へ束縛されている。  
**When** confirm 前または A5 内の再読取時に当該 reservation context が変化する。  
**Then** application seam は `STALE_REVISION` または exact-precondition rejection として no-write で終了し、古い plan を再利用しない。  
**And** UI は既存の recapture/replan flow を提示する。[4]

### Scenario: QSB予約を含む既定layoutを相関再読込する

**Given** organizer lease を伴う A7 reload と、first-screen QSB reservation 外に配置された有効な folder row がある。  
**When** reload が完了する。  
**Then** `LoaderCursor` はその folder を overlap として mark/delete せず、DB row count と対象 row は commit 後と A7 recapture 後で同一である。  
**And** malformed row 等に対する通常の Loader safety policy を本修正の成功根拠にしてはならない。[2] [3]

## Data and state

Launcher DB は現在のホームレイアウトの正本のままであり、reservation 自体を `favorites`、recovery DB、policy bundle、diagnostics payload、backup に永続化しない。reservation は canonical capture の device/profile/page context から再導出する preserve-only context resource とし、canonical encoding に含めて revision、precondition、post-apply verification の比較対象にする。これにより QSB condition が capture と apply の間で変わった場合、既存の full revision semantics に従って stale となる。[4]

planner input の拡張は、`LayoutSnapshot.items` が **captured existing layout only** である既存契約を守る。したがって synthetic QSB widget/folder/shortcut、synthetic `ItemId`、synthetic target membership を導入してはならない。予約を表す値は page identity、`GridCell`、`GridSpan` を持ち、同一 page/rectangle の重複を許さず、bounds 内であること、既存 captured item と overlap しないことを検証する。`PageId("0")` が row を持たない empty workspace の場合も、予約対象と planned/persistent page mapping が矛盾せず、materialized intended state と A7 recapture が一致することをテストで証明する。

DB schema、recovery format、rule/taxonomy version、permission、backup format の migration はない。apply failure 時は既存の atomic transaction と recovery protocol を用い、revert/downgrade は reservation-aware code を外すだけで既存 `favorites` row または recovery point を変更しない。

## Permissions, privacy, and security

**None。** QSB flag と grid capability は端末内の既存 Launcher authority から読み取る。外部送信、network、telemetry、追加 permission、raw package/profile/coordinate の user-facing 表示は導入しない。診断は既存の opaque run ID、closed result code、count-only projection に留める。[1] [4]

## Accessibility and localization

成功時の preview、confirmation、progress、result、recovery UI を変更しない。reservation context が不正で composition が `NotReady` になる場合は、#52 の既存 safe non-write/error mapping を使い、TalkBack 名称、focus restoration、retry/exit action、200% font scale で到達可能な説明を提供する。QSB、row ID、セル座標、raw exception を user-facing string の根拠にしない。[5]

## Acceptance criteria

- [ ] **QSB-AC-01 — production reservation composition:** QSB enabled の first screen について、production composer は Launcher と同じ feature condition と grid authority から canonical reservation を導出する。QSB disabled では空集合であり、synthetic item/target/DB row を生成しない。
- [ ] **QSB-AC-02 — planner safety and determinism:** planner は予約矩形を preserved occupancy と同じ初期制約として扱い、予約と重ならない deterministic plan を返す。予約領域が item と重複・範囲外・表現不能なら typed non-write rejection であり、予約外の既存 fixture behavior は不変である。
- [ ] **QSB-AC-03 — materialization and revision binding:** materializer は reservation を action set/`favorites` row に変換せず、適用前 revision/precondition と post-apply verification は reservation identity を含む。QSB condition/span の capture 後変化は stale/no-write となる。
- [ ] **QSB-AC-04 — failure-path regression:** 既存 seam で「reserved cell を target にした場合、correlated reload が row を削除し A7 recapture が mismatch になる」現行失敗を red test として再現し、修正後は同じ default workspace が A7 を通過することを証明する。
- [ ] **QSB-AC-05 — on-device end-to-end safety:** API 36.1、4×5 default workspace、enabled QSB で manual full organization を実行し、planner target が予約外であること、folder row が commit/A7 後に残ること、A8 `Applied`、recovery point、DB/model invariants が成立することを確認する。QSB 無効の対照ケースも実行する。
- [ ] **QSB-AC-06 — no bridge regression:** organizer-correlated reload は既存の loader token/lease behavior を維持し、`LoaderCursor` の一般 sanitation を緩和しない。normal reload と既存 sanitizer regression が継続して通る。
- [ ] **QSB-AC-07 — evidence and gate:** focused unit/contract/instrumentation、`spotlessCheck`、repository-contract、organizer JVM test、debug build、対象 connected test、CI `final-status`、独立 audit record が exact head SHA で成功し、high-risk gate を満たす。[7] [8]

## Test oracle

| AC | Evidence |
|---|---|
| QSB-AC-01 | `ProductionOrganizationInputInstrumentationTest` と composer unit tests。enabled/disabled、first-screen ID、search span、invalid context、no synthetic inventory を検証する。 |
| QSB-AC-02 | planner public-seam fixture と property/permutation tests。4×5 first-screen reservation、multi-span item、existing preserved occupancy、empty/virtual first-screen を対象に deterministic non-overlap を検証する。 |
| QSB-AC-03 | canonical marshalling/revision、`OrganizationPlanMaterializer`、application public-seam tests。QSB toggle/span change で A2/A5 stale/no-write、A7 recapture equality を検証する。 |
| QSB-AC-04 | red-first regression fixture で reservation を除いた現行 input が `(0,0)` を選ぶこと、修正 input が選ばないこと、かつ correlated reload の row-count/manifest mismatch を確認する。 |
| QSB-AC-05 | clean API 36.1 emulator の `ManualOrganizationProductionE2EInstrumentationTest` または専用 focused test。real Launcher model、DB poll、A7/A8 terminal result、recovery evidence を確認する。 |
| QSB-AC-06 | `SanitizerInstrumentationTest` と Loader/organizer reload regression。ordinary loader sanitation は残り、QSB予約外の organizer target は削除されないことを確認する。 |
| QSB-AC-07 | `./gradlew spotlessCheck`、`./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`、`./gradlew assembleLawnWithQuickstepGithubDebug`、repository-contract、CI/audit を PR に記録する。[7] [8] |

## Open questions

実装開始前に残る product decision はない。Issue #155 の preferred policy seam は planner input composition であり、Loader bridge を緩和しない。実装時は、`LayoutSnapshot.items` に row 以外を混入させずに reservation を表すため、Issue #10 の input model と Issue #13 の revision resource をこの仕様に整合させる。これは public operation/result contract の変更ではないが、canonical input shape の source-compatible extensionであるため、当該正本の更新と reviewer acceptance を同一 PR で必須とする。[1] [4] [6]

## References

[1]: https://github.com/nunu1733/NunuLauncher/issues/155 "Issue #155 — LoaderCursor deletes organizer-placed folder overlapping the QSB reservation"
[2]: https://github.com/nunu1733/NunuLauncher/blob/main/src/com/android/launcher3/model/LoaderCursor.java "LoaderCursor — QSB reservation and overlap deletion"
[3]: https://github.com/nunu1733/NunuLauncher/blob/main/src/com/android/launcher3/model/LoaderTask.java "LoaderTask — workspace load and commitDeleted ordering"
[4]: ../13-safe-layout-application/spec.md "Spec #13 — Safe layout application and recovery"
[5]: ../52-manual-full-organization-vertical-slice/spec.md "Spec #52 — Manual full-organization vertical slice"
[6]: ../10-pure-organization-planning/spec.md "Spec #10 — Pure organization planning interface"
[7]: ../../AGENTS.md "Repository rules — layout safety, tests, and high-risk evidence"
[8]: ../../docs/engineering/quality-strategy.md "Quality strategy — connected tests and independent-evidence gate"

## Change history

- 2026-08-27: Drafted for Issue #155 from the issue evidence, current `main` at `7ba2194ce7`, and the accepted planner/application contracts.
