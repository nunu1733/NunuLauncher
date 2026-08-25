---
issue: "#138"
status: proposed
requirements:
  - FR-015
  - NFR-011
updated: 2026-08-25
---

# オーガナイザー診断exportをサポート済みrelease設定経路で到達可能にする

> Stage A: 本specと同伴の[plan.md](./plan.md)はIssue [#138][1]での承認対象である。
> 承認後、両文書はStage B実装の拘束契約となり、実装発見が決定と矛盾する場合は暗黙の代替を導入せず停止条件として扱う。

## Problem

Release productには、**Export organizer diagnostics**へ一般に到達できるサポート済みSettings経路が存在しない。#67はexportをuser-initiatedなSettings actionとして定義し、#103はそれを実際にユーザーが操作するsurfaceとして扱うが、production export composable(`OrganizerDiagnosticsExportPreference`)はdefault-offのDebug menu内にしか配置されていない。

現在の唯一のDebug menu有効化手段はLawnchair自身の `AllAppsSearchInput` に入力する `/lawnchairdebug` 秘密文字列である。[#132 release candidate](https://github.com/nunu1733/NunuLauncher/issues/132)ではapp-drawer searchがGoogle global searchへ委譲されるため、秘密文字列はGoogle app側に入力され、Debug menuは有効化されない(観測証跡は[issue-132-exploratory-baseline.md §Diagnostics export route — Issue #138](../../docs/assessment/evidence/issue-132-exploratory-baseline.md#diagnostics-export-route--issue-138))。

さらに、manual organizationの `Open organizer diagnostics` actionは `requiresSafeSupport()` のapply/recovery結果に限定されており、一般export entryではなく、#136で再現されたplanning rejection等では利用できない。このactionの遷移先もDebug menuであり、`OrganizerDiagnosticsExportPreference` は `Show debug menu` switch(既定off)の内容内に入れ子になっているため、developer toggleを有効化しない限り遷移先でもexport行は表示されない。

## Outcome

通常ユーザーがrelease buildで、secret developer menuやapp-drawer search provider設定に依存せず、安定したSettings経路から **Export organizer diagnostics** を実行できる。当該経路は既存の #67 export surface(`OrganizerDiagnosticsExportPreference` + 単一のlive diagnostics port + 既存`ExportWriter`)をそのまま使う。安全terminalの `Open organizer diagnostics` も同じ表面へ届く。

## Scope

- HomeScreen設定配下に、organizer diagnostics専用のdestination画面を新設し、HomeScreenのLayout groupからのnavigation entryを置く。
- 既存 `OrganizerDiagnosticsExportPreference` のcomposition siteを新画面へ追加する。diagnostics portは既存の単一port(`layoutApplicationModule.diagnostics`)へのaccessor再利用であり、第2のjournal/export seamは作らない。
- 安全terminal `Open organizer diagnostics` の遷移先を、Debug menuから新経路へ付け替える。
- 新画面title・explainerのlocalized文字列(en/ja)の追加。
- 上記に対するUI/instrumentation検証と、release(minified)build上のredacted operator evidence。

## Non-goals

- developer Debug menu本体の露出・再構成。`/lawnchairdebug` 秘密経路とDebug menu内の既存export行は現状維持とする。
- telemetry、network transport、permission追加、自動export(#67契約§12のまま)。
- journal schema、export format、`ExportWriter`、SAF/share flow自体の変更。これらの正本は[spec 67](../67-organizer-diagnostics/spec.md)と[organizer-diagnostics contract](../../docs/engineering/organizer-diagnostics.md)である。
- #103/#109のSwitch Access matrix・200% font scale等のfull accessibility evidenceの複製。
- manual organizationのrun-state machine UIや安全terminal条件(`requiresSafeSupport()`)自体の変更。

## Domain language

実装語のみのため空。新しいproduct/domain用語は導入しない。

## Behavior scenarios

### Scenario: release buildで設定経路からexportへ到達できる

Given release/minified APKがHOMEとして動作し、`enableDebugMenu=false`(既定)かつapp-drawer search providerがGoogle global searchである
When Home settings → ホーム画面 → organizer diagnostics画面へ通常のpreference navigationで進む
Then organizer diagnostics export行が表示されている
And Debug menu有効化や `/lawnchairdebug` 入力は一切要求されない
And この経路は静的なpreference navigationのみで構成され、drawer search provider設定に依存しない

### Scenario: 明示的活性化だけがSAF surfaceを開く

Given organizer diagnostics画面が表示されている
When ユーザーがexport行を明示的に活性化する(tap/keyboard activation)
Then システムのCreateDocument(SAF)surfaceが開く
And 画面の表示・navigationだけではファイル書き込み・share・外部送信は開始されない

### Scenario: cancellationは副作用なく戻る

Given SAF surfaceが開いている
When ユーザーがcancel/backする
Then 元の画面へ戻り、journal・layout・preference状態は不変である
And 自動retry、書き込み、uploadは発生しない

### Scenario: 安全terminalからの導線が同じ表面に届く

Given manual organizationの適用/recovery結果が `requiresSafeSupport()` に該当する
When `Open organizer diagnostics` を活性化する
Then 遷移先の新経路上で、既存export行がDebug menu無効化のまま表示される
And 書き出しは #67 と同一のexport surface(SAF CreateDocument + ExportWriter)へ接続される

### Scenario: 既存developer表面への影響がない

Given `enableDebugMenu=true` のdeveloper環境
When dashboard overflowやDebug menuを開く
Then 従来どおりDebug menu・Feature flags等が機能し、Debug menu内のexport行も従来どおり動作する
And 本変更によってDebug menu以外のdeveloper flagが新たに露出することはない

## Data and state

- 読む状態: 新経路自体は静的なnavigation構造であり、状態を読まない。export実行時の読み書き(journal snapshot読み取り+ユーザー選択destinationへの書き込み)は #67 契約のままで本Issueでは変更しない。
- 永続化: 本Issueが新規に永続化するものはない。preference key追加なし、journal書き込みなし、Launcher layout DB / `favorites` への接触なし。
- migration / backup / restore / rollback: 対象外。schema・preference format変更はなく、PR revertで現行挙動へ戻る。ホームレイアウト安全規約のrecovery point要件は、layout data pathを一切変更しないため適用外である。

## Permissions, privacy, and security

None — 新しいpermission、外部送信、sensitive dataの扱い追加はない。SAF CreateDocumentはユーザーの明示的活性化後にのみ発火し、宛先選択はシステムflow(#67 §9)のままである。新経路は既存export行への到達性だけを変え、exportされるデータ集合は #67 のAllowed field setから広げない。

## Accessibility and localization

- 新画面title・explainer、および既存export行label/subtitleはlocalized(en/ja)であること。
- control semanticsは近隣の`ClickablePreference` / `NavigationActionPreference`と整合し、TalkBackとkeyboard navigationで到達・操作可能であること。
- Switch Access traversal、focus matrix、font scalingのfull evidenceは #103/#109 が所有する。本specは新経路がそれらの既存結果を回帰させないこと(既存 #103 debug surface evidenceのlabel/subtitle/activationが変質しないこと)のみを要求する。

## Acceptance criteria

- [ ] **AC-1**: release(minified)buildで、secret developer toggleなしにSettings navigationだけでorganizer diagnostics export行へ到達・表示できる。drawer search provider設定に依存しない。(issue受入 1, 3)
- [ ] **AC-2**: 当該経路のexport行は既存 #67 `OrganizerDiagnosticsExportPreference` と同一compositionであり、同一live diagnostics portと`ExportWriter`を使う。第2のjournal/export seam・重複composableは作られていない。(source差分で証明)(issue受入 2)
- [ ] **AC-3**: SAF CreateDocument/share surfaceは明示的活性化後にのみ開く。画面表示・navigationのみでは起動しない。(issue受入 4)
- [ ] **AC-4**: cancellation時に自動retry・書き込み・upload・layout mutationは発生せず、元の画面へ戻る。(issue受入 5)
- [ ] **AC-5**: 安全terminal `Open organizer diagnostics` は新経路へ遷移し、同一export表面へ届く。(issue受入 6)
- [ ] **AC-6**: redactedなrelease operator evidenceが、可視navigation・activation・cancellation・returnをカバーして記録される。(issue受入 7)
- [ ] **AC-7**: 新規・変更UI文字列がen/jaでlocalizedであり、accessible semanticsが近隣controlと整合する。#103/#109の所有範囲を回帰させない。(issue受入 8)
- [ ] **AC-8**: 関連UI/instrumentation test、spotless、debug/release build、CI merge gate(`final-status`)がpassする。(issue受入 9)

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | API 36 emulator上のrelease/minified APKでの手順evidence(redacted UI state記録)+ instrumentation assertion |
| AC-2 | 実装PR差分(新画面が同一composable・同一port accessorを再利用し、ExportWriter/journal seamのdiffがゼロであることの確認) |
| AC-3 | instrumentation: production契約のrecording `ActivityResultRegistry` で、表示・navigation中はlaunch呼出0、明示activationで `ACTION_CREATE_DOCUMENT` intentのlaunch=1を観測(観測方法は[plan.md](./plan.md) Design節) |
| AC-4 | 同instrumentation: `RESULT_CANCELED` dispatch後にwriter不呼び出し(recording port観測)+journal不変。writer seam自体のcancel/write-failure分離は既存 #67 `ExportWriterTest`(JVM gate内の`d10CancellationIsolated` / `d10WriteFailureLeavesJournalIntact`)が所有。release operator evidenceでend-to-end cancel/returnを確認 |
| AC-5 | 安全terminal経由のnavigation instrumentation(既存manual organization harnessの拡張または同等) |
| AC-6 | release APKでの手動操作記録(navigation→activation→cancel→return)をissue/PRへredacted添付 |
| AC-7 | string resource差分(values/values-ja)+ compose semantics assertion |
| AC-8 | CI run URL(`final-status`)とlocal command結果をPR本文へ記録 |

## Open questions

None are blocking. 非blocking事項:

1. 新画面title/explainerの最終文言は実装PRのstring diffで確定する(承認blockerではない)。
2. Debug menu内export行の維持は本specで決定済み(non-goals参照)。将来的なDebug menu整理は別Issueで扱う。

## Change history

- 2026-08-25: Draft created for #138 (Stage A). Issue本文の観測事実と [issue-132-exploratory-baseline.md](../../docs/assessment/evidence/issue-132-exploratory-baseline.md) のroute/source boundary証跡を入力に作成。
- 2026-08-25: Stage A review(P1)対応。AC-3/AC-4のtest oracleを、production `ActivityResultRegistry` 観測(#138新規instrumentation)とwriter seam分離(既存 #67 `ExportWriterTest` 再利用、再実装しない)の分担として固定。behavior・scope変更なし。

[1]: https://github.com/nunu1733/NunuLauncher/issues/138
