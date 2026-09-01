# Implementation Plan: ZIP restore後のmanual organization NotReadyの根因特定

> Issue: #153
> Spec: [spec.md](./spec.md)
> Status: draft

## Current evidence

確認済みの事実（現行main `667e8915f2` 時点）:

- **観測surfaceは実装済み**: `ManualOrganizationRun.start()` の `NotReady` 分岐は
  `INPUT_NOT_READY`（`ErrorFamily.INPUT_READINESS` + `InputCompositionCode`）terminal eventを
  emitする（[ManualOrganizationRun.kt:213](../../lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt)、
  `emitInputNotReady` 同631-）。capture失敗は `DiagnosticsLogger` のtyped API
  （`Class<out Throwable>` のみ、debug build限定）でlogcatに出る（PR #184、#172 AC-1/2/4/5/6/7完了）。
  実測受入（ZIP restore経路での `INPUT_NOT_READY` 取得）はされていない。
- **当時のepisode観測**（[issue-104-105-106 device evidence](../../docs/assessment/evidence/issue-104-105-106-device-evidence.md)
  「Observation recorded during this check」）: ZIP restore 16:18 → 同一/別processのretryが
  約30分 `NotReady`（process再起動 16:27/16:41）→ 16:49開始の新規processでpreview到達。
  理由コードは記録されていない（#172より前のbuild）。
- **ZIP restore経路の特徴**（[LawnchairBackup.kt:61-93](../../lawnchair/src/app/lawnchair/backup/LawnchairBackup.kt)）:
  `restore()` は #58のBACKUP_RESTORE lease下で、`RestoreDbTask.prepareForRawFileRestore` →
  launcher DB directoryの `deleteRecursively()` → `DeviceGridState` をprefsへ書込み →
  archive読込（`favorites` DB、prefs、prefs DB、datastoreの4 fileのみ。organizer recovery
  store / diagnostics journalは含まない）→ `RestoreDbTask.performRestore` +
  `reloadAfterRestore`。Nova restoreと異なり、grid/prefはarchive由来値へ全置換される。
- **gateの状態機械**（[ReadinessGate.kt:26-77](../../lawnchair/src/app/lawnchair/organizer/application/protocol/ReadinessGate.kt)）:
  per-processのin-memory state。`IDLE → RECONCILING → READY|FAILED`。restore直後のprocessでは
  startup reconciliation完了まで `composeManualFullOrganizationInput` は
  `NotReady(ReconciliationPending)` を返し、`FAILED` なら `ReconciliationFailed` を返す
  （[LayoutApplicationModule.kt:115-123](../../lawnchair/src/app/lawnchair/organizer/application/protocol/LayoutApplicationModule.kt)）。
  gateはstore generation単位でfreshになるため、process再起動のたびにreconciliationが
  再実行される。
- **#150で訂正済みの事実**: episodeは「恒久的/DB状態に安定して付随」ではなく
  「複数process・約30分継続し、後のfresh processで解消」である（Issue #150 comment
  2026-08-26 08:46Z）。
- **Nova restoreの類似症状の根因は確定済み**: #172 AC-3 assessmentにより
  `INPUT_NOT_READY / CAPTURE_INVALID`（5x5 gridのQSB行予約とNova import itemの重複 →
  `RowManifestCodec` のrequire）で、fixは#185で実装済み。ただしNova経路とZIP経路は
  入力source（converter vs raw DB+prefs全置換）と状態初期化が異なり、置換証拠にはならない。
- 推測（未確認）: 当時の30分窓の候補は (a) gateの `RECONCILING`/`FAILED` の持続、
  (b) capture/manifest系の失敗（#185と同型のQSB行重複を含む）、(c) bundle/override/evidence
  sourceの読取失敗、(d) model読込/再読込の未完了、の組み合わせ。production証拠が無いため
  特定できない——これが本planの実測（AC-1/2）の動機である。

## Design

### Modules and interfaces

本planの主要作業は**観測・記録・(分岐に応じた)1 test追加**であり、生産コードの変更を
前提としない。変更するのは次のみ。

| Module | 変更 | 備考 |
|---|---|---|
| `docs/assessment/issue-153-zip-restore-notready-root-cause.md` | 新規（redacted assessment） | 実測手順、attempt/process境界表、理由コード、root cause分析（または非再現のbounded証拠）、#150との関係判定 |
| owning seam（分岐で確定） | deterministic test追加のみ | 再現時のみ。実ZIP archiveは解凍せず、schema-level fixtureで同じtyped result（同じ `NotReady` code）を固定する |
| spec status / Issue #153 | status更新とAC mappingコメント | 承認時に `accepted`、実行完了時にassessmentへ `implemented` を記録 |

- 観測は#172実装済みのsurfaceのみを使用する: journal/export（`INPUT_NOT_READY` event行）、
  `logcat OrganizerDiag:V *:S`（debug build。`phase=CAPTURE exceptionClass=` 行を含む）、
  `run-as` レベルのfile存在・サイズ確認（journal初期化の確認のみ。内容は読まない）。
- gate状態・model読込完了の判定は、既存のdebug logcat遷移行（reconciliation完了、model load）
  とjournal理由コード（`RECONCILIATION_PENDING` / `RECONCILIATION_FAILED` とその他のcodeの区別）から
  取得する。production codeへのinstrumentation追加は行わない。
- test追加が生じる場合の配置: 観測codeがcapture/manifest系なら `RowManifestCodec` /
  `OrganizationInputComposer` 系のfixture test、gate系なら `ReadinessGate` /
  `LayoutApplicationModule` 系のcontract testへ置く。どちらも既存test patternの延長であり、
  新しいseam・adapterは作らない（AGENTS.md設計規約: 仮想interface/adapterを増やさない）。

### Data flow

```text
[実測] pm clear → (事前作成済みarchiveを) Restore backup
  → restore直後process: gate=IDLE/RECONCILING → model load → startup reconciliation → READY|FAILED
  → 同一process内retry / process再起動(16:27/16:41相当) / 新規process(16:49相当)
  → 各attempt: journal RUN_STARTED → (NotReadyなら) INPUT_NOT_READY {family, code}
              + (capture失敗時のみ) logcat exceptionClass行
  → 解消時: preview到達 (CAPTURED/PLANNED/…) をjournalで確認
[test] schema-level fixture → composer/gateの呼出し → 観測と同じ NotReady code をassert
```

### Alternatives rejected

- **実deviceのarchiveで実測**: archiveは個人layout内容を含み得るcommit対象外データであり、
  再現性もない。同一エミュレータ上で作成したarchiveで手順を再現する。
- **production codeへの臨時instrumentation追加**: #171 investigationで用いた手段だが、
  今回は#172の観測surfaceが既に理由コードとcapture例外classを運ぶため不要。不要な一時変更を
  生産pathへ入れない。
- **episodeのfixを本Issueへ吸収**: Issue本文の制約（`NotReady` はnon-write、diagnostics
  schema変更はaccepted specが必要、#150との共有時は依存を先に記録）と #172→#185の前例
  （observed surface issueとfocused fix issueの分離）に従い、fixはfocused fix Issueへ分割する。
- **非再現を「#185で解消済み」と断定して終了**: ZIP経路のepisode観測は #155/#156/#172/#185
  より前のbuildであり、中間変更のどれが効いたかは証拠がない。非再現の場合はbounded証拠と
  対応関係の記録（断定可能な範囲）をもって受入とし、断定できない部分を明示する。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `docs/assessment/issue-153-zip-restore-notready-root-cause.md` | 実測記録（新規） | 調査成果物の正本置き場（#171/#172 assessmentと同じ場所） |
| `specs/153-zip-restore-notready-root-cause/spec.md` | status/history更新 | 承認・完了の記録 |
| 本plan.md | status更新、分岐結果の反映 | 実行との一致を保つ |
| owning seam test（分岐時のみ） | deterministic test追加 | テスト規約: 修正は失敗を再現するtestを伴う。fixが別Issueでも、観測済みtyped resultの固定は本Issueで行う |
| Issue #153 | AC mapping + #150関係コメント | 正本はGitHub Issue |

## Migration and recovery

- schema/rule migration: none。生産データ・formatへの変更を想定しない。
- failure中のrollback: 該当なし（write系変更なし）。
- release rollback/downgrade: 該当なし（test/docs追加のみ。生産コード変更が生じた場合は
  focused fix Issue側で評価する）。
- backup/restore compatibility: 影響none。実測はbackup/restore機能を消費するのみで、
  変更しない。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | エミュレータでの実測記録: `run-as` journal export（`INPUT_NOT_READY` event行）、`logcat OrganizerDiag:V *:S`（redacted転載）。`docs/assessment/issue-153-zip-restore-notready-root-cause.md` | エミュレータ `nunu_qpr2_api36_1`、debug build `assembleLawnWithQuickstepGithubDebug` |
| AC-2 | assessmentのattempt/process境界表（gate状態、model読込、理由コード、解消/持続と状態差） | 同上（手動実測） |
| AC-3 | （再現時）owning seamのdeterministic test + 実行記録。（非再現時）bounded証拠節 + 中間変更対応記録 | （再現時）`./gradlew :lawnchair:testLawnWithQuickstepGithubDebugUnitTest` |
| AC-4 | 既存organizer test群の無変更通過、§7 negative fixture通過、`spotlessCheck`、debug build | `./gradlew spotlessCheck` `./gradlew assembleLawnWithQuickstepGithubDebug` |
| AC-5 | assessmentの#150関係節 + Issue #153へのコメント | — |
| AC-6 | （defect確認時）focused fix Issue link。（非確認時）自動充足のassessment記載 | — |

含める観点: 実機相当でのintegration観測（AC-1/2）、deterministic test（AC-3、分岐時）、
privacy negative確認（AC-4: assessment記載とexportの§7 non-containment目視+既存fixture）。
performance観点は本Issueの範囲外（30分窓の計測は観測記録として行うが、性能最適化はしない）。

リスク評価: 生産コード変更を含まないため `risk: layout-data` / `risk: migration` には
該当しない。実測は `pm clear` を含むためエミュレータ上で隔離して実施する。

## Documentation updates

- [x] spec status/history（承認時に `accepted`、完了時にassessment linkと `implemented`）
- [ ] CONTEXT.md — 不要（新domain語なし）
- [ ] DESIGN.md — 不要（system structure変更なし）
- [ ] docs/engineering/organizer-diagnostics.md — 原則不要（closed集合・versioningに触らない。
      観測結果が新code追加を要求する場合のみ、focused fix Issueのspecで扱う）
- [ ] ADR — 原則不要（判断の追加がない。分岐結果が変更困難な設計判断を生んだ場合のみ再評価）
- [ ] AGENTS.md — 検証command追加なし

## Execution checklist

- [ ] Spec review・承認（statusを `accepted` へ更新）。
- [ ] 事前準備: エミュレータでdebug buildをinstallし、Create backupでarchive（`lawnchairbackup`）を
      作成。baselineとしてorganizer runが `Ready`（preview到達）であることをjournalで確認。
- [ ] 実測: `pm clear app.lawnchair.debug` → launcherをHOMEにして起動 → Restore backupで
      archive restore → 同一process内retry → process再起動を挟んだretry（少なくとも2世代） →
      さらに後の新規processでのretry。各attemptのjournal exportとlogcatを保存（`/tmp` 配下）。
- [ ] episodeの再現有無と理由コードを確定し、assessmentにattempt/process境界表として記録。
- [ ] （再現時）解消時点または持続の確定まで観測を続け、状態差をschema-levelで記録。
- [ ] root causeをowning seamで特定し、（再現時）deterministic testを追加して通す。
      （非再現時）bounded証拠と中間変更対応をassessmentに記録する。
- [ ] production defectの有無を判定し、確認時はfocused fix Issueを起票（spec-first）。
- [ ] #150との関係（shared/independent）をassessmentに記録し、Issue #153へコメントする。
- [ ] 既存test群・`spotlessCheck`・debug buildを実行し、結果をPRへ記録する。
