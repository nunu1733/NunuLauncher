---
issue: "#153"
status: accepted
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

episodeのroot causeは未確定のままである。当時の観測は観測head `74c2156767`
（PR #148 merge）で行われ、#155（QSB workspace cell予約。PR #158 merge `2d811b701c`）、
#156（hotseat tokenless writer deferral。PR #157 merge `6fd276b50d`）、#172（観測surface。
PR #184 merge `50ddb86148`）、#185（QSB行重複itemのinterop。PR #186 merge `667e8915f2`、
現行main head）はすべて episode 観測後である。したがって (a) 現行mainでepisodeが再現するか、
(b) 再現する場合に観測される理由コードとowning seamは何か、(c) 再現しない場合にどの変更が
症状を消したか、のいずれも未確定である。Nova restore経路（#168/#171手順）のpost-restore
失敗は #172 AC-3 assessment により `CAPTURE_INVALID` / QSB行重複で根因確定し #185 で解消
されたが、Lawnchair ZIP restore（cleared app stateへの全置換。archiveはlauncher DB + prefs
のみで、organizer recovery store / diagnostics journalを含まない）は入力source・状態初期化の
異なる別経路であり、その証拠で置換できない。

## Outcome

観測head `74c2156767` で作成した同一archiveを入力に、ZIP restore手順を各比較headで
実行することで、episodeがどのhead境界で消える（または現行mainでも残る）かが同一入力で
限定される。reproduceするheadでは、privacy-safeな理由コードが
[spec #172](../172-input-unavailable-diagnostics/spec.md) の承認済みsurface（journal/export、
debug logcatのcapture例外class行）を通じて取得できる。process/reconciliation/model
readinessの境界が、既存surfaceで実際に観測可能な粒度でattempt・process単位のassessmentに
記録され、観測は有限の停止条件で閉じる。限定された因果変更からowning seamを特定し、
そのtriggerに対するregression固定testをowning seamに確保する（fixing変更のtestが既に
存在すればそれを特定・検証し、なければ追加する）。root causeが現行mainで未解決の
production defectである場合、focused fix Issueの**fix実装とnon-write/redaction検証の完了は
本Issueのcloseの前提（blocking dependency）**であり、起票だけでは完了しない。
これにより [Issue #153](https://github.com/nunu1733/NunuLauncher/issues/153) 本文の受入条件
「The fix preserves non-write behavior and existing diagnostics redaction」が、fix側の実装と
検証によって実際に満たされてから本Issueが閉じる。#150との関係が共有/独立として
証拠付きで明示される。

## Scope

- **同一入力のarchive作成（historical input）**: 観測head `74c2156767` のdebug buildで、
  元episodeと同じsynthetic/default workspaceからarchive（`lawnchairbackup`）を1回作成し、
  `/tmp` 配下に保存する（commitしない）。この同一archiveをすべての比較headへの入力とする。
  「current mainで作ったarchive → current mainにrestore」では、#155/#156/#185がarchive作成前の
  workspace representationに影響していた場合に「defectが直った」のか「trigger入力自体を
  生成できなくなっただけ」のか区別できないためである。
- **historical replay ladder**: 少なくとも次のheadを、この順で観測する
  （step間に他の行為変更が入った場合は、境界を特定するために中間headを追加してよい）:
  - H0 = `74c2156767`（episode観測head。元episodeのNotReady再現をまず確認する）
  - H1 = `2d811b701c`（#155 fix: QSB workspace cell予約）
  - H2 = `6fd276b50d`（#156 fix: hotseat tokenless writer deferral）
  - H3 = `50ddb86148`（#172: 観測surface。行為変更を含まない）
  - H4 = `667e8915f2`（#185 / 現行main head）
- 各headでの手順: `pm clear` → 隔離app state → 同一archiveを Settings → ⋮ → Restore backup
  でrestore → 同一process内retryとprocess再起動を交えたretryを、後述の停止条件まで実施。
- **観測粒度（既存surfaceに限定）**: attempt結果は「preview到達 / input unavailable」で分類する。
  H3以降のheadでは、input unavailableなattemptごとに `INPUT_NOT_READY` + `InputCompositionCode`
  を公式export経由で取得し、capture失敗時はdebug logcatのexception class行を取得する。
  H0–H2（#172以前のhead）では理由コードは設計上観測できないため、症状の有無のみを記録する
  （これは欠落ではなく、当時の観測条件の再現である）。
- **有限の停止条件**: 各headの観測は、(a) 解消（最初のpreview到達attempt。elapsed時間と
  process世代を記録）または (b) 持続の確定（restore完了後40分以上経過 かつ 3以上のfresh
  processでそれぞれ1回以上のattemptがすべてinput unavailable）の早い方で終える。
- 限定された因果変更（最後の失敗headと最初の成功headの間の変更）からowning seamを特定し、
  そのtriggerに対するdeterministic testをowning seamに確保する。testは実ZIP archiveを解凍せず、
  schema-levelに合成した状態（grid/pref/favoritesのfixture）で同じ挙動を固定する。
- root causeが現行mainで未解決のproduction defectである場合のfocused fix Issue分割
  （spec-first）。**fixの実装と、fix側でのnon-write振る舞い・既存diagnostics redaction維持の
  検証完了まで本Issueはcloseしない**（Issue本文のfix acceptance「The fix preserves
  non-write behavior and existing diagnostics redaction」の所有権は、focused fix Issueが
  実装・検証の実施者であっても、完了の受入は本Issue側が確認する）。本IssueのPRは
  生産コードのfixを含まない。
- `docs/assessment/issue-153-zip-restore-notready-root-cause.md` へのredacted assessment記録、および
  #150との関係（shared/independent）の明示。

## Non-goals

- readiness判定・fail-closed動作・composer制御フローの変更（正本は
  [spec #83](../83-production-organization-input-sources/spec.md)）。
- `InputReadinessReason` / `InputCompositionCode` の意味論・割当ての変更、および
  diagnostics schema（`RunEvent` field集合、schemaVersion）の変更。closed code集合の
  新定数追加が必要になった場合も、#172契約（§3/§8）の「 `ErrorEntry.code` への定数追加は
  schema変更なしで可」に従う範囲に限り、その適用はfocused fix Issueが所有する。
- production instrumentation（log行・journal field・新API）の追加。観測は既存surfaceのみで
  行い、測定不能な状態（例: gateの `IDLE` と `RECONCILING` の区別）は要求しない。
- ZIP restore自体（`LawnchairBackup` / `RestoreDbTask` / `DeviceGridState`）の動作変更、
  backup format・allowlistの変更。#58のBACKUP_RESTORE lease契約は不変である。
- planner/apply/recoveryの契約変更。`NotReady` はnon-writeであり続け、planner呼出し、
  recovery point作成、layout mutationを一切起こさない。
- Nova restore経路（#168/#185所有）の再検証・再修正。
- 「現行mainで非再現」をもって本Issueを完了させること。それを正式な完了条件にする場合は
  先にIssue #153本文のAcceptanceを変更する（本specでは行わない）。
- 実deviceのarchive・journal等のraw evidenceのcommit。raw evidenceはcommitしない。

## Domain language

- **ZIP restore episode**: 本Issueが追跡する、ZIP backup restore後の複数process・約30分の
  `NotReady` 窓とその後の解消の観測記録。診断契約の用語ではなく、このspec/assessment内の
  証拠識別子である。
- **historical replay ladder（H0–H4）**: 上記Scopeに定義する比較head列。診断契約の用語ではなく
  本spec内の手続き名である。`CONTEXT.md` への追加は不要である。

## Behavior scenarios

### Scenario: H0で元episodeが再現する

Given 観測head `74c2156767` のdebug buildで `pm clear` 済みapp stateへ同一archiveをrestoreした。
When 同一process内retryとprocess再起動を交えたretryを停止条件まで実施する。
Then restore直後の窓でinput unavailableが観測され、元episode（複数process・約30分後の解消）
と同型の挙動がjournal/logcatで確認される。
And H0では理由コードが存在しないため、記録は症状の有無・時刻・process世代に限定される
（#172以前のheadの観測条件どおりである）。
And H0で元episodeが再現しない場合、入力（archive・手順・環境）の差分を特定して
assessmentに記録し、実測計画をIssue #153へ報告してから先へ進む。

### Scenario: input unavailableなattemptが理由コード付きで観測される（H3以降）

Given launcher modelが読込済みで、composerがいずれかの理由で `NotReady` を返すhead（H3以降）
でZIP restore後のmanual startが実行される。
Then journalに `RUN_STARTED` に続く `INPUT_NOT_READY`（`family=INPUT_READINESS`、
`code=<InputCompositionCode定数名>`）が追記され、公式export surfaceで取得できる。
And capture失敗に起因する場合のみ、debug buildのlogcatに `phase=CAPTURE
exceptionClass=<class simple名>` の行が出る（raw message・stack traceは出ない）。
And UIのcopy区分は#172の区分に従い、planner呼出し・checkpoint・apply・recovery point・
Launcher DB mutationはすべて0回である。

### Scenario: attempt/process境界が観測可能な粒度で記録される

Given 任意のheadで、restore直後から同一process内retryとprocess再起動を交えたretryを実施する。
When 各attemptのevidenceを収集する。
Then 各attemptについて、process世代・elapsed時間・結果分類（preview到達 / input unavailable）が
記録される。
And H3以降のheadでは、input unavailableなattemptの理由コード区分
（`RECONCILIATION_PENDING` / `RECONCILIATION_FAILED` / その他のcomposer code）が紐づけられる。
gateの `IDLE` と `RECONCILING` は両方とも `RECONCILIATION_PENDING` に対応するため区別しない。
And model readinessは、観測可能な事実のみ記録する: 30秒timeout failure行
（"Organizer startup reconciliation began without a completed model load"）の有無、および
gate通過（理由コードがreconciliation系でない）からの間接確認である。model load成功の
専用logは存在しないため、それ以上の断定をしない。
And episodeが解消したheadでは、直前の失敗headと比較した状態差（workspace内容、DB file、
pref、process世代）がschema-levelで記録される。

### Scenario: 持続の確定には有限の停止条件が適用される

Given あるheadでrestore後のattemptがinput unavailableであり続けている。
When 観測を継続する。
Then 最初のpreview到達attemptの時点で観測を終え、elapsed時間とprocess世代を記録する。
Or restore完了後40分以上が経過し、かつ3以上のfresh processでそれぞれ1回以上のattemptが
すべてinput unavailableであった時点で「持続」と判定し、観測を終える。
And いずれの条件にも至らないまま無期限に観測することはない。

### Scenario: 因u変更の限定とowning seamのdeterministic test

Given 同一archiveでのreplay結果（各headの失敗/成功）。
When 最後の失敗headと最初の成功headの間の変更から因果変更を限定する。
Then 限定された変更が示す機構からowning seam（composer/capture、`ReadinessGate`/reconciliation、
policy/source整合、model/reloadのいずれか）を特定し、そのtriggerに対するdeterministic testを
owning seamに確保する。fixing変更（例: #155）が既に同triggerのregression testを持つ場合は
それを特定・linkし、triggerを実際にカバーしていることを検証する。存在しない場合は
schema-level fixtureで追加する。いずれの場合もtestは実ZIP archiveを解凍しない。
And root causeが現行mainで未解決のproduction defectである場合、fixはfocused fix Issueへ
spec-firstで分割され、本IssueのPRは生産コードのfixを含まない。fix Issueの実装が完了し、
fix側でnon-write振る舞いと§7 redactionの維持が検証されるまで、本Issueはcloseされない。
And root causeが中間変更により既に解消済みである場合、その対応（変更・seam・既存test）を
assessmentに記録する。既存testがnon-write/redactionのinvariantsを実際にカバーしていることを
この時点で確認する。追加の生産コード変更は行わない。

### Scenario: 収集evidenceのprivacy不変とhash境界

Given 収集された全evidence面（journal export、logcat、assessment記載）。
When organizer-diagnostics契約§7のNever分類検査を行う。
Then package名、component名、座標、item列挙、raw exception message、backup payload内容は
一切現れない。
And assessmentに記載できるhash/digestは、git head SHA・build識別子など**コード識別のみ**に
限定される。layout・DB行・archive・journal内容由来のhash/digestは、§7のNever分類（digest）と
同様に禁止する。
And assessment記載はcount、opaque ID、closed code、schema-level messageに限定される。

### Scenario: #150との関係の明示

Given root causeのevidence。
When #150（A7 completion barrier）との共有/独立を判定する。
Then 判定と根拠がassessmentに記録され、Issue #153へコメントされる。
And 独立と判定する場合、#150の所有するA6後reload完了境界（`LayoutWriterPort` /
`OrganizerModelReloadAdapter` seam）とは異なるseamであることを根拠に含める。

## Data and state

- 生産データへの書込みはnone。journalへの書込みは#172実装済みの `INPUT_NOT_READY`
  terminal経路のみであり、retention・fail-open性は既存契約のままである。
- journal内容の取得は**公式export surfaceのみ**（Settings → Home screen → Organizer
  diagnostics → Export organizer diagnostics。SAF picker経由でDownloads等へ保存しadbで取得。
  #104 evidenceと同じ手順）。private journal fileの内容を `run-as` で直接読むことはしない。
- archiveはH0 headのdebug buildで1回だけ作成し、`/tmp` 配下に保存して全headで同一入力として
  使う。head間でbackup version gateによりrestoreが拒否された場合は、その事実をevidenceとして
  assessmentに記録し、手順の継続判断をIssue #153へ報告する。
- assessment（redacted）のみを `docs/assessment/issue-153-zip-restore-notready-root-cause.md`
  へcommitする。raw evidence（logcat全体、exported journal、archive）は `/tmp` 配下に置き、
  commitしない。
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

- [ ] **AC-1 — headごとのattempt分類と（可能なheadでの）理由コード取得:** 各replay headで、
  restore後のすべてのattemptがjournal/logcatから「preview到達 / input unavailable」に分類
  される。H3以降のheadでは、input unavailableなattemptごとに `INPUT_NOT_READY` +
  `InputCompositionCode` 定数名が公式export経由で取得できる（capture失敗時はdebug logcatの
  exception class行も取得できる）。H0–H2では理由コードが設計上存在しないため、症状の
  有無・時刻・process世代の記録をもって本ACを満たす。
- [ ] **AC-2 — 境界の記録（観測可能粒度）:** process世代・elapsed時間・attempt結果分類が
  attempt/process単位でassessmentに記録される。H3以降では理由コード区分
  （`RECONCILIATION_PENDING` / `RECONCILIATION_FAILED` / その他。`IDLE` と `RECONCILING`
  は区別しない）とmodel readinessの観測可能事実（timeout failure行の有無、gate通過からの
  間接確認）を含む。解消時はelapsed時間とprocess世代を、持続時は停止条件の充足（40分以上かつ
  3以上のfresh process）を記録する。
- [ ] **AC-3 — root cause特定とowning seamのdeterministic test（必須）:** 同一入力での
  historical replayにより因果変更が限定され、そこからroot causeがowning seamで特定される。
  当該triggerに対するdeterministic testがowning seamに確保される（fixing変更の既存regression
  testの特定・検証、またはschema-level fixtureでの追加）。root causeが現行mainで未解決の
  場合、focused fix Issueがspec-firstで起票されている。**現行mainで非再現であること単独では
  本ACを満たさない。** bounded non-reproductionを正式な完了条件にする場合は、先に
  Issue #153本文のAcceptanceを変更する。ただし本ACが満たすのは**実測で再現したNotReady条件の
  root cause**であり、元episodeの「約30分で自然解消」を当該defectが説明できなかった場合
  （H0 scenarioの差異分岐が発動した場合）は、その解消理由を
  `historical discrepancy / unresolved` としてassessmentと本Issueに明示的に分離して記録し、
  fix対象defectの採用判断を本specのchange historyに残す。
- [ ] **AC-4 — non-writeとredactionの不変（本Issueの変更面）:** 調査・test追加・docs変更の
  本IssueのPRが `NotReady` のnon-write振る舞い（planner呼出し・recovery point作成・layout
  mutation 0回）と、§7 Never分類（journal・export・logcat・assessment）を壊さない。assessmentの
  hashはコード識別（head SHA・build識別子）に限定される。既存organizer test群が無変更で
  通過する。**fix側の実装によるinvariants検証はAC-6が所有し、本ACは重複しない。**
- [ ] **AC-5 — #150との関係の明示:** 共有/独立の判定が証拠とともにassessmentと
  Issue #153に記録される。
- [ ] **AC-6 — fix受入の完了:** root causeが現行mainで未解決のproduction defectである場合、
  spec-firstのfocused fix Issueが起票され、**そのfixの実装が完了し、fix側でnon-write振る舞いと
  既存diagnostics redactionの維持が検証済み（fix PRのtest・evidence）であること**。これらが
  確認できるまで本Issueはcloseしない（Issue本文のfix acceptanceの充足確認）。root causeが
  中間変更により既に解消済みの場合は、対応する変更・seam・既存testのmappingと、そのtestが
  non-write/redaction invariantsをカバーすることの確認がassessmentに記録され、本ACは
  満たされる。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | 各headの実測記録: 公式export surfaceで取得したjournal（`RUN_STARTED` / `INPUT_NOT_READY` event行）と `logcat OrganizerDiag:V *:S` のredacted転載、attempt分類表。H0–H2は症状有無・時刻・process世代の記録 |
| AC-2 | assessmentのattempt/process境界表（process世代、elapsed、結果分類、H3以降は理由コード区分とtimeout行の有無、停止条件の充足記録） |
| AC-3 | 因果変更の限定根拠（head×結果の表）、owning seamの特定、deterministic testのlinkまたは追加test + 実行記録。追加時は `./gradlew :lawnchair:testLawnWithQuickstepGithubDebugUnitTest`（該当task） |
| AC-4 | 本Issue PRの既存organizer test群の無変更通過（`./gradlew :lawnchair:testLawnWithQuickstepGithubDebugUnitTest`）、§7 negative fixtureの通過、`./gradlew spotlessCheck` + `./gradlew assembleLawnWithQuickstepGithubDebug`。assessmentのhash使用がコード識別のみであることの確認記録 |
| AC-5 | assessmentの#150関係節 + Issue #153への記録コメント |
| AC-6 | （未解決defect時）focused fix Issueのlink、fix PRのtest/evidenceによるnon-write・redaction維持の検証記録。（解消済み時）中間変更とのmapping + 既存testのinvariantsカバレッジ確認記録 |

## Open questions

なし。分岐（現行mainで再現する/しない）はいずれもhistorical replayとAC-3の必須root cause
要件に収束しており、spec承認の前提となる未決定の製品判断は存在しない。観測結果が上記の
どの分岐にも当てはまらない場合（例: H0で元episodeが再現しない、backup version gateにより
同一archiveのrestoreが拒否される、closed code集合で表現できない新しい失敗区分の発見）は、
実装・調査を止めてIssue #153へ記録し、specを更新する。

## Change history

- 2026-09-01: Issue #153本文、#150の訂正コメント（2026-08-26 08:46Z）とPR #151 evidence
  （issue-104-105-106-device-evidence.md）、#171/#172 assessment、spec #83/#150/#172/#185、
  現行mainのcode事実に基づきdraftを作成。観測surface部分は#172が解消済みのため、本specは
  ZIP restore経路の実測・境界記録・根因特定に範囲を限定した。
- 2026-09-01: Review rev 2。指摘に対応: (1) [P1] 非再現時のbounded完了を撤去し、同一入力
  （H0で作成したarchive）によるhistorical replay ladder（`74c2156767` → #155 → #156 → #172 →
  #185/current）での因果変更限定と、owning seamのdeterministic testを必須化（Issue本文の
  受入条件を緩和しない）。(2) [P1] gate/model状態の要求を観測可能粒度へ修正
  （`IDLE`/`RECONCILING` は区別せず `RECONCILIATION_PENDING` に統合、model readinessは
  timeout failure行とgate通過からの間接確認に限定）。(3) [P1] AC-1をheadの観測条件に応じて
  分岐（H0–H2は理由コード不取得を明示）。(4) [P2] 同一archiveの原則（H0で作成、`/tmp` 管理、
  version gate拒否時の報告）。(5) [P2] 持続判定の停止条件（40分以上かつ3以上のfresh process）。(6) [P2]
  journal内容の取得を公式export surfaceに統一（`run-as` での内容読取を排除）。(7) [P2]
  assessmentのhashをコード識別のみに限定。
- 2026-09-01: Re-review rev 3。残り1点のblockingに対応: focused fix Issueの「起票」だけでは
  Issue #153本文のfix acceptance（"The fix preserves non-write behavior and existing
  diagnostics redaction"）を満たさないため、**fix実装とfix側でのinvariants検証の完了を
  本Issueのcloseの前提（blocking dependency）とする方針を採用**（Issue本文のAcceptanceを
  維持し、変更しない選択）。AC-6を「起票」から「fix完了 + invariants確認」へ強化し、AC-4を
  本Issueの変更面の責務としてAC-6との責務境界を明記。non-blocking: AC-4のverificationに
  organizer unit test commandを追加。
- 2026-09-01: 再レビューで指摘なし。statusを `accepted` へ更新し、plan.mdに従って実行を開始する。
- 2026-09-01: 実行完了([assessment](../../docs/assessment/issue-153-zip-restore-notready-root-cause.md))。ladder全headでepisode再現。H3/H4で理由コード `INPUT_NOT_READY / RECONCILIATION_FAILED` を取得し、root causeを確定(ZIP restoreが `databases/` を削除しrecovery DBを巻き込む一方、stale inspection snapshotが残留 → `SuspiciousAbsence` → `READ_FAILED` → gate FAILEDの恒久化)。snapshot削除で即解消する因果実験をH3/H4で実施。AC-1/2/3/4/5を満たし、AC-6はfocused fix Issueのfix実装・検証完了まで未充足(blocking)。
- 2026-09-01: Investigation review対応。(1) [P1] **fix対象の採用判断とhistorical discrepancyの分離を記録**: 実測で確定したのは「startup reconciliation後にZIP restoreするとrecovery DBだけ削除されsnapshotが残留し `RECONCILIATION_FAILED` が恒久化するproduction defect」であり、元episodeの「約30分で自然解消」(2026-08-26 16:49の回復)を本defectが説明するとは主張しない。解消時点のartifact stateが元証拠に記録されていないため、自然解消の理由は `historical discrepancy / unresolved` としてassessment・本Issueに分離記録した。H0 scenarioの差異分岐(記録・報告してから進む)はこの形で履行済み。AC-3は「実測で再現した条件のroot cause」に対して満たすものと注記。この採用判断は本エントリをもってspec側の記録とする。(2) [P1] assessmentのarchive由来SHA-256を削除し、AC-4 mappingをhead SHA / build識別子のみに修正(archive同一性は手順証拠で担保)。
