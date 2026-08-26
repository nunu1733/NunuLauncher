> Issue: #155
> Spec: [spec.md](./spec.md)
> Status: draft
> Baseline: `main` / `7ba2194ce7`

## Current evidence

Issue #155 の device evidence は、manual organization plan が Google folder を first-screen `(0,0)` へ移動し、A6 commit 後の相関 reload がその row を削除する因果列を示している。これは A7 completion timing ではなく、planner occupancy と Loader occupancy の policy mismatch である。[1]

`LoaderCursor.checkItemPlacement` は first screen の occupancy を初期化するとき、`topQsbOnFirstScreenEnabled` が true であれば `(0,0)` から `numSearchContainerColumns × 1` を occupied として mark する。後続 item がその矩形と重なる場合、`checkAndAddItem` は `markDeleted("Item position overlap")` を実行する。[2] `LoaderTask.loadWorkspaceImpl` は loader 本体の完了後に `c.commitDeleted()` を呼ぶため、削除は後段 sanitizer より前に確定する。[3]

既存の organizer reload token は sanitizer families を抑止するが、QSB overlap cleanup を抑止しない。したがって、loader bridge に「organizer reload では overlap delete を行わない」という特例を加える案は、通常 Loader が担う data-validity policy と organizer verification を混在させ、Issue #155 の preferred seam ではない。[3] [4]

現在の `OrganizationInput` は items/pages/device capability のみを持ち、`DefaultOrganizationInputComposer` と `FullTargetSetMaterializer` は captured item の完全 partition だけを作る。`PlanningPlacement` は preserved workspace item を初期 occupancy に mark するが、非 item の reservation は存在しない。このため current `main` は QSB reservation と同じ allocation constraint を planner へ渡せない。[5] [6] [7]

| 確認済みの事実 | 根拠 | 実装上の含意 |
|---|---|---|
| QSB reservation は first screen の上端 1 row、幅は `numSearchContainerColumns` | `LoaderCursor` | 同一 authority から PageId/cell/span を導出する。 |
| reload 中の overlap deletion は `commitDeleted()` で DB に反映される | `LoaderTask` | A7 の前に row が失われるため、verification retry では修正できない。 |
| composer は canonical application capture を唯一の layout source とする | Spec #83 / `OrganizationInputComposer` | UI/local DB read を追加せず、capture adapter へ reservation context を集約する。 |
| application seam は device capability を revision と exact precondition に含める | Spec #13 / `RevisionCalculator` | QSB enabled/span の差分も same capture identity に束縛する。 |
| planner input の `items` は captured existing layout のみ | Spec #10 | QSB を synthetic item/target/action にしてはならない。 |

## Design

### Modules and interfaces

**選択する seam は planner input composition である。** `OrganizationPlanner.plan(input)` と `LayoutApplicationModule.apply(plan)` の operation/result interface は維持する。その代わり、canonical layout context と planner `LayoutSnapshot` に opaque value `ReservedWorkspaceRegion(page, cell, span)` の canonical list を追加する。この値は layout item、target、placement outcome、DB row ではない。Kotlin constructor には empty-list default を置き、既存の pure-fixture call site を source-compatible に保つ。ただし public input shape の拡張であるため、Spec #10/#12 と Spec #13 の input/revision clauses を本 Issue の accepted spec に同期する。

application capture は `LauncherLayoutAdapter` が持つ既存 `Context` と `InvariantDeviceProfile` を使い、FeatureFlags と QSB span を one capture context として `DeviceCapabilities` へ projection する。`DeviceCapabilities` は reservation list を直接内包せず、reservation context を canonical capture として扱う必要がある。推奨実装は `LayoutState` に `reservedWorkspaceRegions` を追加し、`LayoutSnapshot` はそれを lossless に mapping する方法である。これにより `RevisionCalculator`、A2/A5 exact capture、A7 post-reload recapture、materialized state validator が同じ revision resource を比較できる。予約値は both pre-state and intended state で Preserve-only であり、apply action set に現れない。

planner は `PlanningValidation` で reservation の canonical order、bounds、重複、page existence、captured item overlap を validate し、invalid reservation を typed planner-invalid に変換しない。production composer が platform origin の reservation を表現不能とした場合は、より早く `NotReady(InvalidCanonicalCapture)` で fail closed する。pure planner fixture に与えた不正 value は既存 validation style に従い typed rejection とする。`PlanningPlacement` は preserved item occupancy の前に reservation を allocator へ mark し、new page allocation には reservation を持ち込まない。

`OrganizationPlanMaterializer` は planner placements が persistent captured item IDs と一致することを引き続き要求する。reservation は item list に入らないため、既存の `snapshotIds == sourceItems.keys` assertion と action generation を変更しない。ただし source/intended `LayoutState` の reservation equality を require し、apply plan が captured reservation の書換えを含まないことを explicit validation で保証する。

### Data flow

```text
Launcher feature/grid authority
  └─ captureCurrent()
       ├─ canonical LayoutState + DB manifest + revision context
       └─ reserved first-screen region (enabled QSB のみ)
            └─ OrganizationInputComposer
                 └─ LayoutSnapshot(reservedWorkspaceRegions)
                      └─ OrganizationPlanner.plan()
                           └─ target placements outside reservation
                                └─ OrganizationPlanMaterializer
                                     └─ ValidatedLayoutPlan
                                          └─ A2/A5: reservation-inclusive revision/preconditions
                                               └─ A6 DB updates for real items only
                                                    └─ A7 correlated reload + recapture
                                                         └─ A8 reservation-aware equality/invariants
```

Reservation derivationは次の順序で行う。まず `topQsbOnFirstScreenEnabled(appContext)` を読み、false なら empty list とする。true のとき、first-screen `PageId` が canonical page inventory にあること、`numSearchContainerColumns` が `1..columns` であること、span `(width,1)` が device bounds 内であることを確認する。次に one region `(FIRST_SCREEN_ID, GridCell(0,0), GridSpan(width,1))` を canonical order で生成する。capture 内に同 region と重なる real workspace item があるなら、全体を `InvalidCanonicalCapture` とし、loader cleanup への依存で矛盾を隠さない。[2] [8]

### Alternatives rejected

| Alternative | 判断 | 却下理由 |
|---|---|---|
| **Planner input で reservation を first-class constraint として表す** | 採用 | platform bridge を変更せず、pure planning の allocation と Loader の占有 rule を同じ input context へ揃え、A7 before の data deletion を予防する。 |
| Organizer-correlated reload 時だけ `LoaderCursor` overlap delete を skip する | 却下 | overlap deletion は sanitizer より前の general loader integrity path にあり、organizer token で policy を分岐させると invalid row を model へ取り込む危険がある。normal reload policy と二重化し、Issue #155 の preferred seam に反する。[2] [3] |
| QSBを `CapturedItem` / synthetic widget / target membership に偽装する | 却下 | `items` は captured existing layout only であり、synthetic item は conservation、TargetSet partition、plan materialization、action set、recovery manifest を汚染する。[5] [9] |
| A7 mismatch を success 扱いにする、または一致まで retry する | 却下 | exact verification、truthful result、transaction/recovery contract を弱め、原因となる deletion を解消しない。[1] [9] |
| first screen 全体を常に避ける | 却下 | QSB が占有する実際の矩形より強い制約であり、available capacity と既存 layout strategy を不必要に変える。 |

ADR は追加しない。選択は既存の「pure planning + production composition + minimal Launcher bridge」という accepted design の直接適用であり、storage/transaction/public operation の高コストな選択を変更しない。Spec #10/#12/#13 の canonical input/revision clauses は同一 PR で更新して矛盾を残さない。[9] [10]

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `specs/155-qsb-reservation-reload/spec.md` | 状態、scenario、acceptance、failure semantics を `draft` から review/acceptance 可能な正本にする。 | #155 の observable behavior の正本。 |
| `specs/10-pure-organization-planning/spec.md` | `LayoutSnapshot` に non-item `ReservedWorkspaceRegion` list を追加し、canonical/bounds/overlap validation と non-action semantics を規定する。 | planner public input shape の正本。 |
| `specs/12-deterministic-full-layout-planner-v1/spec.md` | P-06 の initial occupancy を preserved items **and reservations** と明記し、QSB first-screen fixture を追加する。 | deterministic placement rule の正本。 |
| `specs/13-safe-layout-application/spec.md` | canonical capture/revision/precondition/verification の context resource に reserved workspace regions を追加する。 | A2/A5/A7/A8 の same-context guarantee の正本。 |
| `lawnchair/.../planning/OrganizationInput.kt` | `ReservedWorkspaceRegion` と `LayoutSnapshot.reservedWorkspaceRegions = emptyList()` を定義する。 | Android-free planner input で reservation を表す唯一の値。 |
| `lawnchair/.../planning/PlanningValidation.kt` | page resolution、canonical uniqueness、bounds、region-to-item overlap の typed checks を追加する。 | invalid reservation を fail closed し、planner invariant を維持する。 |
| `lawnchair/.../planning/PlanningPlacement.kt` | allocator construction時に reservation rectangles を mark する。 | movable item/new folder が QSB cells を選ばないようにする。 |
| `lawnchair/.../integration/OrganizationInputComposer.kt` | canonical `LayoutState` reservation を `LayoutSnapshot` へ lossless mapping。invalid capture を `NotReady` にする。 | composer は UI/DB を触らない existing input seam。 |
| `lawnchair/.../integration/ProductionOrganizationInputComposer.kt` | reservation-aware canonical capture source を組み立てる依存を追加する。 | production-only Android authority を pure composer へ漏らさない。 |
| `lawnchair/.../application/public/LayoutState.kt` | preserve-only `ReservedWorkspaceRegion` context を `LayoutState` に追加する。 | source/intended state、apply/recovery、verification が同じ resource を比較する。 |
| `lawnchair/.../application/adapter/LauncherLayoutAdapter.kt` | FeatureFlags/IDP/first screen から reservation を captureし、current/re-capture に同じ canonical context を返す。 | existing canonical capture と platform translation の boundary。 |
| `lawnchair/.../application/canonical/CanonicalMarshalling.kt` | reservation list の stable encoding を追加する。 | revision/digest が QSB context change を検出する。 |
| `lawnchair/.../application/protocol/MaterializedStateValidator.kt` および `OrganizationPlanMaterializer.kt` | intended state が source reservation を変更・drop しない validation を追加する。 | reservation が write action/DB row に漏れず、A8 convergence comparison に残ることを保証する。 |
| `tests/unit/.../OrganizationInputComposerTest.kt` | enabled/disabled reservation mapping、invalid span/page/item overlap、no planner/writer invocation を追加する。 | production composition safety の focused unit evidence。 |
| `tests/unit/.../DefaultLayoutComposerPlannerRegressionTest.kt` と planner contract fixtures | 4×5 default layout + folder を `(0,0)` に置かない regression、permutation/determinism、new-page overflow を追加する。 | current failure path を planner seam で red-first 再現する。 |
| application unit/contract tests | QSB context change at A2/A5 の stale/no-write、materialization no-action、A7 reservation equality を追加する。 | exact revision and apply contract evidence。 |
| `tests/organizer-instrumentation/.../ProductionOrganizationInputInstrumentationTest.kt` | real IDP/feature flag projection を確認する。 | Android authority と pure input の parity evidence。 |
| `tests/organizer-instrumentation/.../ManualOrganizationProductionE2EInstrumentationTest.kt` | enabled QSB default workspace の manual run→A7→A8 result/recovery を追加する。 | #150 unblock の production E2E evidence。 |
| `tests/organizer-instrumentation/.../SanitizerInstrumentationTest.kt` | ordinary sanitizer behavior を維持しつつ、reserved外 folder が correlated reload で残る regression を追加する。 | no Loader policy weakening を独立に証明する。 |

`LoaderCursor.java`、`LoaderTask.java`、`LauncherModel.java`、`LayoutWriteCoordinator.java` は **コード変更対象外** である。ただし instrumentation test は current bridge が修正後の target を安全に reload できることを proof する。bridge code を変更する必要が判明した場合は、本 plan を止め、Issue #155 に evidence と代替 design を記録して spec review をやり直す。

## Migration and recovery

Launcher schema、recovery schema、policy bundle、override store、backup、permission の migration はない。reservation は per-capture reconstructible context であり、persisted manifest/action set の subject ではない。したがって backup/restore、downgrade、revert が QSB row を作成・消去・復元することはない。

failure handling は既存 application protocol を使う。A2 または A5 で reservation-inclusive revision/precondition が変化すれば `Rejected(STALE_REVISION | EXACT_PRECONDITION_FAILED)` で no write となる。A6 commit 後に model reload または reservation-aware A7/A8 verification が失敗すれば、既存 recovery point から row-accounted recovery を試行し、`Recovered`、`Unresolved`、`RecoveryFailed` を truthfully return する。成功パスで reservationは pre/intended layout state の value equality に残るが、`ApplyAction`、Launcher DB SQL、recovery write-set の対象にはならない。[9]

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| QSB-AC-01 | composer unit + production instrumentation。QSB enabled/disabled、invalid width/page、no synthetic item/target を確認する。 | `testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.integration.*'`、API 36.1 focused instrumentation。 |
| QSB-AC-02 | planner public seam: 4×5 QSB region、multi-span folder/widget、permutation/determinism、new-page fallback。 | organizer JVM test + existing planner property/harness lane。 |
| QSB-AC-03 | canonical marshalling/revision + materializer/application contract。toggle/span drift at A2/A5 は no-write、planned reservation は no-action。 | focused application JVM tests。 |
| QSB-AC-04 | red-first fixture: unreserved `(0,0)` target produces reload deletion/mismatch; fixed input selects a non-reserved target and A7 manifest equality holds。 | unit regression + API 36.1 `ManualOrganizationProductionE2EInstrumentationTest`。 |
| QSB-AC-05 | actual 4×5 default workspace, enabled QSB, manual run reaches `Applied` after A7/A8; row persists and recovery point is restorable。disabled-QSB control also passes。 | clean `nunu_qpr2_api36_1` / debug APK instrumentation。 |
| QSB-AC-06 | normal reload sanitizer still removes malformed fixtures; organizer reload does not delete a valid reservation-safe folder。 | `SanitizerInstrumentationTest` / shared-writer connected-test lane。 |
| QSB-AC-07 | format, source/build, contract gate, exact-head CI, independent audit。 | commands below + CI `final-status` + `docs/assessment/pr-<n>-<slug>.md`。[10] [11] |

Run the following commands in a clean or appropriately provisioned checkout and record the exact output/commit in the pull request:

```bash
./gradlew spotlessCheck
python3 tools/repo-contract/validate_repo_contract.py
python3 tools/repo-contract/test_validate_repo_contract.py
./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'
./gradlew assembleLawnWithQuickstepGithubDebug
```

The source-changing, `risk: layout-data` PR must additionally run the relevant API 36.1 connected-test jobs, obtain a successful `CI / final-status` for its head SHA, and receive an independent audit from a separate session. The audit must enumerate QSB-AC-01 through QSB-AC-07, the spec/ADR references consulted, executed test surface, and CI run URL.[10] [11]

## Documentation updates

- [x] Add Issue #155 `spec.md` and `plan.md` with `draft` status.
- [ ] After spec review, update Spec #10 public input definitions and validation criteria.
- [ ] After spec review, update Spec #12 placement initial-occupancy rule and fixtures.
- [ ] After spec review, update Spec #13 capture/revision/verification context resources.
- [ ] Keep `CONTEXT.md` unchanged; no user-domain term is introduced.
- [ ] Keep `DESIGN.md` unchanged unless review determines the canonical context resource changes the documented module boundary.
- [ ] Do not add an ADR unless implementation reveals a new durable storage/public-operation/Launcher bridge choice.
- [ ] Add PR-linked independent audit under `docs/assessment/` after source implementation.

## Execution checklist

- [ ] Obtain Issue #155 spec review/acceptance before modifying source.
- [ ] Establish red evidence that existing composer/planner can select `(0,0)` under enabled QSB and that the correlated reload deletes the valid row.
- [ ] Add canonical reservation model plus capture/revision/marshalling propagation before changing allocation.
- [ ] Add validation and planner allocation behavior through the existing public `plan` seam.
- [ ] Add materializer/application checks proving reservation is preserve-only/no-action and QSB drift is stale/no-write.
- [ ] Add focused production instrumentation and default-workspace manual E2E evidence through A7/A8.
- [ ] Re-run normal sanitizer/shared-writer tests to prove the Loader bridge policy is unchanged.
- [ ] Complete format, repository contract, JVM/build, CI `final-status`, and independent high-risk audit; record the exact outcomes in the PR.

## References

[1]: https://github.com/nunu1733/NunuLauncher/issues/155 "Issue #155 — observed A7 QSB-overlap failure"
[2]: https://github.com/nunu1733/NunuLauncher/blob/main/src/com/android/launcher3/model/LoaderCursor.java "LoaderCursor — QSB occupancy and markDeleted"
[3]: https://github.com/nunu1733/NunuLauncher/blob/main/src/com/android/launcher3/model/LoaderTask.java "LoaderTask — commitDeleted ordering"
[4]: ../../docs/assessment/issue-44-shared-writer-audit.md "Issue #44 — correlated reload writer audit"
[5]: ../10-pure-organization-planning/spec.md "Spec #10 — planner input contract"
[6]: ../../lawnchair/src/app/lawnchair/organizer/integration/OrganizationInputComposer.kt "DefaultOrganizationInputComposer"
[7]: ../../lawnchair/src/app/lawnchair/organizer/planning/PlanningPlacement.kt "PlanningPlacement — initial occupancy and allocation"
[8]: ../../docs/product/item-preservation-policy.md "Item preservation policy — capture boundary and baseline cleanup"
[9]: ../13-safe-layout-application/spec.md "Spec #13 — safe application, revision, A7/A8 verification"
[10]: ../../AGENTS.md "Repository rules — high-risk layout-data evidence"
[11]: ../../docs/engineering/quality-strategy.md "Quality strategy — connected test and high-risk gate"
