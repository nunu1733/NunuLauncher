---
issue: "#155"
status: accepted
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

`FullOrganization` の production input は、実際の Launcher と同じ **first-workspace-page authority** および QSB snapshot から導出した予約済み workspace region を、**配置アイテムではない occupancy constraint** として planner に渡す。planner は予約領域を保存済み配置と同様に allocation と overlap validation の初期占有として扱い、予約領域を含む target を生成しない。適用後の相関再読込と A7 の独立 recapture は intended manifest を維持して A8 へ進み、default workspace における #150 の AC-150-04 を unblock する。[1] [2]

`OrganizationPlanner.plan(OrganizationInput)`、`LayoutApplicationModule.apply(ValidatedLayoutPlan)`、結果の closed algebra、および Loader の正常な sanitation policy は変更しない。予約制約を読めない・不正にしか表現できない場合は、planner を呼ばず、checkpoint、Launcher DB write、recovery point、reload を開始しない typed non-write outcome とする。

## Scope

本仕様は、manual full organization の canonical capture/composition、pure planner の occupancy initialization、plan materialization、application revision/precondition、A7 verification、recovery format compatibility、ならびに必要な unit・instrumentation evidence を対象とする。QSB reservation は、`FeatureFlags.topQsbOnFirstScreenEnabled(context)` が true の場合に限り、Launcher の `Workspace.FIRST_SCREEN_ID` を `PageId` へ変換した page の `(0,0)` から幅 `InvariantDeviceProfile.numSearchContainerColumns`、高さ `1` の矩形として導出する。organizer 独自の `PageId("0")` literal を source of truth にしてはならない。[2] [4]

| 対象 | 必須の振る舞い |
|---|---|
| canonical page authority | `BgDataModel.collectWorkspaceScreens()` と同じ規則を使う。desktop row の screen 集合を基礎とし、**QSB が有効、または集合が空**なら `Workspace.FIRST_SCREEN_ID` をちょうど一度含める。QSB が無効かつ desktop row がある場合は first page を合成しない。[4] |
| 予約領域の導出 | page authority、feature condition、columns、`numSearchContainerColumns` を同一 capture attempt で一回だけ snapshot する。QSB 無効時は region を空集合にする。page identity は `Workspace.FIRST_SCREEN_ID` から変換する。 |
| planner 入力 | reservation は `CapturedItem`、`TargetSet`、分類 signal、または DB row へ偽装せず、canonical かつ duplicate-free な workspace occupancy constraint として入力へ含める。rowless logical first page は正常な planner input である。 |
| planner | reserved rectangle を初期 occupancy として mark し、movable item・既存 folder unit・new folder のいずれも overlap させない。new page は reservation の対象外である。planner は Android/DB/I/O を読まない。 |
| application | normalized page authority と reservation snapshot を capture revision、exact precondition、post-apply verification の context resource に含める。capture 後に QSB の enabled state、search span、または normalized page inventory が変われば stale/non-write とする。A7 は同じ context を持つ DB/model convergence を確認する。 |
| recovery compatibility | reservation context の永続表現を追加するため recovery format を v2 とし、旧 v1 recovery DB は active/verified point を安全に再解釈せず fail closed する。詳細は ADR-0008 の承認を前提とする。[5] |
| user-visible behavior | 通常の成功表示、preview、confirmation、result、recovery UX は変更しない。reservation conflict、legacy recovery format、または capture failure は既存の non-write/retry/support surface で説明し、座標・profile・row ID を露出しない。 |

## Non-goals

本仕様は QSB/Smartspace の UI、QSB preference の意味、search-container の描画、`LoaderCursor` の一般的な overlap sanitation、通常 reload の削除規則、grid migration、folder policy、target membership policy、権限、通信、telemetry を変更しない。既存の不正 row、失われた profile、widget failure、dangling folder/app-pair の sanitizer を organizer reload だけ特別扱いすることも対象外である。

この修正は retry-until-match、A7 比較の緩和、`favorites` の全削除・再挿入、手動 backup を undo と見なすこと、または `LoaderCursor` の overlap deletion を無条件に無効化することを許可しない。[1] [6]

## Domain language

新しいユーザー向け用語は追加しない。ここでいう **予約済み workspace region** は、DB row ではなく、platform が first screen 上で占有するセル矩形を表す planner input の技術的な constraint である。**logical first page** は item row がなくても current Launcher が有効な workspace page と扱う first-screen authority を指し、ユーザー向け語ではない。したがって `CONTEXT.md` は更新しない。

## Behavior scenarios

### Scenario: QSB有効のdefault workspaceを全体整理する

**Given** QSB が有効で、`Workspace.FIRST_SCREEN_ID` に有効な予約矩形があり、Google folder を含む canonical layout capture と policy inputs が安定している。
**When** ユーザーが manual full organization を確認する。
**Then** planner は予約矩形を初期占有として扱い、Google folder を含む全 workspace unit の target は予約セルと重ならない。
**And** A6 commit 後の相関 reload は organizer が意図した folder row を `LoaderCursor` の overlap として削除せず、A7 recapture は intended manifest と一致する。
**And** A8 の既存不変条件、transaction/recovery、truthful result はそのまま適用される。[1] [2] [6]

### Scenario: QSB有効かつdesktop rowがない

**Given** `favorites` に DESKTOP row がなく、QSB が有効である。
**When** canonical capture が page inventory と reservation context を構成する。
**Then** page authority は `Workspace.FIRST_SCREEN_ID` を一度だけ logical first page として含め、同 page の QSB reservation を planner input に含める。
**And** composer は rowless first page を unknown page、`NotReady`、synthetic item、または synthetic DB row として扱わない。[4]

### Scenario: QSB無効かつdesktop rowがない

**Given** `favorites` に DESKTOP row がなく、QSB が無効である。
**When** canonical capture が page inventory を構成する。
**Then** page authority は `Workspace.FIRST_SCREEN_ID` を logical first page として含めるが、reservation list は空である。
**And** empty manual run は既存どおり `NoChanges` となり、checkpoint、Launcher/recovery DB write、reload を行わない。[4] [7]

### Scenario: QSBが無効でdesktop rowがある

**Given** row-derived canonical page inventory が空でなく、QSB feature condition が false である。
**When** composer が `FullOrganization` input を構成する。
**Then** row-derived page inventory に first page を追加せず、reserved region は空集合である。
**And**既存 planner fixture と同じ page authority/rule で value-equivalent input/result を得る。

### Scenario: reservationが既存rowと衝突する、または不正である

**Given** enabled QSB の reservation geometry が bounds 外、幅ゼロ、columns を超過、または同一 capture の既存 workspace item と重なる。
**When** composer が input を構成する。
**Then** typed `NotReady(InvalidCanonicalCapture)` を返す。
**And** planner、checkpoint、apply、recovery point、model reload、Launcher DB mutation はすべて 0 回である。
**And** composer は row を削除、移動、保存済み item への変換、または reservation の黙殺を行わない。[7] [8]

### Scenario: capture後にQSB条件またはpage authorityが変わる

**Given** preview に使った input が、その capture attempt で一回だけ取得した normalized page authority、QSB enabled state、search span を含む revision へ束縛されている。
**When** confirm 前または A5 内の再読取時に enabled → disabled、disabled → enabled、`numSearchContainerColumns` の変更、または first-page normalization の変化が起きる。
**Then** application seam は `STALE_REVISION` または exact-precondition rejection として no-write で終了し、古い plan を再利用しない。
**And** UI は既存の recapture/replan flow を提示する。[6]

### Scenario: v1 recovery DBを持つ端末がv2へ更新される

**Given** prior binary が作成した recovery format v1 database に `CREATING`、`READY`、`APPLYING`、`COMMITTED_UNVERIFIED`、`RESTORING`、または `VERIFIED` point がある。
**When** v2 binary が起動する。
**Then** format gate は legacy record を v2 reservation context で推測・再hash・rewrite せず、`INCOMPATIBLE_VERSION` として fail closed する。
**And** Launcher DB、旧 recovery DB、active/pending point は変更されず、apply/recovery は開始されない。
**And** user-facing surface は false restored/success を表示せず、既存の safe diagnostic/support path を提供する。[5] [6]

### Scenario: 空のv1 recovery DBをv2へ更新する

**Given** recovery format v1 database に restorable、verified、または non-final point がなく、tombstone retention も完了している。
**When** v2 binary が startup migration を実行する。
**Then** migration は recovery DB 内だけで atomic に v2 schema を作成し、Launcher DB を読み書きしない。
**And** migration失敗時は旧 DB と Launcher layout を残し、store を unavailable/incompatible として fail closed する。[5]

## Data and state

Launcher DB は現在のホームレイアウトの正本のままであり、reservation 自体を `favorites`、policy bundle、diagnostics payload、backup に DB row として永続化しない。reservation context は canonical capture の preserve-only resource として recovery record v2 に保存し、normalized page authority、QSB enabled state、search span、page/cell/span の canonical list を含める。これらは revision、A2/A5 precondition、post-apply verification の比較対象であり、`ApplyAction` や Launcher DB SQL の対象ではない。[5] [6]

1回の capture attempt は、最初に current Launcher authority から normalized page inventory と QSB condition/geometry を一回だけ読み、その同じ immutable snapshot を `LayoutState`、`PersistenceManifest` context resource、planner `LayoutSnapshot`、`InputProvenance`、revision/digest の全てへ使用する。DB rows を読む前後に flag/IDP を読み直して mixed capture を作ってはならない。A2/A5/A7 の再captureも同じ規則で新しい snapshot を作り、完全一致でなければ stale または verification failure とする。

page inventory は、`Workspace.FIRST_SCREEN_ID` から変換した logical first page を `PageId` とし、literal `"0"` を独自定義しない。row-derived page list と logical first page の正規化は duplicate-free であり、PageOrder は同じ launcher page authority の canonical orderに従う。QSB enabled/disabled transition により page set が変化する場合も、page authority と reservation context の両方が revision を変える。

recovery format は v2 を導入する。v2 は reservation context を明示的に encode し、legacy v1 record の `preRevision`、digest、manifest を v2 meaning に再解釈しない。upgrade path は ADR-0008 の選択に従う。すなわち、active、verified、または restorable v1 record が一つでもあれば migration をしないで `INCOMPATIBLE_VERSION` とする。empty/retention-complete v1 store のみが recovery-DB-only atomic migration を行える。downgrade では v1 binary は v2 recovery DB を read-only/incompatible とし、Launcher layout を変更しない。[5]

Launcher schema、rule/taxonomy version、permission、backup format の migration はない。v2 recovery migration は recovery DB のみを対象とする。apply failure 時は既存の atomic transaction と recovery protocol を用い、revert/downgrade は reservation-aware code を外すだけで既存 `favorites` row を変更しない。

## Permissions, privacy, and security

**None。** QSB flag、first-screen identity、grid capability は端末内の既存 Launcher authority から読み取る。外部送信、network、telemetry、追加 permission、raw package/profile/coordinate の user-facing 表示は導入しない。診断は既存の opaque run ID、closed result code、count-only projection に留める。[1] [6]

## Accessibility and localization

成功時の preview、confirmation、progress、result、recovery UI を変更しない。reservation context が不正で composition が `NotReady` になる場合、または legacy recovery store が incompatible である場合は、#52 の既存 safe non-write/error mapping を使い、TalkBack 名称、focus restoration、retry/exit/support action、200% font scale で到達可能な説明を提供する。QSB、row ID、セル座標、raw exception を user-facing string の根拠にしない。[7]

## Acceptance criteria

- [ ] **QSB-AC-01 — canonical page and reservation composition:** production composer は `Workspace.FIRST_SCREEN_ID` と `BgDataModel.collectWorkspaceScreens` 相当の page authority を用い、QSB enabled/disabled、rowless/row-backed workspace の規則に従って logical first page と canonical reservation を一回の capture で構成する。synthetic item/target/DB row を生成しない。
- [ ] **QSB-AC-02 — planner safety and determinism:** planner は予約矩形を preserved occupancy と同じ初期制約として扱い、予約と重ならない deterministic plan を返す。予約領域が item と重複・範囲外・表現不能なら typed non-write rejection であり、予約外の既存 fixture behavior は不変である。
- [ ] **QSB-AC-03 — capture binding and stale safety:** normalized page authority、QSB enabled state、search span、reservation list は one-shot capture snapshot から revision/precondition/verification resource へ lossless に伝播する。enabled ↔ disabled、span change、page normalization change は A2/A5 stale/no-write となる。
- [ ] **QSB-AC-04 — materialization and persistence:** materializer は reservation を action set/`favorites` row に変換しない。v2 recovery record は reservation context を明示的に保存し、source/intended context を変更・drop できない。
- [ ] **QSB-AC-05 — legacy recovery compatibility:** active/verified/restorable v1 recovery record を持つ upgrade は `INCOMPATIBLE_VERSION` で fail closed し、Launcher DB と旧 recovery DB を変更しない。empty/retention-complete store のみ v2 migration が成功し、upgrade/downgrade instrumentation がこれを証明する。
- [ ] **QSB-AC-06 — failure-path regression:** 既存 seam で「reserved cell を target にした場合、correlated reload が row を削除し A7 recapture が mismatch になる」現行失敗を red test として再現し、修正後は同じ default workspace が A7 を通過することを証明する。
- [ ] **QSB-AC-07 — on-device end-to-end safety:** API 36.1、4×5 default workspace、enabled QSB で manual full organization を実行し、planner target が予約外であること、folder row が commit/A7 後に残ること、A8 `Applied`、recovery point、DB/model invariants が成立することを確認する。QSB 無効、rowless first-screen の対照ケースも実行する。
- [ ] **QSB-AC-08 — no bridge regression:** organizer-correlated reload は既存の loader token/lease behavior を維持し、`LoaderCursor` の一般 sanitation を緩和しない。normal reload と既存 sanitizer regression が継続して通る。
- [ ] **QSB-AC-09 — evidence and gate:** focused unit/contract/instrumentation、`spotlessCheck`、repository-contract、organizer JVM test、debug build、対象 connected test、CI `final-status`、独立 audit record が exact head SHA で成功し、high-risk gate を満たす。[9] [10]

## Test oracle

| AC | Evidence |
|---|---|
| QSB-AC-01 | `ProductionOrganizationInputInstrumentationTest` と composer unit tests。enabled/disabled、rowless/row-backed page authority、first-screen ID、search span、no synthetic inventory を検証する。 |
| QSB-AC-02 | planner public-seam fixture と property/permutation tests。4×5 first-screen reservation、multi-span item、existing preserved occupancy、rowless logical first screen を対象に deterministic non-overlap を検証する。 |
| QSB-AC-03 | canonical marshalling/revision、`OrganizationPlanMaterializer`、application public-seam tests。QSB enabled flip、span drift、page normalization drift で A2/A5 stale/no-write、A7 recapture equality を検証する。 |
| QSB-AC-04 | source/intended resource equality と no-action assertions。v2 record encode/decode/read-back checksum が reservation context を保持し、recovery write-set が preserve-only であることを検証する。 |
| QSB-AC-05 | v1 fixture DB の lifecycle matrix (`CREATING`、`READY`、`APPLYING`、`COMMITTED_UNVERIFIED`、`RESTORING`、`VERIFIED`) と empty store upgrade、v2→v1 downgrade の instrumentation。Launcher/recovery DB fingerprint が no-mutation であることを検証する。 |
| QSB-AC-06 | red-first regression fixture で reservation を除いた現行 input が `(0,0)` を選ぶこと、修正 input が選ばないこと、かつ correlated reload の row-count/manifest mismatch を確認する。 |
| QSB-AC-07 | clean API 36.1 emulator の `ManualOrganizationProductionE2EInstrumentationTest` または専用 focused test。real Launcher model、DB poll、A7/A8 terminal result、recovery evidence を確認する。 |
| QSB-AC-08 | `SanitizerInstrumentationTest` と Loader/organizer reload regression。ordinary loader sanitation は残り、QSB予約外の organizer target は削除されないことを確認する。 |
| QSB-AC-09 | `./gradlew spotlessCheck`、`./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`、`./gradlew assembleLawnWithQuickstepGithubDebug`、repository-contract、CI/audit を PR に記録する。[9] [10] |

## Open questions

実装開始前に残る product decision はない。Issue #155 の policy seam は planner input composition であり、Loader bridge を緩和しない。rowless workspace の page authority と recovery format migration/fail-closed policy は ADR-0008 を `accepted` にしてから source implementation を開始する。これは public operation/result contract の変更ではないが、canonical input/revision/recovery representation の高コストな設計判断である。[1] [5] [6]

## References

[1]: https://github.com/nunu1733/NunuLauncher/issues/155 "Issue #155 — LoaderCursor deletes organizer-placed folder overlapping the QSB reservation"
[2]: https://github.com/nunu1733/NunuLauncher/blob/main/src/com/android/launcher3/model/LoaderCursor.java "LoaderCursor — QSB reservation and overlap deletion"
[3]: https://github.com/nunu1733/NunuLauncher/blob/main/src/com/android/launcher3/model/LoaderTask.java "LoaderTask — workspace load and commitDeleted ordering"
[4]: https://github.com/nunu1733/NunuLauncher/blob/main/src/com/android/launcher3/model/BgDataModel.java "BgDataModel — first workspace screen authority"
[5]: ../../docs/adr/0008-qsb-reservation-context-and-recovery-compatibility.md "ADR-0008 — QSB reservation context and recovery compatibility"
[6]: ../13-safe-layout-application/spec.md "Spec #13 — Safe layout application and recovery"
[7]: ../52-manual-full-organization-vertical-slice/spec.md "Spec #52 — Manual full-organization vertical slice"
[8]: ../../docs/product/item-preservation-policy.md "Item preservation policy — capture boundary and baseline cleanup"
[9]: ../../AGENTS.md "Repository rules — layout safety, tests, and high-risk evidence"
[10]: ../../docs/engineering/quality-strategy.md "Quality strategy — connected tests and independent-evidence gate"

## Change history

- 2026-08-27: Drafted for Issue #155 from the issue evidence, current `main` at `7ba2194ce7`, and the accepted planner/application contracts.
- 2026-08-27: Addressed review comment `#issuecomment-5432708854`: replaced the literal first-page assumption with Launcher page authority; defined rowless page normalization and one-shot capture; added v2 recovery-format compatibility, fail-closed legacy handling, and upgrade/downgrade evidence requirements.
