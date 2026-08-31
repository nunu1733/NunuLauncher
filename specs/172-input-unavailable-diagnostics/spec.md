---
issue: "#172"
status: draft
requirements:
  - FR-015
  - NFR-011
updated: 2026-08-31
---

# Organizer input-unavailableの診断可能性と理由コードの可視化

## Problem

手動全体整理の `manual_organization_input_unavailable` 画面は、入力が利用不可であることだけを示し、理由をどこにも残さない。

- `DefaultOrganizationInputComposer.composeFullOrganization()` は `NotReady(InputReadinessReason)` を返すが、`ManualOrganizationRun.start()` はそれをUI state `State.InputUnavailable(reason)` に置くだけで、diagnostics journal へのterminal event もlogcat出力も行わない。journal は `RUN_STARTED` のまま終わり、export されたdiagnostics からは `CAPTURE_UNAVAILABLE` / `UNKNOWN_LOCK` / `UNREPRESENTABLE_LAYOUT` / bundle / override / evidence 系のどの理由で終わったrun かを区別できない。
- `LayoutWriterCanonicalCaptureSource.capture()` はcapture側の `RuntimeException` を握り潰して `Invalid` にするため、captureが失敗した場合ですらlogcatに痕跡がない。
- #171 では、Nova restore直後のprocessで「model読込済みなのに2連続で `manual_organization_input_unavailable`、その後のprocessでは成功」という一回性のepisodeが観測されたが、理由を示すproduction証拠が一切残っておらず、原因を限定できない。
- 同一surfaceは、launcher modelの読込完了前にorganizerを開いた場合にも正当に失敗する（`ReadinessGate` が `ReconciliationPending` を返す）。これは期待動作だが、supportからは同じく診断できない。

## Outcome

`InputUnavailable` で終わるすべてのrunが、privacy-safeな理由コードを持つterminal diagnostics recordをjournalに残す。capture側の失敗はdebug/diagnosis levelで観測できる。supportとユーザーは「後でもう一度試せる」状態（model未読込等）と「バグ報告に値する」状態（source/config系の理由）を区別できる。#171の一回性episodeは、名前付きの理由で再現されるか、失敗時点の状態証拠でboundedされる。

## Scope

- `InputReadinessReason` の理由カテゴリ（理由コードのみ。内容・座標・識別子は含まない）をjournalへ出力するterminal phase（`INPUT_NOT_READY`）の導入。
- journal理由コードのclosed集合の定義。composerが既に生成している安定したdiagnostic code集合を、journal出力可能なclosed code集合へ昇格する。
- capture側例外の観測: 例外phase + exception class/messageのdebugレベル出力（organizer diagnostics logger単一tag経由。layout内容は一切含まない）。
- user-facing copyの区分: `ReconciliationPending` は「後でもう一度試してください」系、それ以外の理由は「バグ報告」系の文言を表示できるようにする。
- #171の一回性post-restore capture不可episodeの再現またはbounded。具体的原因が特定された場合はfocused fix Issueへ分割する。
- 上記に伴う [organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md)（正本）の§4.1/§5/§7/§10/§13更新。

## Non-goals

- Ready/NotReadyの意味論自体の変更。何がReadyかは [spec #83](../83-production-organization-input-sources/spec.md) が正本であり、本specはreadiness判定、fail-closed動作、composer制御フローを一切変更しない。
- composerが返す `InputReadinessReason` 型とその割当ての変更。
- 既存journal/projection契約を越える公開diagnostics schemaの追加。§7分類表の限定例外（capture側例外のdebug出力）以外のfield追加はしない。
- capture失敗の根本原因の修正。具体的なcapture側欠陥が見つかった場合はfocused fix Issueとして分割する。
- onboarding/incremental run flowの変更。本specのjournal出力はmanual full run（`ManualOrganizationRun`）を対象とする。composerが共有であるため、将来のrun flowは同じ射影を再利用できる。

## Domain language

- **INPUT_NOT_READY**: 入力合成が `NotReady` で終了したrunを閉じるterminal phase code。journal理由コードを1つ伴う。
- **INPUT_READINESS**: `INPUT_NOT_READY` が使う `ErrorFamily`。codeの来源はcomposerのclosed diagnostic code集合である。

承認時に [CONTEXT.md](../../CONTEXT.md) への追加は不要（diagnostics契約の用語であり、[organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md) が正本となる）。

## Behavior scenarios

### Scenario: InputUnavailableで終わるrunが理由コード付きのterminal recordを残す

Given launcher modelが読込済みで、composerがいずれかの理由（例: bundle読取失敗）で `NotReady` を返す。
When manual organizerのstartが実行される。
Then journalに `RUN_STARTED` に続くterminal event `INPUT_NOT_READY` が追記される。
And eventのerrorは `family=INPUT_READINESS`、`code=<composerの失敗箇所を識別するclosed code>` であり、package名、座標、item列挙、digest、自由形式textを含まない。
And UI stateは `State.InputUnavailable(reason)` のままであり、readiness判定とfail-closed動作は変わらない。

### Scenario: model読込前にorganizerを開く

Given `ReadinessGate` が `IDLE` または `RECONCILING`（再起動reconciliation未完了）である。
When manual organizerのstartが実行される。
Then journalに `INPUT_NOT_READY` が `code` がreconciliation pendingを示す値で追記される。
And user-facing copyは「後でもう一度試す」ことを示す区分である。

### Scenario: capture側で例外が発生する

Given `LayoutWriterCanonicalCaptureSource.capture()` が `RuntimeException` を投げる。
When composerがcaptureを読む。
Then composerの返却は従来どおり `NotReady(InvalidCanonicalCapture(CAPTURE_UNAVAILABLE))` であり、振る舞いは変わらない。
And diagnostics loggerの単一tagにDEBUG levelで、失敗phaseとexception class名・messageのみの行が出力される。
And その行にlayout内容、package名、profile識別子、座標、item列挙は含まれない。
And journalには例外class名・messageが書かれず、理由コードのみが書かれる。

### Scenario: journal・export・logcatのnegative invariant

Given 任意の `INPUT_NOT_READY` run。
When journal、export、logcatの全出力面を検査する。
Then [organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md) §7のNever分類（package名、component名、座標、digest、自由形式text等）は一切現れない。
And capture側例外のclass名・messageは、本specが承認するdebug出力面を除いて現れない。

### Scenario: 一回性のpost-restore capture不可の再現またはbounded

Given #168/#171と同じ手順で、既存grid上へのNova restoreを1回実施し、workspaceがauthoritativeである。
When 同一process内でmanual organizerのstartを繰り返す。
Then 失敗が再現された場合、`INPUT_NOT_READY` の理由コードと（capture側失敗なら）例外class・messageがevidenceとして記録される。
And 再現しない場合、失敗時点の状態（gate state、recovery-store availability、bundle/override/evidence sourceの読取結果）を含むbounded証拠がassessment文書に記録される。

## Data and state

- 書込み先は既存organizer run journalのみ。retention（直近10 run・7日・512 KiB）、削除規則、fail-open性は既存契約のままである。`INPUT_NOT_READY` はterminal eventであるため、unresolved run保護の対象にならない。
- journal schemaVersionは1のまま。closed enumへの定数追加はschema変更を伴わない。
- export形式・手順は不変。exportはjournalの `RunEvent` 列を読むため、`INPUT_NOT_READY` は既存手順でexportされる。
- layout DB、recovery store、backup/restore経路への影響はnone。capture、plan、apply、recoveryの動作は不変である。

## Permissions, privacy, and security

- permission・network・telemetryの追加はnone。journal・exportは既存のlocal-only契約に従う。
- [organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md) §7分類表に1行の例外を承認する: **capture側例外のclass名とmessage** を、organizer diagnostics loggerの単一tag・DEBUG level・capture失敗時のみに限り出力してよい。messageは例外に含まれるtextそのままであるが、layout内容・座標・package名を意図的に含めてはならない。journalとexportには書かない。

## Accessibility and localization

- `InputUnavailable` 画面の新copyは、既存の `FocusTargetText`（focus、`liveRegion=Polite`）と同じ扱いを受ける。focus移動やretry操作は変わらない。
- 新しいuser-facing文字列はdefault（en）と `values-ja` の両方に追加し、[spec #161](../161-japanese-ui-copy-lqa/spec.md) のcopy規約に従う。

## Acceptance criteria

- [ ] **AC-1 — 理由コード付きterminal record:** `InputUnavailable` で終わるすべてのmanual runが、`INPUT_NOT_READY` phase + `ErrorFamily.INPUT_READINESS` + composer失敗箇所を識別するclosed codeを持つjournal eventを生成する。すべての `InputReadinessReason` 系がこのclosed集合へ対応付けられ、未知のcodeは `UNMAPPED` に落ちる。
- [ ] **AC-2 — capture例外の観測:** capture側 `RuntimeException` が、layout内容を含まない形（失敗phase + exception class/message）でdiagnostics logger単一tagにDEBUG levelで出力される。composerのfail-closed返却値は不変である。
- [ ] **AC-3 — 一回性episodeの解決:** post-restore capture不可episodeが、名前付き理由での再現、または失敗時点の状態証拠によるboundedのいずかで `docs/assessment/` に記録される。具体的なcapture側欠陥が確認された場合はfocused fix Issueへ分割されている。
- [ ] **AC-4 — copy区分:** `ReconciliationPending` の場合とその他の理由の場合で、user-facing copyが「後で再試行」と「バグ報告」を区別する。en/ja両方が提供される。
- [ ] **AC-5 — privacy不変:** journal・export・logcatの全出力面で§7 Never分類が保たれる。negative fixture testがこれを自動検証する。
- [ ] **AC-6 — readiness意味論の不変:** 既存のcomposer/readiness unit・contract testがすべて変更なしで通過し、Ready/NotReady判定とfail-closed動作に差分がない。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | `InputReadinessReason` 全variant→closed code対応のunit test。`ManualOrganizationRunTest` への `INPUT_NOT_READY` event検証追加（理由コード、terminal性、emit失敗時のfail-open）。`ModelValidationTest` / journal系testのclosed集合検証 |
| AC-2 | capture sourceのfailure注入test（例外→Invalid + DEBUG log行、内容のnon-containment） |
| AC-3 | エミュレータ（`nunu_qpr2_api36_1`）での再現/bounded手順と結果を記録した `docs/assessment/issue-172-<slug>.md` |
| AC-4 | UI compose test（reason区分→表示copy）。string resourceのen/ja確認 |
| AC-5 | journal/export negative fixture testの拡張（例外text・digest等のnon-containment、`INPUT_NOT_READY` 含む） |
| AC-6 | 既存organizer test群の無変更通過 + `spotlessCheck` + `assembleLawnWithQuickstepGithubDebug` |

## Open questions

- `INPUT_NOT_READY` のlogcat levelをWARN（terminal failure扱い、releaseでも出力）にするかDEBUGにするか。本specはterminal failureであることからWARNを想定するが、最終値は [organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md) §10更新時に確定する。非blocking。
- capture側例外messageのdebug出力をrelease buildでも行うか。diagnosis価値はrelease端末で最も高いため、本specは「DEBUG level・全build」を想定する。§7更新時に確定する。非blocking。

## Change history

- 2026-08-31: Issue #172のdraft specを作成。#171 investigation（[assessment](../../docs/assessment/issue-171-organizer-after-external-restore.md)）のhandoffに基づく。
