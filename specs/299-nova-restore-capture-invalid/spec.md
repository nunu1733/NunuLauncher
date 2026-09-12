---
issue: "#299"
status: draft
requirements: [CI-AC-01, CI-AC-02, CI-AC-03, CI-AC-04, CI-AC-05, CI-AC-06, CI-AC-07]
risk: []
updated: 2026-09-12
---

# Nova restore後のOrganizer captureがCAPTURE_INVALIDに恒常化しない

## Problem

Nova backup restore完了後、Organizerの権威的capture
（[OrganizationInputComposer.kt](../../lawnchair/src/app/lawnchair/organizer/integration/OrganizationInputComposer.kt)
の `LayoutWriterCanonicalCaptureSource` →
[LauncherLayoutAdapter.kt](../../lawnchair/src/app/lawnchair/organizer/application/adapter/LauncherLayoutAdapter.kt)
の `capture()`）が `IllegalArgumentException` で失敗し、composerが
`CAPTURE_INVALID`（terminal `INPUT_NOT_READY`）を返す状態が発生しうる。

#287の横断調査で記録された実機セッション（build `d0f40446c7`、2026-09-12報告）では:

- 9/9のOrganizer runが `INPUT_NOT_READY` / `CAPTURE_INVALID` で失敗した。
- 障害は4回のapp process置換と5分超を跨いで持続した。
- ZIP restore、Nova再restore、process再起動のいずれでも復帰しなかった。
- #172のdebug capture diagnosticsは一貫して
  `phase=CAPTURE exceptionClass=IllegalArgumentException` を報告した。
- 失敗はstrategy planningより前（capture直後、数十ミリ秒）に発生した。

同一日・同build・同様のNova restore手順の他セッションは成功しており、
発生はセッション依存（intermittent trigger）である。ただし一旦 bad state に
達すると観測された失敗は持続する。

現在のprivacy-bounded diagnosticsは例外classしか運ばないため、違反された
capture不変条件が何であるかは不明である。Organizerは無効なcaptureに対して
正しくfail-closedするため、修正はreadiness checkを弱めるのではなく、
復元されたworkspace/model状態またはcapture不変条件そのものを対象にしなければ
ならない。

観測build `d0f40446c7` は現在のmain（`f9afd8bfde`）の祖先であり、両者間の差分で
capture pathの本体
（[RowManifestCodec.kt](../../lawnchair/src/app/lawnchair/organizer/application/adapter/RowManifestCodec.kt)、
[LauncherLayoutAdapter.kt](../../lawnchair/src/app/lawnchair/organizer/application/adapter/LauncherLayoutAdapter.kt)、
[NovaBackupConverter.kt](../../lawnchair/src/app/lawnchair/backup/NovaBackupConverter.kt)、
[RestoreDbTask.java](../../src/com/android/launcher3/provider/RestoreDbTask.java)）
は無変更である。composer
（OrganizationInputComposer.kt）には #228 のselection追加があるが、
capture失敗 → `CAPTURE_INVALID` の導出は不変である。よってこの観測は
現在のmainのcapture path構造に妥当する。

root causeは未確定である。このspecは、観測可能な振る舞いの契約と
root cause確定のためのinvestigation要求を固定するものであり、fix architectureを
先取りしない。

## Outcome

Nova restore → model reload完了後のworkspaceが、Organizerによって権威的に
captureできる。影響を受けるNova restore → Organizer flowが持続的な
`CAPTURE_INVALID` に陥らない。それでいて、本当に無効な権威的captureに対して
Organizerはfail-closedし続ける。capture失敗時のdiagnosticsは、ユーザーの
layout内容を漏らさずに、違反された不変条件を特定できる十分な（かつ契約上
boundedな）文脈を運ぶ。

## Scope

- Nova restore後にOrganizer captureが `IllegalArgumentException` で失敗する
  正確なsource/不変条件の特定（investigationの第一成果）。
- 特定された無効状態への対処: 復元dataが本当に無効な場合のdeterministicな
  正規化または拒絶、またはcapture不変条件を読めるbounded diagnostic文脈の
  提供。選択はroot cause確定後のdecision gateで行う。
- capture pathの持続失敗からの復旧挙動の明示化（どの状態遷移が
  `CAPTURE_INVALID` から回復させるか）。
- #185が保護するreserved QSB / reservation不変条件の非回帰確認。
- Nova restore → capture の繰り返し検証での安定性（レース性triggerの排除確認）。

## Non-goals

- readiness/capture checkの弱化、`CAPTURE_INVALID` の握り潰し、無効captureの
  成功扱い。Organizerのfail-closed挙動は保存される。
- #298（Nova restore/reloadのwrong-thread障害）。同一セッションで観測された
  相関はあるが因果関係は未確立であり、#299の調査・修正は #298 の修正を
  仮定せず、逆も同様である。証拠が出るまで両Issueをmergeしない。
- #287（grid変更時の `CAPTURE_UNKNOWN_LOCK` 恒常化）。
- ZIP backup restore path（`LawnchairBackup` 経由）自体の振る舞い変更。
  障害セッションでZIP restoreが試行されたという経過はあるが、本Issueの
  適用条件はNova restore → Organizer capture flowである。
- #172が確立したprivacy-bounded diagnostics契約（例外class simple nameのみ、
  message/stack trace/layout由来text不載）の廃止。文脈追加が必要な場合は
  同契約のbounded拡張として行う。
- #185 / ADR-0010のreservation overlap契約（`RESERVED_OVERLAP` gate、
  `overlapAcceptanceHolds`）の変更。

## Domain language

実装語のみであり、`CONTEXT.md` への新規domain用語の追加はない。既存語の
整理:

- **capture失敗identity**: `CAPTURE_INVALID` が運ぶ失敗の正規化identity。
  現在は例外class simple nameのみ（#172契約）。
- **CAPTURE_INVALID**: composerの `InputCompositionCode` の1つ。
  productionではcapture sourceが非Readyを返した場合のみ発生し、
  `UNKNOWN_LOCK` / `CAPTURE_UNREPRESENTABLE` / `CAPTURE_RESERVED_OVERLAP`
  とは理由が区別される閉じた語彙である。

## Behavior scenarios

### Scenario: Nova restore後のOrganizer captureが成功する（回帰oracle）

Given 初期状態から、影響を受けたNova backupを含む復元対象を用意する
When Nova backup restoreを実行し、restore/model reload完了を待つ
Then Organizerのpreview/capture要求が成功し、`CAPTURE_INVALID` が発生しない
And logcatに `phase=CAPTURE exceptionClass=IllegalArgumentException` が出現しない

### Scenario: 繰り返しrestoreでも持続失敗が再現しない

Given 同一環境でNova restore → Organizer capture → （必要なら再）restoreの
  cycleを繰り返す
When 複数回のrestore → captureを試みる
Then 全cycleでcaptureが成功するか、失敗しても次のcycleで恒常化しない
And 障害が特定セッションに依存しないことが繰り返し実行で確認される

### Scenario: 本当に無効なcaptureはfail-closedし続ける

Given capture対象の状態がcapture不変条件に違反している（root causeとは無関係に
  意図的に作った無効状態を含む）
When Organizer runがcomposeを試みる
Then runは書込みを行わずterminal `INPUT_NOT_READY` で終了する
And 例外classの正規化identityがdiagnosticsに現れる（#172契約の維持）

### Scenario: capture失敗時のdiagnosticsが不変条件を特定できる

Given captureが不変条件違反で失敗する
When ユーザーまたは保守がdiagnostics（journal + logcat）を読む
Then 違反された不変条件が、ユーザーlayout内容（title、intent、icon、
  配置一意データ等）を漏らさずに識別できる
And 追加された文脈はorganizer diagnostics契約のbounded fieldである

### Scenario: CAPTURE_INVALIDからの復旧挙動が説明できる

Given Organizer runが `CAPTURE_INVALID` で終了した
When ユーザーが後続のOrganizer runを開始する
Then 各runは新鮮なcaptureから再出発する（前runの失敗状態の持ち越しはない）
And 復元状態が有効化された後のrunは成功する（修復操作の要否と復帰条件が
  plan/investigation記録で説明される）

### Scenario: reserved QSB保護は変更されない

Given #185の回帰表面（QSB行overlap item、reservation geometry検証）を実行する
When 本Issueの変更を適用する
Then #185の既存unit/instrumentation回帰coverageがすべてgreenである
And reservation違反に対する `CAPTURE_RESERVED_OVERLAP` / write時
  `OVERLAP_POLICY_REJECTED` のfail-closed挙動が保存される

## Data and state

- 本Issueの修正がschema変更や `favorites` 書式変更を含まないことを目標とする。
  ただしroot cause確定後に復元dataの正規化writeが必要と判明した場合は、
  そのwriteは既存のrestore-family lease（#58）、transaction、および
  ホームレイアウト安全規約（snapshot/revision一致、recovery point、
  transactional適用、適用後再検証）の内側で行われ、別途planで詳細化する。
- capture自体は読み取り専用であり、書込み・checkpoint・recovery操作を
  行わない（既存契約の維持）。
- 調査では「持続失敗の本体が復元dataの永続的無効性か、capture読み取り窓の
  世代不整合（レース）か」を切り分ける。後者の場合、修正対象は
  capture読み取りの整合性である。
- diagnostics journalはapp-private・local-onlyであり、backup対象外（既存契約）。

## Permissions, privacy, and security

- None。新規permission、外部通信、sensitive dataの追加はない。
- capture失敗diagnosticsにlayout内容・例外message・stack traceを載せない。
  bounded文脈の追加はorganizer diagnostics契約
  ([docs/engineering/organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md))
  のredaction規則に従う。

## Accessibility and localization

- None。本IssueはUI文言・操作に触れない。`INPUT_NOT_READY` 時の既存
  ユーザー向けsurfac表示は変更しない。調査で失敗理由のUI表現の不備が
  判明した場合は、別Issueへ分離する。

## Acceptance criteria

- [ ] CI-AC-01: capture側 `IllegalArgumentException` の正確なsource/不変条件が、
  stack/log証跡とともに特定・記録されている（候補の列挙ではなく、観測された
  障害の説明になっていること）。
- [ ] CI-AC-02: 影響を受けたNova restore → Organizer flowが、持続的な
  `CAPTURE_INVALID` に陥らない。restore/model reload完了後のcaptureが成功する。
- [ ] CI-AC-03: 本当に無効な権威的captureに対してOrganizerがfail-closedし続ける
  （readiness checkの弱化、`CAPTURE_INVALID` の除去・握り潰しを行わない）。
- [ ] CI-AC-04: #185のreserved-QSB placement保護が引き続き有効である
  （既存回帰coverageがgreen）。
- [ ] CI-AC-05: 実施可能な範囲で自動regression（restore → capture）が追加されて
  いる。triggerが判明した後は、繰り返しrestore cycleまたは特定されたレース窓を
  対象にする。
- [ ] CI-AC-06: emulatorまたは実機検証が、繰り返しのrestore → capture試行を
  覆い、回復・安定性を確認している。
- [ ] CI-AC-07: capture失敗時の復旧挙動が確定している。root cause確定後の
  decision gateで「deterministicな正規化/拒絶」または「bounded diagnostic文脈」
  （または両方）の選択が記録され、選択された振る舞いが検証されている。

## Test oracle

| AC | Evidence |
|---|---|
| CI-AC-01 | 再現実行時のlogcat/stack証跡と、特定された不変条件の記録（`docs/assessment/issue-299-<slug>.md` + plan.md） |
| CI-AC-02 | emulator/実機でのNova restore → capture実行記録。`CAPTURE_INVALID` / `phase=CAPTURE` 出力の不在。instrumentation（`NovaRestoreGridApplicationTest` 系seamの拡張を含む） |
| CI-AC-03 | 意図的無効状態でのunit/instrumentation fail-closed test + 既存composer `NotReady` 契約testのgreen |
| CI-AC-04 | `OrganizationInputComposerTest` の#185 case、`LoaderCursorOverlapAcceptanceContractTest`、`OverlapAcceptanceGateSeamInstrumentationTest` 等の既存coverageのgreen |
| CI-AC-05 | 追加したautomated regressionの実行記録（CI gateに接続するsurface） |
| CI-AC-06 | emulator/実機での繰り返しrestore → capture検証の実行記録（PR証跡） |
| CI-AC-07 | decision gate記録（assessment doc）と、選択された振る舞いのtest/evidence |

## Open questions

- 違反されたcapture不変条件の同定（CI-AC-01）。capture pathの実読により
  `RowManifestCodec` に復元data由来の `IllegalArgumentException` 候補が複数、
  およびcapture読み取り窓の世代不整合候補が確認されているが、どれが観測障害の
  本体かは未確定である。plan.mdのcandidate inventoryとinvestigation planを参照。
- 持続失敗の本体が「復元dataの永続的無効性」か「capture読み取り窓のレース」か。
  プロセス再起動・再restoreでも復帰しなかった観測は前者を示唆するが、
  #298のreload中断が再restore自体の失敗につながった可能性を排除できない。
- emulator上での再現手順の確立（実機観測はセッション依存）。
- 影響を受けた実backup内容をfixture化できるか（privacy配慮の下でsynthetic
  等価fixtureを作れるか）。

## Change history

- 2026-09-12: Draft created for #299.
