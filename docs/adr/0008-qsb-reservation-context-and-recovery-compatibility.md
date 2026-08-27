---
status: proposed
issue: "#155"
updated: 2026-08-27
---

# QSB予約コンテキストのpage authorityとrecovery format互換性

## Decision

manual `FullOrganization` の QSB reservation は、`Workspace.FIRST_SCREEN_ID` を起点とする Launcher の first-workspace-page authority に所属する **non-item, preserve-only context** として扱う。page authority は `BgDataModel.collectWorkspaceScreens()` の既存規則と一致させる。すなわち、DESKTOP row から得た screen set を正規化し、QSB-on-first-screen が有効な場合、またはその set が空の場合に `Workspace.FIRST_SCREEN_ID` を一度だけ含める。QSB が無効で set が空の場合にも logical first page は存在するが、reservation list は空である。QSB が無効で set が空でない場合には、first page を追加しない。[1]

QSB reservation の feature condition、first page identity、IDP columns/rows、`numSearchContainerColumns`、normalized page inventory は、**1回の canonical capture attempt の開始時に一度だけ** snapshot する。その immutable capture context を `LayoutState`、planner `LayoutSnapshot`、application revision/precondition、A7/A8 verification、recovery record に一貫して使う。QSB reservation は `CapturedItem`、`TargetSet`、`ApplyAction`、`PersistentRow` ではない。planner はそれを initial occupancy としてのみ使用し、application/recovery は context が source/intended state 間で不変であることだけを検証する。[2] [3]

reservation context を recovery record の canonical resource に追加するため、private recovery DB/record format を **v2** にする。format v1 の non-final point、restorable `VERIFIED` point、または retained tombstone が存在するとき、v2 binary は legacy record を新しい canonical revision/digest の意味に再解釈・再hash・書換えしない。pre-open compatibility gate は `INCOMPATIBLE_VERSION` を返し、Launcher DB、legacy recovery DB、point lifecycle に一切触れない。v1 store が empty で tombstone retention も完了した場合のみ、recovery-DB-only の atomic v1→v2 migration を許可する。v2→v1 downgrade も同様に incompatible/no Launcher mutation とする。[4] [5] [6]

## Context

Issue #155 は、planner が first-screen `(0,0)` を空きセルとして計画する一方、`LoaderCursor` が enabled QSB の first row を occupied として扱うため、相関 reload が organizer の folder row を overlap として削除する問題を追跡する。`LoaderCursor` は `Workspace.FIRST_SCREEN_ID`、enabled feature condition、`numSearchContainerColumns` で `(0,0,width,1)` を mark する。[2]

既存 `LauncherLayoutAdapter.dbDesktopPageIds()` は DESKTOP row から page IDs を導出するため、rowless workspace をそのまま扱うと page inventory は空となる。しかし Launcher は item row だけを page authority としていない。`BgDataModel.collectWorkspaceScreens()` は QSB enabled または empty screen set のいずれでも first screen を加える。この difference を organizer が独自 literal `PageId("0")` や synthetic DB row で補うと、production capture と Loader runtime semantics がずれ、rowless default workspace の failure mode を生む。[1]

また、Spec #13 の recovery protocol は pre/post revision、digest、context resource を保存して restart reconciliation を行う。reservation context を canonical hash に含めるなら、v1 record が表す DB-only/device context を v2 reservation-aware context として黙って読み替えることはできない。recovery record format は現状 v1 のみを decode し、version gate は unsupported format を no-write incompatible にするため、この既存 fail-closed stance を明示的な migration policy に拡張する。[4] [5] [6]

この判断は、(a) rowless first workspace の page identity、(b) stale/revision semantics、(c) pending apply/recovery の crash safety、(d) upgrade/downgrade compatibility を横断する。複数の実行可能な選択肢があり、誤ると user layout または recovery point を失うため、`AGENTS.md` の ADR 条件を満たす。[7]

## Alternatives

| Alternative | Advantages | Disqualifying cost or risk |
|---|---|---|
| **Launcher first-page authority + one-shot context + recovery v2** | Loader/runtimeと同じ page/QSB semanticsを使い、rowless workspace を安全に表現する。context driftをrevisionで検出し、v1 pending recovery を推測せず保全できる。 | recovery v2 migration/compatibility test と legacy fail-closed UX が必要。 |
| DB row-derived pagesだけを使い、`PageId("0")` を organizerで固定する | 小さな実装に見える。 | rowless first page を invalid と誤認し、platform identity を重複定義する。QSB geometry と page authority が別々になる。 |
| reservationを item-row page inventory と無関係な planner-only page として扱う | application captureのpage modelを変えずに済む。 | plan materialization、A2/A5 revision、A7 verification、recovery manifestが異なる page universe を見てしまい、exact convergenceを証明できない。 |
| enabled QSB時だけ first pageを合成し、disabled + empty workspaceは空pageのままにする | QSB caseだけを最小修正できる。 | QSB toggle が logical page existenceを不必要に変え、empty manual runとstale semanticsに不連続を作る。Launcherのempty-set first-page ruleとも一致しない。 |
| canonical hashを変えても recovery v1を維持する | recovery DB schemaを変えない。 | v1 pending recordにない reservation contextを推測し、pre/post digestとrestart reconciliationの意味を破る。 |
| non-empty v1 recordsを自動v2 migrationする | upgrade後に旧 recovery pointを維持できる可能性がある。 | v1 payloadからenabled state/span/page authorityを安全に復元できない。特に pending lifecycleは新contextのrecovery対象として検証できず、false restoreのリスクがある。 |
| organizer reloadだけLoader overlap deletionをskipする | planner modelの変更を回避できる。 | Loaderの一般data-validity policyをtokenで変え、invalid rowsをmodelへ入れる特例を作る。Issue #155 preferred seamにも反する。 |

## Consequences

planner の public operation `plan(input)` と application の `apply(plan)`/`recover(request)` の署名、result variants は変えない。一方で `LayoutSnapshot` と canonical `LayoutState` は preserve-only reservation/page context を表現できるようになる。Spec #10 は public input shape、Spec #12 は initial occupancy、Spec #13 は capture/revision/recovery formatを本 ADR に整合させる。

planner/composer は synthetic item を追加しないため、TargetSet complete partition、conservation、action materialization、Launcher DB mutation の対象は従来どおり captured `favorites` rows のみである。enabled QSB の valid plan は `(0,0,width,1)` を避ける。invalid reservation geometry または real item overlap は typed non-write failure とし、Loader cleanupへ依存しない。

v2 recordが導入されるまで、v2 checkpointを作ってはならない。v1 non-empty storeを持つ upgrade は organizer mutation/recoveryを unavailable にし、normal Launcher operationとexisting layoutを保護する。empty-store migrationのatomicity、migration failure、legacy compatibility、downgradeは dedicated instrumentation evidence を必須とする。長期 backup は recovery DB の対象外であり、既存の Lawnchair export backup behavior を変更しない。[6]

## References

[1]: https://github.com/nunu1733/NunuLauncher/blob/main/src/com/android/launcher3/model/BgDataModel.java "BgDataModel — collectWorkspaceScreens first-page authority"
[2]: https://github.com/nunu1733/NunuLauncher/blob/main/src/com/android/launcher3/model/LoaderCursor.java "LoaderCursor — QSB occupancy geometry"
[3]: ../../specs/155-qsb-reservation-reload/spec.md "Issue #155 draft spec"
[4]: ../../specs/13-safe-layout-application/spec.md "Spec #13 — recovery record and lifecycle"
[5]: ../../lawnchair/src/app/lawnchair/organizer/application/store/RecoveryDbSchema.kt "Recovery DB schema — v1 baseline"
[6]: ../../lawnchair/src/app/lawnchair/organizer/application/store/RecoveryDbVersionGate.kt "Recovery DB version gate — incompatible without Launcher mutation"
[7]: ../../AGENTS.md "Repository rules — ADR threshold and layout safety"

## Change history

- 2026-08-27: Proposed for Issue #155 in response to the Spec/Plan review comment `#issuecomment-5432708854`.
