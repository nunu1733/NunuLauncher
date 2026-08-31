---
issue: "#172"
status: implemented
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

`InputUnavailable` で終わるすべてのrunが、privacy-safeな理由コードを持つterminal diagnostics recordをjournalに残す。capture側の失敗は、例外class名のみ（raw message・stack traceは出さない）をdebug buildのlogcatに出し、journal側は `INPUT_NOT_READY` の理由コードが担う組み合わせで観測できる。supportとユーザーは「後でもう一度試せる」状態（model未読込等）と「バグ報告に値する」状態（source/config系の理由）を区別できる。#171の一回性episodeは、名前付きの理由で再現されるか、失敗時点の状態証拠でboundedされる。

Issue #172本文の受入条件にある「exception class/message/code」のうち、**messageは本specにより「出力しない」に強化する**。理由: 任意の `Throwable.message` がlayout内容・package名・component名・座標等を含まないことは一般に保証できず、§7のNever分類と両立しないためである。class名単独（例: `SQLiteBlobTooBigException`）とerror codeで#171の診断に必要な識別は十分であり、この強化はissueの意図（診断可能にする）を満たす。

## Scope

- `InputReadinessReason` の理由コードをjournalへ出力するterminal phase（`INPUT_NOT_READY`）の導入。journal理由コードはcomposerの既存diagnostic codeに1:1対応するclosed enum `InputCompositionCode`（SCREAMING_SNAKE定数名）を正式値とする。
- capture側例外の観測: `DiagnosticsLogger` への専用APIを追加し、debug buildのlogcatで出力する。APIは**String型を一切受け取らない**: 例外は `Class<out Throwable>` として受け、logger内部で `simpleName` 化する。raw message・stack traceは出力しない。class simple名（例: `SQLiteBlobTooBigException`）が正規化された失敗identityである（platformはtypedな数値error code accessorを提供しないため、数値codeは運ばない）。
- user-facing copyの区分: `ReconciliationPending` は「後でもう一度試してください」系、それ以外の理由は「バグ報告」系の文言を表示する。
- #171の一回性post-restore capture不可episodeの再現またはbounded。具体的原因が特定された場合はfocused fix Issueへ分割する。
- 上記に伴う [organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md)（正本）の§4.1/§5/§7/§10/§13更新と、serialized enum追加のversioning規定（§3/§8）の明確化。

## Non-goals

- Ready/NotReadyの意味論自体の変更。何がReadyかは [spec #83](../83-production-organization-input-sources/spec.md) が正本であり、本specはreadiness判定、fail-closed動作、composer制御フローを一切変更しない。
- composerが返す `InputReadinessReason` 型とその割当ての変更。
- 既存journal/projection契約を越える公開diagnostics schemaの追加。`RunEvent` のfield集合は不変であり、schemaVersionは1のままである（versioningの影響は「Data and state」に定義する）。
- capture失敗の根本原因の修正。具体的なcapture側欠陥が見つかった場合はfocused fix Issueとして分割する。
- onboarding/incremental run flowの変更。本specのjournal出力はmanual full run（`ManualOrganizationRun`）を対象とする。composerが共有であるため、将来のrun flowは同じ射影を再利用できる。

## Domain language

- **INPUT_NOT_READY**: 入力合成が `NotReady` で終了したrunを閉じるterminal phase code。journal理由コードを1つ伴う。
- **INPUT_READINESS**: `INPUT_NOT_READY` が使う `ErrorFamily`。codeの正式値は `InputCompositionCode` の定数名（+ `UNMAPPED`）である。
- **InputCompositionCode**: composerの失敗箇所に1:1対応するclosed enum。`CompositionDiagnostic.code` の型をこのenumに変更し、journal出力と同一の集合を単一の正本として参照する。

正式なclosed code集合（16値 + `UNMAPPED`。契約§5へ記載する）:

`RECONCILIATION_PENDING` / `RECONCILIATION_FAILED` / `CAPTURE_INVALID` / `CAPTURE_UNKNOWN_LOCK` / `CAPTURE_UNREPRESENTABLE` / `BUNDLE_MISSING` / `BUNDLE_CORRUPT` / `BUNDLE_UNSUPPORTED` / `BUNDLE_INVALID` / `OVERRIDE_UNREADABLE` / `OVERRIDE_UNSUPPORTED_SCHEMA` / `OVERRIDE_CATEGORY_INVALID` / `EVIDENCE_UNREADABLE` / `SIGNAL_CONTRADICTION` / `TARGET_PARTITION` / `DYNAMIC_CUT_UNSTABLE`

承認時に [CONTEXT.md](../../CONTEXT.md) への追加は不要（diagnostics契約の用語であり、[organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md) が正本となる）。

## Behavior scenarios

### Scenario: InputUnavailableで終わるrunが理由コード付きのterminal recordを残す

Given launcher modelが読込済みで、composerがいずれかの理由（例: bundle読取失敗）で `NotReady` を返す。
When manual organizerのstartが実行される。
Then journalに `RUN_STARTED` に続くterminal event `INPUT_NOT_READY` が追記される。
And eventのerrorは `family=INPUT_READINESS`、`code=<InputCompositionCode定数名>` であり、package名、座標、item列挙、digest、自由形式textを含まない。
And UI stateは `State.InputUnavailable(reason)` のままであり、readiness判定とfail-closed動作は変わらない。

### Scenario: model読込前にorganizerを開く

Given `ReadinessGate` が `IDLE` または `RECONCILING`（再起動reconciliation未完了）である。
When manual organizerのstartが実行される。
Then journalに `INPUT_NOT_READY` が `code=RECONCILIATION_PENDING` で追記される。
And user-facing copyは「後でもう一度試す」ことを示す区分である。

### Scenario: capture側で例外が発生する

Given `LayoutWriterCanonicalCaptureSource.capture()` が `RuntimeException` を投げる。
When composerがcaptureを読む。
Then composerの返却は従来どおり `NotReady(InvalidCanonicalCapture(CAPTURE_UNAVAILABLE))` であり、振る舞いは変わらない。
And debug buildでは、diagnostics loggerの単一tagにDEBUG levelで、失敗contextとexception class名（loggerが受け取った `Class` から `simpleName` 化した値。例: `exceptionClass=SQLiteBlobTooBigException`）のみの行が出力される。
And raw `Throwable.message`、stack trace、layout内容、package名、profile識別子、座標、item列挙は出力されない。取得できない情報は省略され、`<redacted>` 等のtextに置換して出力することもしない。
And この観測APIは文字列型パラメータを持たないため、呼出側がmessageやlayout由来の文字列を渡す経路が型として存在しない。
And journalには例外class名・error codeは書かれず、理由コード `CAPTURE_INVALID` のみが書かれる。
And release buildではこの行は出力されない。releaseではjournal由来の `INPUT_NOT_READY`（WARN、理由コード付き）のみが観測される。

### Scenario: journal・export・logcatのnegative invariant

Given 任意の `INPUT_NOT_READY` run。
When journal、export、logcatの全出力面を検査する。
Then [organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md) §7のNever分類（package名、component名、座標、digest、自由形式text、exception message等）は一切現れない。
And capture側例外のclass名は、本specが承認するdebug-build限定のlogcat行を除いて現れない。

### Scenario: 一回性のpost-restore capture不可の再現またはbounded

Given #168/#171と同じ手順で、既存grid上へのNova restoreを1回実施し、workspaceがauthoritativeである。
When 同一process内でmanual organizerのstartを繰り返す。
Then 失敗が再現された場合、`INPUT_NOT_READY` の理由コードと（capture側失敗なら）exception class名・error codeがevidenceとして記録される。
And 再現しない場合、失敗時点の状態（gate state、recovery-store availability、bundle/override/evidence sourceの読取結果）を含むbounded証拠がassessment文書に記録される。

### Scenario: 新しいjournalを旧buildで開く（downgrade）

Given 新buildが `INPUT_NOT_READY` / `INPUT_READINESS` を含むjournalを書いた後、端末が旧buildへ戻されている。
When 旧buildがjournalを起動時にdecodeする。
Then 未知のenum値によりdecodeが失敗し、既存のcorruption-isolation規則（§8）どおりjournal全体が初期化される。journalSequenceを保持するsequence fileはresetされない。
And 失われるのはdiagnostics journalのみであり、layout DB、recovery store、設定には影響しない。journalはfail-openであるためrun動作も変わらない。
And upgrade方向（旧journal → 新build）は既存eventのみで構成されるため、decodeに成功する。

## Data and state

- 書込み先は既存organizer run journalのみ。retention（直近10 run・7日・512 KiB）、削除規則、fail-open性は既存契約のままである。`INPUT_NOT_READY` はterminal eventであるため、unresolved run保護の対象にならない。
- **schemaVersionは1のまま**。ただし次のversioning性質を契約（§3/§8）に明記する:
  - `ErrorEntry.code` のString値への新定数追加は従来どおりschema変更なしで可である（既存契約の規定対象）。
  - serialized enum値（`PhaseCode` / `ErrorFamily`）の追加は、新buildが旧journalを読む（upgrade）方向では可である。旧buildが新eventを含むjournalを読む（downgrade）方向では、現行のstrict decode（`ignoreUnknownKeys=false`、schemaVersion 1のみ受理、decode失敗時にjournal全体をresetするcorruption isolation）によりjournalが初期化される。これは受入可能な挙動として規定し、downgrade時に失うのはdiagnostics journalのみであることを明記する。
- export形式・手順は不変。exportはjournalの `RunEvent` 列を自buildのserializerで読むため、新buildのexportには `INPUT_NOT_READY` が含まれる。
- layout DB、recovery store、backup/restore経路への影響はnone。capture、plan、apply、recoveryの動作は不変である。

## Permissions, privacy, and security

- permission・network・telemetryの追加はnone。journal・exportは既存のlocal-only契約に従う。
- [organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md) §7分類表に1行の限定例外を承認する: **capture側例外のexception class名** を、`DiagnosticsLogger` の専用API経由で、DEBUG level・debug build・capture失敗時のみlogcatに出力してよい。APIは `Class<out Throwable>` のみを受け取り、文字列型パラメータを持たない（platformがtyped accessorを提供しないため、class名単独が正規化identityである）。issue本文の「class/code」要求は、debug logcat行のclass名とjournal `INPUT_NOT_READY` recordの理由コード（例: `CAPTURE_INVALID`）の組み合わせで満たされる。**raw `Throwable.message` とstack traceは引き続きNeverであり、journal・export・logcatのいずれにも出力しない。**

## Accessibility and localization

- `InputUnavailable` 画面の新copyは、既存の `FocusTargetText`（focus、`liveRegion=Polite`）と同じ扱いを受ける。focus移動やretry操作は変わらない。
- 新しいuser-facing文字列はdefault（en）と `values-ja` の両方に追加し、[spec #161](../161-japanese-ui-copy-lqa/spec.md) のcopy規約に従う。

## Acceptance criteria

- [x] **AC-1 — 理由コード付きterminal record:** `InputUnavailable` で終わるすべてのmanual runが、`INPUT_NOT_READY` phase + `ErrorFamily.INPUT_READINESS` + `InputCompositionCode` 定数名を持つjournal eventを生成する。`CompositionDiagnostic.code` は `InputCompositionCode` 型となり、journal側は `validCodesForFamily(INPUT_READINESS)` で検証し、未知codeは `UNMAPPED` に落ちる。全16値の対応（既存kebab-codeとの対応表を含む）がspec/契約に記載される。
- [x] **AC-2 — capture例外の観測（message出力なし）:** capture側 `RuntimeException` が、debug buildのlogcatに `exceptionClass`（loggerが受け取った `Class<out Throwable>` から `simpleName` 化）のみで出力される。観測APIはString型パラメータを持たず、raw message・stack traceは出力されない。composerのfail-closed返却値は不変である。
- [x] **AC-3 — 一回性episodeの解決:** post-restore capture不可episodeが、名前付き理由での再現、または失敗時点の状態証拠によるboundedのいずかで `docs/assessment/` に記録される。具体的なcapture側欠陥が確認された場合はfocused fix Issueへ分割されている。
- [x] **AC-4 — copy区分:** `RECONCILIATION_PENDING` の場合とその他の理由の場合で、user-facing copyが「後で再試行」と「バグ報告」を区別する。en/ja両方が提供される。
- [x] **AC-5 — privacy不変:** journal・export・logcatの全出力面で§7 Never分類（exception message・stack traceを含む）が保たれる。negative fixture testがこれを自動検証する。
- [x] **AC-6 — readiness意味論の不変:** 既存のcomposer/readiness unit・contract testがすべて変更なしで通過し、Ready/NotReady判定とfail-closed動作に差分がない。
- [x] **AC-7 — journal versioning:** upgrade（旧journal→新build）で既存journalがdecode可能であること、および新event含むjournalが未知enumでdecode失敗した場合に既存のcorruption-isolation（journal全体reset、sequence保持、他store無影響）へ従うことをfixture testで検証する。契約§3/§8にversioning規定が記載される。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | `InputCompositionCode` 全16値と既存kebab-code・`InputReadinessReason` 系の対応表unit test。`ManualOrganizationRunTest` への `INPUT_NOT_READY` event検証追加（理由コード、terminal性、emit失敗時のfail-open）。`ModelValidationTest` / journal系testのclosed集合検証 |
| AC-2 | capture sourceのfailure注入test（例外→`Invalid` + `logCaptureFailure` 呼出し、class名のみのassert、messageのnon-containment、release buildで出力されないassert） |
| AC-3 | エミュレータ（`nunu_qpr2_api36_1`）での再現/bounded手順と結果を記録した `docs/assessment/issue-172-input-unavailable-diagnostics.md` |
| AC-4 | UI compose test（reason区分→表示copy）。string resourceのen/ja確認 |
| AC-5 | journal/export negative fixture testの拡張（例外message・digest等のnon-containment、`INPUT_NOT_READY` 含む） |
| AC-6 | 既存organizer test群の無変更通過 + `spotlessCheck` + `assembleLawnWithQuickstepGithubDebug` |
| AC-7 | fixture test: 未知enum値を含むevent行でjournal初期化・sequence保持を検証（既存corruption系testの拡張）。upgrade方向は既存fixtureのdecode成功で検証 |

## Open questions

なし。前回reviewで挙がった2点は次の判断で確定した（実装開始の前提）:

- `INPUT_NOT_READY` のlogcat level: **WARN**（terminal failure扱い。releaseでも出力される）。
- capture例外詳細行: **debug build限定・DEBUG level**。releaseではjournal由来のWARN `INPUT_NOT_READY` のみ。diagnosis価値はdebug build（開発・emulator検証）で発揮され、releaseのprivacy面は§10の「terminal failure系のみ」規約に完全に従う。

## Change history

- 2026-08-31: Issue #172のdraft specを作成。#171 investigation（[assessment](../../docs/assessment/issue-171-organizer-after-external-restore.md)）のhandoffに基づく。
- 2026-08-31: Review rev 2。Blocker指摘に対応: (1) capture例外はraw messageを出さずclass名+正規化error codeに限定（debug build限定）、(2) serialized enum追加のversioning/downgrade挙動（journal reset）をAC-7として明記、(3) `InputCompositionCode` の16値を正式closed集合として確定、(4) capture観測のlogger seamを `DiagnosticsLogger` への専用typed API追加として確定し、open questionsを解消。
- 2026-08-31: Review rev 3。残り2点に対応: Issue #172本文のAC文言を「exception class/code、raw message不使用」へ更新（Blocker。codeはjournal側の理由コードを指す）。`logCaptureFailure` を `Class<out Throwable>` + `Int?` のみを受け取るAPIとして確定し、文字列型パラメータを排除してprivacy保証をcaller convention依存から型境界へ移した（Major）。
- 2026-08-31: 再レビューで指摘なし。statusを `accepted` へ更新し、plan.mdに従って実装を開始する。
- 2026-08-31: rev 4。`logCaptureFailure` から数値error codeパラメータを削除。API 36.1のplatform `SQLiteException` はtypedなerror code accessorを持たないため、class simple名単独を正規化identityとする（`javap` で確認）。String型パラメータなしの型境界は不変である。- 2026-08-31: AC-3 assessment（docs/assessment/issue-172-input-unavailable-diagnostics.md）により、#171のepisodeは `INPUT_NOT_READY / CAPTURE_INVALID` で再現し、root cause（5x5 gridのQSB row予約とNova import itemの重複 → `RowManifestCodec` のrequire）を特定。interop修正は [#185](https://github.com/nunu1733/NunuLauncher/issues/185) へ分割し、本specの受入条件は完了として `implemented` へ更新。
