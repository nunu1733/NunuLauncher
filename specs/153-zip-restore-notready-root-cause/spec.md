---
issue: "#153"
status: draft
requirements:
  - ZIP-NOTREADY-FOLLOWUP
updated: 2026-09-01
---

# ZIP restore後のmanual organization NotReadyの根因特定と観測の確定

要件 `ZIP-NOTREADY-FOLLOWUP` は [spec #150](../150-manual-organization-a7-verification/spec.md)
が追跡するfollow-up要件であり、その所有者である本Issueの正本specとして本書を置く。

## Problem

[Issue #153](https://github.com/nunu1733/NunuLauncher/issues/153) の記録したepisode:
Lawnchair ZIP backupを `pm clear` 済みのapp stateへrestoreした後、manual全体整理の
`composeFullOrganization()` が複数process・約30分にわたり理由不明の `NotReady`
（"input unavailable"）を返し、さらに後の新規processで解消した
（[issue-104-105-106 device evidence](../../docs/assessment/evidence/issue-104-105-106-device-evidence.md)
「Observation recorded during this check」。restore 16:18、retryを跨ぐprocess再起動
16:27/16:41、解消 16:49）。失敗側のsub-check（`capture-invalid` / `capture-unrepresentable`
/ gate状態等）は当時どこにも記録されず、on-deviceで識別できなかった。

この観測性の欠落自体は [spec #172](../172-input-unavailable-diagnostics/spec.md)
（`implemented`）により解消済みである: `INPUT_NOT_READY` terminal event + `ErrorFamily.INPUT_READINESS`
+ `InputCompositionCode`（17値。#185が `CAPTURE_RESERVED_OVERLAP` を追加）がjournal/exportに落ち、
capture失敗はdebug buildのlogcatにexception class名のみ出る。

episodeのroot causeは未確定のままである。当時の観測は #155（QSB予約の相関再読込削除）、
#156（hotseat tokenless writer deferral）、#172（上記観測surface）、#185（QSB行重複itemの
interop意味論）より前のbuildで行われたため、(a) 現行mainでepisodeが再現するか、
(b) 再現する場合に観測される理由コードとowning seamは何か、(c) 再現しない場合にどの
中間変更とどの状態差がepisodeを説明するか、のいずれも未確定である。Nova restore経路
（#168/#171手順）のpost-restore失敗は #172 AC-3 assessment により
`CAPTURE_INVALID` / QSB行重複で根因確定し #185 で解消されたが、Lawnchair ZIP restore
（ cleared app stateへの全置換。archiveはlauncher DB + prefs のみで、organizer recovery
store / diagnostics journalを含まない）は入力source・状態初期化の異なる別経路であり、
その証拠で置換できない。

## Outcome

記録されたZIP restore手順を現行main（debug build、`nunu_qpr2_api36_1` エミュレータ）で
実行すると、episodeが再現するかどうかと、再現時のprivacy-safeな理由コードが
[spec #172](../172-input-unavailable-diagnostics/spec.md) の承認済みsurface（journal/export、
debug logcatのcapture例外class行）を通じて取得できる。process/reconciliation/model
readinessの境界がattempt・process単位でassessmentに記録され、episodeが継続する場合は
その解消時点の状態差まで含めてboundedされる。root causeはowning seamで特定され、
観測と同じtyped resultを決定論的に再現するtestで固定される。production defectが確認された
場合はfocused fix Issueへ分割される（#172→#185の前例どおり）。#150との関係が
共有/独立として証拠付きで明示される。

## Scope

- 記録済み手順（`pm clear` → 隔離app state → Create backupで作成したarchiveを
  Settings → ⋮ → Restore backup でrestore → 同一process内retry + 複数processにわたる
  retry）を現行main debug buildで実行し、journal/exportとlogcatから理由コード・gate状態・
  model読込完了を含むevidenceを収集する。archiveは同一エミュレータ上で事前に作成する
  （実deviceのarchiveはcommitされておらず、個人layout内容を含み得るため再利用しない）。
- episodeが再現する場合: 解消（後続processでのpreview到達）または持続のどちらの結果も、
  失敗・解消時点のworkspace/DB/pref/model/recovery store状態の差分（schema-level）とともに
  assessmentへ記録する。
- 観測された理由コードに基づくroot causeの特定と、owning seamでの決定論的testの追加。
  testは実ZIP archiveを解凍せず、schema-levelに合成した状態（grid/pref/favoritesのfixture）
  で同じtyped result（同じ `NotReady` code）を固定する。
- production defectが確認された場合のfocused fix Issue分割。本Issueはfixを吸収しない。
- [docs/assessment/](../../docs/assessment/) へのredacted assessment記録、および
  #150との関係（shared/independent）の明示。

## Non-goals

- readiness判定・fail-closed動作・composer制御フローの変更（正本は
  [spec #83](../83-production-organization-input-sources/spec.md)）。
- `InputReadinessReason` / `InputCompositionCode` の意味論・割当ての変更、および
  diagnostics schema（`RunEvent` field集合、schemaVersion）の変更。closed code集合の
  新定数追加が必要になった場合も、#172契約（§3/§8）の「 `ErrorEntry.code` への定数追加は
  schema変更なしで可」に従う範囲に限り、その適用はfocused fix Issueが所有する。
- ZIP restore自体（`LawnchairBackup` / `RestoreDbTask` / `DeviceGridState`）の動作変更、
  backup format・allowlistの変更。#58のBACKUP_RESTORE lease契約は不変である。
- planner/apply/recoveryの契約変更。`NotReady` はnon-writeであり続け、planner呼出し、
  recovery point作成、layout mutationを一切起こさない。
- Nova restore経路（#168/#185所有）の再検証・再修正。
- 実deviceのarchive・journal等のraw evidenceのcommit。raw evidenceはcommitしない
  （assessmentはhash/count/opaque ID/closed code/schema-level messageのみ記載する）。

## Domain language

- **ZIP restore episode**: 本Issueが追跡する、ZIP backup restore後の複数process・約30分の
  `NotReady` 窓とその後の解消の観測記録。診断契約の用語ではなく、このspec/assessment内の
  証拠識別子である。`CONTEXT.md` への追加は不要である。

## Behavior scenarios

### Scenario: ZIP restore後のNotReadyが理由コード付きで観測される

Given `pm clear` 済みのapp stateへ記録手順でZIP restoreを実施し、launcher modelが読込済みである。
When 同一process内でmanual full organizationのstartを実行し、それが `NotReady` で終わる。
Then journalに `RUN_STARTED` に続く `INPUT_NOT_READY`（`family=INPUT_READINESS`、
`code=<InputCompositionCode定数名>`）が追記される。
And capture失敗に起因する場合のみ、debug buildのlogcatに `phase=CAPTURE
exceptionClass=<class simple名>` の行が出る（raw message・stack traceは出ない）。
And UIのcopy区分は#172の区分に従い、planner呼出し・checkpoint・apply・recovery point・
Launcher DB mutationはすべて0回である。

### Scenario: process/reconciliation/model readiness境界の記録

Given ZIP restore直後から、同一process内retryとprocess再起動を交えながら複数processにわたりretryを実施する。
When 各attemptのevidenceを収集する。
Then 各processについて、attempt時点のgate状態（`READY` / `RECONCILING` / `FAILED` /
`IDLE`）、model読込完了の有無、recovery storeの状態が、journalの理由コードと紐づけられて記録される。
And episodeが解消したprocessでは、直前の失敗processと比較した状態差（workspace内容、
DB file、pref、model load、process世代）がschema-levelで記録される。

### Scenario: root causeの特定と決定論的test

Given 観測された理由コードと境界evidence。
When root causeをowning seam（composer/capture、`ReadinessGate`/reconciliation、
policy/source整合、model/reloadのいずれか）で特定する。
Then 観測と同じtyped resultを、決定論的なfixture/contract test（必要ならinstrumentation）で
owning seamに固定する。testは実ZIP archiveを解凍しない。
And production defectが確認された場合、fixはfocused fix Issueへspec-firstで分割され、
本IssueのPRは生産コードのfixを含まない。

### Scenario: 現行mainで再現しない場合

Given 記録手順を現行main debug buildで実行しても、restore後の全processでorganizerが
preview（または既存契約のtyped結果）へ到達する。
When 同一workspace状態でのcompose成功をjournalで確認する。
Then gate状態・capture成功・bundle/override/evidence各sourceの読取結果を含むbounded証拠が
assessmentに記録される。
And episodeを説明し得る中間変更（#155/#156/#172/#185）との対応関係が、断定できる範囲で
記録され、断定できない部分は未確定として明示される。
And 生産コードの変更は行われない。

### Scenario: 収集evidenceのprivacy不変

Given 収集された全evidence面（journal、export、logcat、assessment記載）。
When organizer-diagnostics契約§7のNever分類検査を行う。
Then package名、component名、座標、item列挙、digest、raw exception message、backup
payload内容は一切現れない。
And assessment記載はhash、count、opaque ID、closed code、schema-level messageに限定される。

### Scenario: #150との関係の明示

Given root causeのevidence（または非再現のbounded証拠）。
When #150（A7 completion barrier）との共有/独立を判定する。
Then 判定と根拠がassessmentに記録され、Issue #153へコメントされる。
And 独立と判定する場合、#150の所有するA6後reload完了境界（`LayoutWriterPort` /
`OrganizerModelReloadAdapter` seam）とは異なるseamであることを根拠に含める。

## Data and state

- 生産データへの書込みはnone。journalへの書込みは#172実装済みの `INPUT_NOT_READY`
  terminal経路のみであり、retention・fail-open性は既存契約のままである。
- assessment（redacted）のみを `docs/assessment/issue-153-zip-restore-notready-root-cause.md`
  へcommitする。raw evidence（logcat全体、journal export、DB/DB listing、archive）は
  `/tmp` 配下に置き、commitしない。
- 決定論的testは合成fixture（grid/pref/favorites状態をschema-levelに構成）を使用し、
  実ZIP archive・実端末固有設定・個人データをfixtureへ取り込まない
  （AGENTS.mdの「生成物、credential、端末固有設定、個人データをcommitしない」に従う）。
- layout DB、recovery store、backup/restore経路への影響はnone。migration不要であり、
  source rollbackで元に戻る（test/docs追加のみを想定する）。

## Permissions, privacy, and security

- permission・network・telemetryの追加はnone。
- 観測surfaceは#172が承認した範囲（journal/exportのclosed code、debug build限定のcapture
  例外class行）に限定され、§7 Never分類は不変である。episodeの診断に例外message・backup
  payload内容・layout内容を使用しない。

## Accessibility and localization

- 新しいuser-facing文字列・UIは追加しない。既存の `InputUnavailable` 画面（focus、
  `liveRegion`、retry操作、#172のcopy区分）は不変である。

## Acceptance criteria

- [ ] **AC-1 — ZIP restore経路での理由コード取得:** 記録手順を現行main debug buildで実行し、
  restore後の各 `NotReady` attemptがjournal/export上で `INPUT_NOT_READY` +
  `InputCompositionCode` 定数名として取得できる（capture失敗時はdebug logcatの
  exception class行も取得できる）。これは#172の実装受入に加えて、ZIP restore実経路での
  実測による受入確定である。
- [ ] **AC-2 — 境界と解消の記録:** process/reconciliation/model readinessの境界が
  attempt・process単位でassessmentに記録され、episodeが継続する場合は解消時点
  （または持続の確定）までの状態差がbounded記録される。
- [ ] **AC-3 — root causeと決定論的test:** root causeがowning seamで特定され、観測と同じ
  typed resultを決定論的に再現するtestがowning seamに追加される。非再現の場合は、
  同等のbounded証拠と中間変更への対応記録をもって本ACを満たし、test追加は行わない。
- [ ] **AC-4 — non-writeとredactionの不変:** 観測・test追加を通じて `NotReady` のnon-write
  振る舞い（planner呼出し・recovery point作成・layout mutation 0回）と、§7 Never分類
  （journal・export・logcat・assessment）が保たれる。既存organizer test群が無変更で通過する。
- [ ] **AC-5 — #150との関係の明示:** 共有/独立の判定が証拠とともにassessmentと
  Issue #153に記録される。
- [ ] **AC-6 — focused fixの分割:** production defectが確認された場合、spec-firstの
  focused fix Issueが起票され、本IssueのPRにfixを含まない。defectが確認されなかった場合
  （非再現を含む）は本ACは自動的に満たされる。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | エミュレータ `nunu_qpr2_api36_1` debug buildでの実行記録。`run-as` journal export（`INPUT_NOT_READY` event行）と `logcat OrganizerDiag:V *:S` をassessmentに転載（redacted） |
| AC-2 | assessmentのattempt/process境界表（gate状態・model読込・理由コード・解消時点の状態差） |
| AC-3 | owning seamのdeterministic test（fixture/contract、必要ならinstrumentation）。非再現の場合はbounded証拠節。test実行commandは `./gradlew :lawnchair:testLawnWithQuickstepGithubDebugUnitTest`（該当task） |
| AC-4 | 既存organizer unit/contract test群の無変更通過、negative fixture（§7 non-containment）の通過、`./gradlew spotlessCheck` + `./gradlew assembleLawnWithQuickstepGithubDebug` |
| AC-5 | assessmentの#150関係節 + Issue #153への記録コメント |
| AC-6 | focused fix Issueのlink（起票時）。assessmentの分割節 |

## Open questions

なし。分岐（再現する/しない）はいずれもBehavior scenarios・ACで受入条件を確定済みであり、
spec承認の前提となる未決定の製品判断は存在しない。観測結果が上記のどの分岐にも当てはまらない
場合（例: closed code集合で表現できない新しい失敗区分の発見）は、実装を止めてspecを更新する。

## Change history

- 2026-09-01: Issue #153本文、#150の訂正コメント（2026-08-26 08:46Z）とPR #151 evidence
  （issue-104-105-106-device-evidence.md）、#171/#172 assessment、spec #83/#150/#172/#185、
  現行mainのcode事実に基づきdraftを作成。観測surface部分は#172が解消済みのため、本specは
  ZIP restore経路の実測・境界記録・根因特定に範囲を限定した。
