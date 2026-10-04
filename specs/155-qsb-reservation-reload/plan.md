> Issue: #155
> Spec: [spec.md](./spec.md)
> Status: accepted
> Baseline: `main` / `7ba2194ce7`

## Current evidence

Issue #155 の device evidence は、manual organization plan が Google folder を first-screen `(0,0)` へ移動し、A6 commit 後の相関 reload がその row を削除する因果列を示している。これは A7 completion timing ではなく、planner occupancy と Loader occupancy の policy mismatch である。[1]

`LoaderCursor.checkItemPlacement` は first screen の occupancy を初期化するとき、`topQsbOnFirstScreenEnabled` が true であれば `(0,0)` から `numSearchContainerColumns × 1` を occupied として mark する。後続 item がその矩形と重なる場合、`checkAndAddItem` は `markDeleted("Item position overlap")` を実行する。[2] `LoaderTask.loadWorkspaceImpl` は loader 本体の完了後に `c.commitDeleted()` を呼ぶため、削除は後段 sanitizer より前に確定する。[3]

page authority は DB row のみに限定されない。`BgDataModel.collectWorkspaceScreens()` は desktop item の screen 集合に加え、QSB-on-first-screen が有効な場合**または**その集合が空の場合、`Workspace.FIRST_SCREEN_ID` を必ず含める。したがって rowless first workspace は valid runtime page であり、organizer の `PageId("0")` literal や row-derived inventory だけで否定してはならない。[4]

既存 recovery record は `preRevision`、pre/intended digest、manifest を versioned private DB に保存する。record/DB format は現時点で v1 だけを受け付け、version gate は未知 format を `INCOMPATIBLE_VERSION` として Launcher DB に触れず fail closed する。reservation context を canonical resource として永続表現へ追加するなら、format compatibility を設計上明示しなければならない。[5] [6] [7]

| 確認済みの事実 | 根拠 | 実装上の含意 |
|---|---|---|
| QSB reservation は first screen の上端 1 row、幅は `numSearchContainerColumns` | `LoaderCursor` | 同一 Launcher authority から PageId/cell/span を導出する。 |
| reload 中の overlap deletion は `commitDeleted()` で DB に反映される | `LoaderTask` | A7 の前に row が失われるため、verification retry では修正できない。 |
| rowless または QSB-enabled workspace は `FIRST_SCREEN_ID` を有効 page とする | `BgDataModel.collectWorkspaceScreens()` | page normalization は capture adapter が行い、rowless first page を reject しない。 |
| composer は canonical application capture を唯一の layout source とする | Spec #83 / `OrganizationInputComposer` | UI/local DB read を追加せず、capture adapter へ reservation context を集約する。 |
| application seam は device capability を revision と exact precondition に含める | Spec #13 / `RevisionCalculator` | QSB snapshot と normalized page authority も same capture identity に束縛する。 |
| recovery record/DB format v1 は不一致 version を fail closed する | `RecoveryRecordCodec` / `RecoveryDbVersionGate` | v2 migration、legacy rejection、upgrade/downgrade test を実装前に明文化する。 |
| planner input の `items` は captured existing layout のみ | Spec #10 | QSB を synthetic item/target/action にしてはならない。 |

## Design

### Modules and interfaces

**選択する policy seam は planner input composition である。** `OrganizationPlanner.plan(input)` と `LayoutApplicationModule.apply(plan)` の operation/result interface は維持する。その代わり、canonical layout context と planner `LayoutSnapshot` に opaque value `ReservedWorkspaceRegion(page, cell, span)` の canonical list を追加する。この値は layout item、target、placement outcome、DB row ではない。Kotlin constructor には empty-list default を置き、既存 pure-fixture call site を source-compatible に保つ。ただし public input shape の拡張であるため、Spec #10/#12/#13 を同一 PR で更新する。

application capture は `LauncherLayoutAdapter` の既存 `Context` と `InvariantDeviceProfile` を用いる。1回の capture attempt の最初に、`Workspace.FIRST_SCREEN_ID`、`topQsbOnFirstScreenEnabled(appContext)`、`numSearchContainerColumns`、IDP columns/rows を一回だけ immutable `WorkspaceReservationContext` へ snapshot する。capture の残りの過程および `LayoutState`、planner snapshot、manifest context resource、`InputProvenance`、revision/digest はすべてこの同じ object から得る。A2/A5/A7 では別の新規 capture attempt を同じ手順で作り、値が異なれば stale/mismatch とする。

`LauncherWorkspacePageAuthority` は private adapter helper として、`BgDataModel.collectWorkspaceScreens()` と同じ normalization を実装する。DB にある DESKTOP `screen` の canonical set を基礎とし、QSB snapshot が enabled の場合、または set が空の場合に、Launcher constant `Workspace.FIRST_SCREEN_ID` を一回だけ加える。first-screen value は Launcher3 constant から `PageId` に変換し、`"0"` を organizer が直接定義しない。QSB disabled かつ row-backed page set では first page を加えない。ページ順序は既存 canonical page ordering を使用し、合成された logical first page がある場合は Launcher first-screen semantics に従う最小 order を持つ。

reservation-aware `LayoutState` は `reservedWorkspaceRegions` と normalized page inventory を preserve-only context として持つ。`ContextResourceCodec` は v2 resource bytes に QSB enabled state、first-page identity、search span、canonical regions/page authority を encode する。`RevisionCalculator`、`CanonicalMarshalling`、`PersistenceManifest` read-back、`MaterializedStateValidator` はその同一 context を比較し、`OrganizationPlanMaterializer` は source/intended reservation equality を require する。reservation は `ApplyAction`、`PersistentRow`、Launcher DB SQL、recovery write-set の対象ではない。

planner は `PlanningValidation` で reservation の canonical order、page resolution、bounds、region同士の重複、captured workspace item との重複を検証する。production composer は platform origin の reservation を表現不能とした場合、より早く `NotReady(InvalidCanonicalCapture)` で fail closed する。pure planner fixture に与えた不正 value は既存 validation style に従い typed rejection とする。`PlanningPlacement` は preserved item occupancy の前に reservation を allocator へ mark し、new page allocation には reservation を持ち込まない。

recovery context encoding の変更は v2 format とする。ADR-0008 に従い、v1 record が `CREATING`、`READY`、`APPLYING`、`COMMITTED_UNVERIFIED`、`RESTORING`、`VERIFIED` のいずれかで存在する場合、v2 binary は v1 canonical hash を推測して migration/reconciliation せず、`INCOMPATIBLE_VERSION` として no-write fail closed とする。restorable/non-final point と retained tombstone が存在しない empty/retention-complete v1 store だけ、recovery-DB-only atomic migration が v2 schemaを生成する。v2→v1 downgrade は既存 version gate により同じく incompatible/no Launcher mutation である。[5] [6] [7]

### Data flow

```text
one-shot capture attempt
  ├─ Launcher first-page/QSB/IDP snapshot
  ├─ normalized page authority
  ├─ canonical DB row/profile capture
  └─ LayoutState + v2 context resource + revision
       └─ OrganizationInputComposer
            └─ LayoutSnapshot(reservedWorkspaceRegions)
                 └─ OrganizationPlanner.plan()
                      └─ target placements outside reservation
                           └─ OrganizationPlanMaterializer
                                └─ ValidatedLayoutPlan (reservation unchanged)
                                     └─ A2/A5: fresh reservation-inclusive capture equality
                                          └─ A6: DB updates for real items only
                                               └─ A7: correlated reload + recapture
                                                    └─ A8: DB/model/reservation convergence
```

Production capture uses the following fixed order. It snapshots first-page/QSB geometry once, builds the normalized page set, captures canonical rows/profiles against that page authority, then validates that QSB region dimensions are `1..columns` and that no captured real workspace item overlaps the region. The result remains valid only if every representation uses the original snapshot values. If the region is invalid or overlaps a row, composer returns typed non-write `NotReady`; it must not let baseline Loader sanitation repair the conflict. A later capture observes changed enabled state, geometry, or normalized pages as a different revision and prevents apply at A2/A5.

### Alternatives rejected

| Alternative | 判断 | 却下理由 |
|---|---|---|
| **Planner input で reservation を first-class constraint として表す** | 採用 | platform bridge を変更せず、pure planning の allocation と Loader の占有 rule を同じ input context へ揃え、A7 before の data deletion を予防する。 |
| Reservation page を row-derived `PageId("0")` と固定する | 却下 | rowless workspace を unknown page と誤認し、Launcher first-screen authority と乖離する。`Workspace.FIRST_SCREEN_ID` と existing page-normalization semantics を使う。[4] |
| Organizer-correlated reload 時だけ `LoaderCursor` overlap delete を skip する | 却下 | overlap deletion は sanitizer より前の general loader integrity path にあり、organizer token で policy を分岐させると invalid row を model へ取り込む危険がある。normal reload policy と二重化し、Issue #155 の preferred seam に反する。[2] [3] |
| QSBを `CapturedItem` / synthetic widget / target membership に偽装する | 却下 | `items` は captured existing layout only であり、synthetic item は conservation、TargetSet partition、plan materialization、action set、recovery manifest を汚染する。[8] [9] |
| Canonical hashを変えつつ recovery format v1 を維持する | 却下 | v1 pending record の digest/precondition を v2 context で誤って再解釈し、crash/recovery safety を破る。v2 format/version gate と explicit migration eligibility が必要である。[5] [6] |
| v1 pending recovery を自動的に v2 record へ変換する | 却下 | v1 payload には QSB snapshot がなく、pre/post equality と pending lifecycle の正しい意味を安全に復元できない。推測migrationは行わず fail closed とする。 |
| A7 mismatch を success 扱いにする、または一致まで retry する | 却下 | exact verification、truthful result、transaction/recovery contract を弱め、原因となる deletion を解消しない。[1] [9] |
| first screen 全体を常に避ける | 却下 | QSB が占有する実際の矩形より強い制約であり、available capacity と既存 layout strategy を不必要に変える。 |

ADR-0008 を追加する。rowless first-page authority、canonical revision resource、legacy recovery migration eligibility は、rollback/recovery semantics と format compatibility を変える高コストな選択であり、複数の安全な候補が存在するため、`AGENTS.md` の ADR 閾値を満たす。[10]

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `specs/155-qsb-reservation-reload/spec.md` | 状態、scenario、acceptance、failure semantics を `draft` から review/acceptance 可能な正本にする。 | #155 の observable behavior の正本。 |
| `docs/adr/0008-qsb-reservation-context-and-recovery-compatibility.md` | page authority、one-shot capture、recovery v2、v1 fail-closed/empty-store migration、downgrade を決定する。 | high-cost recovery/compatibility decision の唯一の正本。 |
| `specs/10-pure-organization-planning/spec.md` | `LayoutSnapshot` に non-item `ReservedWorkspaceRegion` list を追加し、canonical/bounds/overlap validation と non-action semantics を規定する。 | planner public input shape の正本。 |
| `specs/12-deterministic-full-layout-planner-v1/spec.md` | P-06 の initial occupancy を preserved items **and reservations** と明記し、rowless first-screen/QSB fixture を追加する。 | deterministic placement rule の正本。 |
| `specs/13-safe-layout-application/spec.md` | normalized page authority/QSB context、v2 recovery compatibility、A2/A5/A7/A8 の resource semantics を追加する。 | capture/revision/recovery/verification の正本。 |
| `lawnchair/.../planning/OrganizationInput.kt` | `ReservedWorkspaceRegion` と `LayoutSnapshot.reservedWorkspaceRegions = emptyList()` を定義する。 | Android-free planner input で reservation を表す唯一の値。 |
| `lawnchair/.../planning/PlanningValidation.kt` | page resolution、canonical uniqueness、bounds、region-to-item overlap の typed checks を追加する。 | invalid reservation を fail closed し、planner invariant を維持する。 |
| `lawnchair/.../planning/PlanningPlacement.kt` | allocator construction時に reservation rectangles を mark する。 | movable item/new folder が QSB cells を選ばないようにする。 |
| `lawnchair/.../integration/OrganizationInputComposer.kt` | canonical `LayoutState` reservation/page context を `LayoutSnapshot` へ lossless mapping。invalid capture を `NotReady` にする。 | composer は UI/DB を触らない existing input seam。 |
| `lawnchair/.../integration/ProductionOrganizationInputComposer.kt` | reservation-aware canonical capture source を組み立てる依存を追加する。 | production-only Android authority を pure composer へ漏らさない。 |
| `lawnchair/.../application/public/LayoutState.kt` | normalized page authority と preserve-only `ReservedWorkspaceRegion` context を追加する。 | source/intended state、apply/recovery、verification が同じ resource を比較する。 |
| `lawnchair/.../application/adapter/LauncherLayoutAdapter.kt` | one-shot FeatureFlags/first-screen/IDP snapshot、page normalization、current/re-captureへの同一 canonical context投入を実装する。 | existing canonical capture と platform translation の boundary。 |
| `lawnchair/.../application/adapter/ContextResourceCodec.kt` | v2 device/page/reservation context encoding を追加する。 | recovery record が v2 canonical context を正確に保存する。 |
| `lawnchair/.../application/canonical/CanonicalMarshalling.kt` | page authority/reservation list の stable encoding を追加する。 | revision/digest が QSB context change を検出する。 |
| `lawnchair/.../application/store/RecoveryDbSchema.kt`、`RecoveryDbHelper.kt`、`RecoveryDbVersionGate.kt`、`RecoveryStore.kt`、`RecoveryRecordCodec.kt` | v2 schema/record codec、v1 active-store rejection、empty-store migration、v2→v1 rejection を実装する。 | reservation-aware recovery compatibility を explicit/fail-closed にする。 |
| `lawnchair/.../application/protocol/RestartReconciler.kt`、`RecoveryProtocol.kt`、`RecoveryPreviewProtocol.kt` | incompatible legacy state を typed safe result/inspection state へ投影する。 | pending recovery を success/restored と誤表示しない。 |
| `lawnchair/.../application/protocol/MaterializedStateValidator.kt` と `OrganizationPlanMaterializer.kt` | intended state が page authority/reservation を変更・drop しない validation を追加する。 | reservation が write action/DB row に漏れず、A8 convergence comparison に残ることを保証する。 |
| `tests/unit/.../OrganizationInputComposerTest.kt` | enabled/disabled、rowless/row-backed first page、invalid span/page/item overlap、one-shot snapshot、no planner/writer invocation を追加する。 | composition safety の focused unit evidence。 |
| `tests/unit/.../DefaultLayoutComposerPlannerRegressionTest.kt` と planner contract fixtures | 4×5 default layout + folder を `(0,0)` に置かない regression、rowless first page、permutation/determinism、new-page overflow を追加する。 | current failure path を planner seam で red-first 再現する。 |
| application unit/contract tests | enabled flip/span/page normalization drift at A2/A5、materialization no-action、A7 reservation equality、v1/v2 compatibility を追加する。 | exact revision and recovery contract evidence。 |
| `tests/organizer-instrumentation/.../ProductionOrganizationInputInstrumentationTest.kt` | real page/QSB/IDP projection、rowless first page、one-shot capture/no-write を確認する。 | Android authority と pure input の parity evidence。 |
| `tests/organizer-instrumentation/.../store/RecoveryStoreInspectionInstrumentationTest.kt` と `.../RecoveryStoreLifecycleTest.kt` | v1 pending lifecycle matrix、empty-store migration、v2 downgrade、no Launcher mutation を追加する。 | recovery compatibility evidence。 |
| `tests/organizer-instrumentation/.../ManualOrganizationProductionE2EInstrumentationTest.kt` | enabled QSB default workspace の manual run→A7→A8 result/recovery を追加する。 | #150 unblock の production E2E evidence。 |
| `tests/organizer-instrumentation/.../SanitizerInstrumentationTest.kt` | ordinary sanitizer behavior を維持しつつ、reservation外 folder が correlated reload で残る regression を追加する。 | no Loader policy weakening を独立に証明する。 |

`LoaderCursor.java`、`LoaderTask.java`、`LauncherModel.java`、`LayoutWriteCoordinator.java` は **コード変更対象外** である。ただし instrumentation test は current bridge が修正後の target を安全に reload できることを証明する。bridge code を変更する必要が判明した場合は、本 plan を止め、Issue #155 に evidence と代替 design を記録して spec review をやり直す。

## Migration and recovery

Launcher schema、rule/taxonomy bundle、override store、permission、backup format の migration はない。reservation は `favorites` row ではないが、A2/A5/A7/A8 and recovery reconciliation の context resource であるため、private recovery DB record format は v2 に上げる。migration must run before ordinary recovery-store opening and must never open/mutate the Launcher database.

| 更新前状態 | v2 起動時の扱い | Launcher DB / v1 record |
|---|---|---|
| v1 store が無い | v2 DB を新規作成する。 | 変更なし / 対象なし。 |
| v1 store が empty かつ tombstone retention 完了 | recovery DB 内で only-once atomic v1→v2 migration を行う。 | 変更なし / v2 empty storeへ遷移。 |
| v1 store に `CREATING`、`READY`、`APPLYING`、`COMMITTED_UNVERIFIED`、`RESTORING` がある | migration/reconciliationを開始せず `INCOMPATIBLE_VERSION`。 | 変更なし / legacy bytesを保持。 |
| v1 store に `VERIFIED`/restorable point または retained tombstone がある | migration/recoveryを開始せず `INCOMPATIBLE_VERSION`。 | 変更なし / legacy bytesを保持。 |
| v2 binary から v1 binary へ downgrade | v1 version gate が v2 store を `INCOMPATIBLE_VERSION` とする。 | 変更なし / v2 bytesを保持。 |
| migration failure/uncertain outcome | store unavailable/incompatible として fail closed; no retry that can overwrite v1 data without a fresh verified state. | 変更なし / old bytesを保持。 |

Failure handling は既存 application protocol を使う。A2 または A5 で reservation-inclusive revision/precondition が変化すれば `Rejected(STALE_REVISION | EXACT_PRECONDITION_FAILED)` で no write となる。A6 commit 後に model reload または reservation-aware A7/A8 verification が失敗すれば、**v2** recovery point がある場合にのみ既存 row-accounted recovery を試行し、`Recovered`、`Unresolved`、`RecoveryFailed` を truthfully return する。legacy v1 point は try-to-recover せず `INCOMPATIBLE_VERSION` を提示する。成功パスで reservation は pre/intended layout state の value equality に残るが、`ApplyAction`、Launcher DB SQL、recovery write-set の対象にはならない。[6] [7]

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| QSB-AC-01 | composer unit + production instrumentation。QSB enabled/disabled、rowless/row-backed authority、first-screen constant、search span、no synthetic item/target を確認する。 | `testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.integration.*'`、API 36.1 focused instrumentation。 |
| QSB-AC-02 | planner public seam: 4×5 QSB region、multi-span folder/widget、permutation/determinism、rowless logical first page、new-page fallback。 | organizer JVM test + existing planner property/harness lane。 |
| QSB-AC-03 | canonical marshalling/revision + materializer/application contract。enabled flip、span drift、page normalization drift at A2/A5 は no-write。 | focused application JVM tests。 |
| QSB-AC-04 | source/intended resource equality と no-action assertions。v2 record encode/decode/read-back checksum が reservation context を保持し、recovery write-set が preserve-only であることを検証する。 | recovery store unit/contract tests。 |
| QSB-AC-05 | v1 fixture DB lifecycle matrix、empty-store v1→v2 migration、v2→v1 downgrade。both DB fingerprints must prove no Launcher mutation and no non-empty legacy-store rewrite。 | API 36.1 `RecoveryStoreLifecycleTest` / recovery inspection instrumentation。 |
| QSB-AC-06 | red-first fixture: unreserved `(0,0)` target produces reload deletion/mismatch; fixed input selects a non-reserved target and A7 manifest equality holds。 | unit regression + API 36.1 `ManualOrganizationProductionE2EInstrumentationTest`。 |
| QSB-AC-07 | actual 4×5 default workspace, enabled QSB, manual run reaches `Applied` after A7/A8; row persists and recovery point is restorable。QSB-disabled and rowless controls also pass。 | clean `nunu_qpr2_api36_1` / debug APK instrumentation。 |
| QSB-AC-08 | normal reload sanitizer still removes malformed fixtures; organizer reload does not delete a valid reservation-safe folder。 | `SanitizerInstrumentationTest` / shared-writer connected-test lane。 |
| QSB-AC-09 | format, source/build, contract gate, exact-head CI, independent audit。 | commands below + CI `final-status` + `docs/assessment/pr-<n>-<slug>.md`。[10] [11] |

Run the following commands in a clean or appropriately provisioned checkout and record the exact output/commit in the pull request:

```bash
./gradlew spotlessCheck
python3 tools/repo-contract/validate_repo_contract.py
python3 tools/repo-contract/test_validate_repo_contract.py
./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'
./gradlew assembleLawnWithQuickstepGithubDebug
```

The source-changing, `risk: layout-data` PR must additionally run the relevant API 36.1 connected-test jobs, obtain a successful `CI / final-status` for its head SHA, and receive an independent audit from a separate session. The audit must enumerate QSB-AC-01 through QSB-AC-09, ADR-0008, the executed test surface, the legacy-format fixture matrix, and the CI run URL.[10] [11]

## Documentation updates

- [x] Add Issue #155 `spec.md` and `plan.md` with `draft` status.
- [x] Revise Spec/Plan in response to review comment `#issuecomment-5432708854`.
- [x] Add proposed ADR-0008 for page authority and recovery compatibility.
- [ ] After spec/ADR review, update Spec #10 public input definitions and validation criteria.
- [ ] After spec/ADR review, update Spec #12 placement initial-occupancy rule and fixtures.
- [ ] After spec/ADR review, update Spec #13 capture/revision/verification/recovery-v2 context resources.
- [ ] Keep `CONTEXT.md` unchanged; no user-domain term is introduced.
- [ ] Update `DESIGN.md` only if accepted review determines its module boundary/reference list must state reservation context explicitly.
- [ ] Add PR-linked independent audit under `docs/assessment/` after source implementation.

## Execution checklist

- [ ] Obtain Issue #155 spec review and ADR-0008 acceptance before modifying source.
- [ ] Establish red evidence that existing composer/planner can select `(0,0)` under enabled QSB and that the correlated reload deletes the valid row.
- [ ] Add page-authority normalization and immutable one-shot capture context before changing allocation.
- [ ] Add canonical reservation model plus revision/marshalling/recovery-v2 propagation before changing allocation.
- [ ] Add validation and planner allocation behavior through the existing public `plan` seam.
- [ ] Add materializer/application checks proving reservation is preserve-only/no-action and QSB/page drift is stale/no-write.
- [ ] Add recovery v1→v2 compatibility/rejection instrumentation before enabling v2 checkpoints.
- [ ] Add focused production instrumentation and default-workspace manual E2E evidence through A7/A8.
- [ ] Re-run normal sanitizer/shared-writer tests to prove the Loader bridge policy is unchanged.
- [ ] Complete format, repository contract, JVM/build, CI `final-status`, and independent high-risk audit; record the exact outcomes in the PR.

## References

[1]: https://github.com/nunu1733/NunuLauncher/issues/155 "Issue #155 — observed A7 QSB-overlap failure"
[2]: https://github.com/nunu1733/NunuLauncher/blob/main/src/com/android/launcher3/model/LoaderCursor.java "LoaderCursor — QSB occupancy and markDeleted"
[3]: https://github.com/nunu1733/NunuLauncher/blob/main/src/com/android/launcher3/model/LoaderTask.java "LoaderTask — commitDeleted ordering"
[4]: https://github.com/nunu1733/NunuLauncher/blob/main/src/com/android/launcher3/model/BgDataModel.java "BgDataModel — first workspace screen authority"
[5]: ../../lawnchair/src/app/lawnchair/organizer/application/store/RecoveryDbSchema.kt "Recovery DB schema — current format"
[6]: ../../lawnchair/src/app/lawnchair/organizer/application/store/RecoveryRecordCodec.kt "Recovery record codec — versioned canonical payload"
[7]: ../../lawnchair/src/app/lawnchair/organizer/application/store/RecoveryDbVersionGate.kt "Recovery DB pre-open version gate"
[8]: ../10-pure-organization-planning/spec.md "Spec #10 — planner input contract"
[9]: ../13-safe-layout-application/spec.md "Spec #13 — safe application, revision, A7/A8 verification"
[10]: ../../AGENTS.md "Repository rules — ADR threshold and high-risk layout-data evidence"
[11]: ../../docs/engineering/quality-strategy.md "Quality strategy — connected test and high-risk gate"
