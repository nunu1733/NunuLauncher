# Implementation Plan: ZIP restore後のmanual organization NotReadyの根因特定

> Issue: #153
> Spec: [spec.md](./spec.md)
> Status: draft（review rev 3対応済み。承認待ち）

## Current evidence

確認済みの事実（現行main `667e8915f2` 時点）:

- **観測surfaceは実装済み（H3以降のみ有効）**: `ManualOrganizationRun.start()` の `NotReady`
  分岐は `INPUT_NOT_READY`（`ErrorFamily.INPUT_READINESS` + `InputCompositionCode`）terminal
  eventをemitする（[ManualOrganizationRun.kt:213](../../lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt)、
  `emitInputNotReady` 同631-）。capture失敗は `DiagnosticsLogger` のtyped API
  （`Class<out Throwable>` のみ、debug build限定）でlogcatに出る（PR #184 `50ddb86148`）。
  H0–H2のheadにはこのsurfaceが存在しない（設計上、理由コードは取得できない）。
- **当時のepisode観測**（[issue-104-105-106 device evidence](../../docs/assessment/evidence/issue-104-105-106-device-evidence.md)
  「Observation recorded during this check」）: 観測headは `74c2156767`（PR #148 merge、
  2026-08-26 14:30 +0900。#150ブランチ経由の検証時）。ZIP restore 16:18 → 同一/別processの
  retryが約30分 `NotReady`（process再起動 16:27/16:41）→ 16:49開始の新規processでpreview到達。
  理由コードは記録されていない（#172より前のbuildのため）。
- **episode観測後に行為変更を持つhead**（`git log` で日付確認済み）:
  - `2d811b701c`（PR #158、#155 fix: QSB workspace cell予約。2026-08-27 21:22）
  - `6fd276b50d`（PR #157、#156 fix: hotseat tokenless writer deferral。2026-08-27 22:21。
    #155実装とのmerge `fd3dad799d` が同一日 22:28）
  - `50ddb86148`（PR #184、#172観測surface。2026-08-31 18:57。行為変更なし）
  - `667e8915f2`（PR #186、#185 fix。2026-09-01 10:18。現行main head）
  すべて `74c2156767` の子孫である（`git merge-base --is-ancestor` で確認）。
- **gateの状態機械と観測可能性**（[ReadinessGate.kt:26-77](../../lawnchair/src/app/lawnchair/organizer/application/protocol/ReadinessGate.kt)）:
  per-processのin-memory state。`IDLE → RECONCILING → READY|FAILED`。`composeManualFullOrganizationInput`
  は `IDLE`/`RECONCILING` をどちらも `NotReady(ReconciliationPending)` に潰し、`FAILED` のみ
  `ReconciliationFailed` を返す（[LayoutApplicationModule.kt:115-123](../../lawnchair/src/app/lawnchair/organizer/application/protocol/LayoutApplicationModule.kt)）。
  **したがってjournal理由コードから区別できるのは「reconciliation未完了(`IDLE`/`RECONCILING`
  いずれか) / reconciliation失敗 / gate通過後のcomposer失敗」の3区分のみであり、`IDLE` と
  `RECONCILING` の区別は既存surfaceでは観測不能である**。
- **model readinessの観測可能性**: model load成功の専用logは存在しない。`LawnchairApp.kt:247`
  に「Organizer startup reconciliation began without a completed model load」（30秒timeout時の
  error log）のみがある。またreconciliation自体、recovery storeが空ならrecord単位の
  `RESTART_RECONCILED` が出ないまま `Clean` で終了し得る（ZIP restoreはorganizer recovery
  storeをrestoreしないため、この経路が普通にあり得る）。model readinessは「timeout failure行の
  有無」と「理由コードがreconciliation系でないことによるgate通過の間接確認」でのみ観測できる。
- **ZIP restore経路の特徴**（[LawnchairBackup.kt:61-93](../../lawnchair/src/app/lawnchair/backup/LawnchairBackup.kt)）:
  `restore()` は #58のBACKUP_RESTORE lease下で、`RestoreDbTask.prepareForRawFileRestore` →
  launcher DB directoryの `deleteRecursively()` → `DeviceGridState` をprefsへ書込み →
  archive読込（`favorites` DB、prefs、prefs DB、datastoreの4 fileのみ。organizer recovery
  store / diagnostics journalは含まない）→ `RestoreDbTask.performRestore` +
  `reloadAfterRestore`。Nova restoreと異なり、grid/prefはarchive由来値へ全置換される。
  backup version gateにより新buildが旧archiveを拒否する可能性がある（拒否された場合は
  specのopen分岐どおり調査を止めて報告する）。
- **#150で訂正済みの事実**: episodeは「恒久的/DB状態に安定して付随」ではなく
  「複数process・約30分継続し、後のfresh processで解消」である（Issue #150 comment
  2026-08-26 08:46Z）。
- **Nova restoreの類似症状の根因は確定済み**: #172 AC-3 assessmentにより
  `INPUT_NOT_READY / CAPTURE_INVALID`（5x5 gridのQSB行予約とNova import itemの重複 →
  `RowManifestCodec` のrequire）で、fixは#185で実装済み（trigger regression testも#185が
  所有）。ただしNova経路とZIP経路は入力source（converter vs raw DB+prefs全置換）と状態
  初期化が異なり、置換証拠にはならない。
- 推測（未確認）: 当時の30分窓の候補は (a) gateのreconciliation未完了/失敗の持続、
  (b) capture/manifest系の失敗、(c) bundle/override/evidence sourceの読取失敗、
  (d) model読込/再読込の未完了、の組み合わせ。H0–H2に理由コードのsurfaceはないため、
  区別はreplayの限定（どのheadで消えるか）とH3以降の理由コードでのみ可能になる。

## Design

### Modules and interfaces

本planの主要作業は**同一入力でのhistorical replay、観測・記録、因果変更の限定、
（既存testがtriggerをカバーしない場合の）1 test追加**であり、生産コードの変更を
前提としない。変更するのは次のみ。

| Module | 変更 | 備考 |
|---|---|---|
| `docs/assessment/issue-153-zip-restore-notready-root-cause.md` | 新規（redacted assessment） | archive作成条件、head×結果表、attempt/process境界表、理由コード、因果変更の限定根拠、owning seam、#150との関係判定 |
| owning seam（分岐で確定） | deterministic test追加（既存testがtriggerをカバーしない場合のみ） | 実ZIP archiveは解凍せず、schema-level fixtureで同じ挙動を固定する。fixing変更の既存regression test（例: #155のQSB予約fixture群）がtriggerを実際にカバーしている場合はそれを特定・linkし、追加しない |
| spec status / Issue #153 | status更新とAC mappingコメント | 承認時に `accepted`、実行完了時にassessmentへ `implemented` を記録 |

- **同一入力のarchive**: H0 head `74c2156767` のdebug buildで、元episodeと同じsynthetic/
  default workspaceからarchiveを1回作成し、`/tmp/issue153/` 配下に保存する。全headでこの
  同一fileを入力とする（P2対応。「current mainで作ったarchive」では #155/#156/#185がworkspace
  representationへ与えた影響とtrigger生成の有無を分離できないため）。
- **replay ladder**: H0 = `74c2156767` → H1 = `2d811b701c`（#155）→ H2 = `6fd276b50d`
  （#156）→ H3 = `50ddb86148`（#172観測surface、行為変更なし）→ H4 = `667e8915f2`
  （#185/現行main）。各headはdebug buildとしてcheckout→assemble→installして観測する。
  step間に他の行為変更を発見した場合は中間headを追加してよい（assessmentに理由を記録）。
  注: H2とH3の間には#150の本体fix（PR #160 `8740f8c136`）も入る。A6後のbarrierであり
  restore後の初回compose窓には関与しない見込みだが、結果がこれと矛盾した場合は中間headで
  境界を確定する。
- **観測粒度（既存surfaceのみ）**: attempt結果は「preview到達 / input unavailable」で分類。
  H3以降はinput unavailableなattemptの理由コード区分を公式export経由で取得する。
  gateの `IDLE`/`RECONCILING` は観測不能のため区別せず、
  `RECONCILIATION_PENDING` / `RECONCILIATION_FAILED` / その他のcomposer code の3区分を
  使う。model readinessはtimeout failure行（`LawnchairApp.kt:247`）の有無とgate通過からの
  間接確認のみ記録する（P1-2対応。production instrumentationは追加しない）。
- **journal内容の取得は公式export surfaceに統一**（P2対応）: Settings → Home screen →
  Organizer diagnostics → Export organizer diagnostics（SAF picker → Downloads等 → adb pull）。
  #104 evidenceと同じ手順である。private journal fileの内容を `run-as` で読むことはしない
  （存在・サイズ確認すら行わない。exportで十分である）。#172が定義するexport手順を不変のまま
  使う。
- **停止条件**（P2対応）: 各headの観測は、(a) 解消（最初のpreview到達attempt。elapsedと
  process世代を記録）または (b) 持続の確定（restore完了後40分以上 かつ 3以上のfresh processで
  それぞれ1回以上のattemptがすべてinput unavailable）の早い方で終える。元episodeが約30分
  だったことに対する余裕をもった有限窓であり、無期限観測はしない。
- **hash規約**（P2対応）: assessmentに記載可能なhashはコード識別のみ（head SHA、build識別子、
  APKのSHA-256などコード成果物の識別）。layout・DB行・archive・journal内容由来のhash/digestは
  §7のNever分類と同様に禁止する。
- test追加が生じる場合の配置: 限定された因果変更が示す機構に従う。capture/manifest系なら
  `RowManifestCodec` / `OrganizationInputComposer` 系のfixture test、gate/reconciliation系なら
  `ReadinessGate` / `LayoutApplicationModule` 系のcontract testへ置く。どちらも既存test
  patternの延長であり、新しいseam・adapterは作らない（AGENTS.md設計規約）。

### Data flow

```text
[archive作成] H0 (74c2156767) debug build: synthetic/default workspace → Create backup
              → /tmp/issue153/ に保存（全head共通入力）

[replay: 各head Hn]
  checkout → assemble debug → install
  → pm clear → launcher起動 → Restore backup (同一archive)
  → 同一process内retry / process再起動を交えたretry（停止条件まで）
  → 各attempt: 結果分類（preview到達 / input unavailable）
      H0–H2: 症状の有無・時刻・process世代のみ（理由コードのsurfaceなし）
      H3以降: journal export（INPUT_NOT_READY {family, code}）+ logcat
              （capture失敗時は exceptionClass 行）
  → 解消 or 持続（40分以上かつ3 fresh process）で終了

[限定]
  最後の失敗head → 最初の成功head の間の変更 → 因果変更 → owning seam
  → triggerのdeterministic test（既存testの特定/検証、または追加）
```

### Alternatives rejected

- **bounded non-reproductionを完了条件とする**: Issue #153本文が「root cause特定 +
  owning seamのdeterministic test」を明示的に要求するため、受入条件そのものの緩和になる
  （P1-1）。これを行う場合は先にIssue本文のAcceptanceを変更する必要があり、本specでは
  行わない。非再現の場合もhistorical replayで因果変更を限定し、owning seam/testまで到達する。
- **current mainで新規にarchiveを作り直す**: 元episodeの入力（`74c2156767` のdefault
  synthetic workspace）を再現する保証がなく、「defectが直った」のか「trigger入力が生成
  できなくなっただけ」のか区別できない（P2-1）。H0で作成した同一archiveを使う。
- **production codeへの臨時instrumentation追加 / gate状態・model loadの直接観測**:
  `IDLE` と `RECONCILING` はcomposer結果では区別できず、model load成功logも存在しない。
  これらを要求するとACが測定不能になる（P1-2）。観測可能な3区分と間接確認に落とし、
  不要な一時変更を生産pathへ入れない。
- **`run-as` でのprivate journal読取**: #172がexport手順を不変として定義しており、plan内で
  「存在確認のみ」と「exportでのevidence取得」が矛盾していた（P2-3）。公式export surfaceに
  統一する。
- **fix受入はblocking dependency**（P1対応）: root causeが現行mainで未解決のproduction
  defectである場合、focused fix Issueの起票（spec-first）に加えて、**fixの実装完了と
  fix側でのnon-write振る舞い・§7 redaction維持の検証完了まで本Issueをcloseしない**。
  責務境界: AC-4は本IssueのPR（調査・test・docs）がinvariantsを壊さないことを、AC-6は
  fix側の実装と検証の受入を所有する。fixの実装はfocused fix IssueのPRで行われるため、
  本planのbranch（docs/issue-153-spec-plan）のPRは #153 を `Closes` せず、fix完了確認を
  含む最終PR（またはdocs-only最終コミット）がcloseを担う。
- **episodeのfixを本IssueのPRへ吸収しない**: Issue本文の制約（`NotReady` はnon-write、
  diagnostics schema変更はaccepted specが必要、#150との共有時は依存を先に記録）と
  #172→#185の前例に従う。fix実装自体はfocused fix Issueが所有するが、**その完了確認は
  本Issueのclose条件である**（「起票だけ」でcloseしない）。
- **無期限の観測による「持続」判定**: 判定が実行者依存になる（P2-2）。40分以上かつ3以上の
  fresh processという有限停止条件を固定する。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `docs/assessment/issue-153-zip-restore-notready-root-cause.md` | 実測記録（新規） | 調査成果物の正本置き場（#171/#172 assessmentと同じ場所） |
| `specs/153-zip-restore-notready-root-cause/spec.md` | status/history更新 | 承認・完了の記録 |
| 本plan.md | status更新、分岐結果の反映 | 実行との一致を保つ |
| owning seam test（triggerが既存testで未カバーの場合のみ） | deterministic test追加 | テスト規約: 観測済みtriggerの固定。fixが別Issueでも、因果変更のregression固定は本Issueで確保する |
| Issue #153 | AC mapping + #150関係コメント | 正本はGitHub Issue |

## Migration and recovery

- schema/rule migration: none。生産データ・formatへの変更を想定しない。
- failure中のrollback: 該当なし（write系変更なし）。
- release rollback/downgrade: 該当なし（test/docs追加のみ。生産コード変更が生じた場合は
  focused fix Issue側で評価する）。
- backup/restore compatibility: 影響none。実測はbackup/restore機能を消費するのみで、
  変更しない。backup version gateにより旧archiveのrestoreが拒否された場合は、specのopen分岐
  どおり調査を止めてIssue #153へ報告する。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | 各headの実測記録: 公式export surfaceで取得したjournal（`RUN_STARTED` / `INPUT_NOT_READY` event行。H3以降）と `logcat OrganizerDiag:V *:S` のredacted転載、attempt分類表。H0–H2は症状有無・時刻・process世代の記録 | エミュレータ `nunu_qpr2_api36_1`、各headのdebug build `assembleLawnWithQuickstepGithubDebug` |
| AC-2 | assessmentのattempt/process境界表（process世代、elapsed、結果分類、H3以降は理由コード3区分とtimeout行の有無、停止条件の充足記録） | 同上（手動実測） |
| AC-3 | 因果変更の限定根拠（head×結果表）、owning seamの特定、deterministic testのlink/検証記録または追加test + 実行記録 | （test追加時）`./gradlew :lawnchair:testLawnWithQuickstepGithubDebugUnitTest` |
| AC-4 | 本Issue PRの既存organizer test群の無変更通過、§7 negative fixture通過、assessment hash規約の確認記録 | `./gradlew :lawnchair:testLawnWithQuickstepGithubDebugUnitTest` `./gradlew spotlessCheck` `./gradlew assembleLawnWithQuickstepGithubDebug` |
| AC-5 | assessmentの#150関係節 + Issue #153へのコメント | — |
| AC-6 | （未解決defect時）focused fix Issueのlink + fix PRのtest/evidenceによるnon-write・redaction維持の検証記録を確認し、完了後に本Issueをclose。（解消済み時）中間変更・seam・既存testのmapping + invariantsカバレッジ確認記録 | — |

含める観点: エミュレータでのintegration観測（AC-1/2）、deterministic test（AC-3、該当時）、
privacy negative確認（AC-4: assessment記載とexportの§7 non-containment目視+既存fixture）。
performance観点は本Issueの範囲外（30分窓の計測は観測記録として行うが、性能最適化はしない）。

リスク評価: 生産コード変更を含まないため `risk: layout-data` / `risk: migration` には
該当しない。実測は `pm clear` を含むためエミュレータ上で隔離して実施する。

## Documentation updates

- [x] spec status/history（承認時に `accepted`、完了時にassessment linkと `implemented`）
- [ ] CONTEXT.md — 不要（新domain語なし。ladder/episodeはspec内の手続き・証拠識別子）
- [ ] DESIGN.md — 不要（system structure変更なし）
- [ ] docs/engineering/organizer-diagnostics.md — 原則不要（closed集合・versioningに触らない。
      観測結果が新code追加を要求する場合のみ、focused fix Issueのspecで扱う）
- [ ] ADR — 原則不要（判断の追加がない。分岐結果が変更困難な設計判断を生んだ場合のみ再評価）
- [ ] AGENTS.md — 検証command追加なし

## Execution checklist

- [ ] Spec review・承認（statusを `accepted` へ更新）。
- [ ] H0準備: `74c2156767` をcheckoutし、debug buildをassemble・install。
      synthetic/default workspaceでCreate backupを実行し、archiveを `/tmp/issue153/` に保存。
      baselineとしてrestore前の同一head環境でorganizer runがpreview到達することをjournalで確認。
- [ ] H0実測: `pm clear` → launcherをHOMEにして起動 → Restore backup（同一archive）→
      同一process内retry → process再起動を挟んだretry（停止条件まで）。
      元episodeと同型の挙動（NotReady窓→解消）を確認する。再現しない場合は入力差分を特定し、
      Issue #153へ報告してから進む。
- [ ] H1–H4を同手順で実測（同一archive、各head debug build）。各headのattempt分類・
      時刻・process世代を記録。H3以降は理由コード3区分とtimeout行の有無も記録。
- [ ] head×結果表から因果変更を限定し、owning seamを特定する。
- [ ] triggerのdeterministic testを確保する: fixing変更の既存regression testを特定・検証する
      （triggerを実際にカバーしていることを確認）、またはschema-level fixtureで追加testを
      作って通す。
- [ ] root causeの現行mainでの状態（未解決/解消済み）を判定し、未解決ならfocused fix Issueを
      起票（spec-first）。**fixの実装・検証が完了するまで本Issueをcloseしない。** 解消済みなら
      対応する変更・seam・既存testのmappingとinvariantsカバレッジ確認をassessmentに記録。
- [ ] #150との関係（shared/independent）をassessmentに記録し、Issue #153へコメントする。
- [ ] 既存test群（`./gradlew :lawnchair:testLawnWithQuickstepGithubDebugUnitTest`）・
      `spotlessCheck`・debug buildを実行し、結果をPRへ記録する。
