# Independent audit: PR #344 External Agent ExchangeのImport入力をclipboard/file-firstのモバイルUIへ変更する (spec 332)

> Status: accepted
> Audit date: 2026-09-17

- Auditor: Independent audit session (ZCode subagent、GLM。implementation PRを担当したsessionとは別の作業として実施。実装側の主張 (PR本文・README) は参照したが依拠していない — 記載内容は監査sessionが自ら読んだdiff/test本文/実行結果のみによる)
- PR: https://github.com/nunu1733/NunuLauncher/pull/344
- Head SHA: `9d16111c20fa030fdea5a1aa4c0b23678e55e9d4` (base `main` = `9290afc2be`)
- CI run (audit時点): https://github.com/nunu1733/NunuLauncher/actions/runs/35178991632 (CI workflow、`head_sha == 9d16111c20fa030fdea5a1aa4c0b23678e55e9d4` をactions APIで確認、conclusion **success**) / 同一head SHA上のcheck-runs APIで **15/15 check success** (`final-status` success、`high-risk-evidence` run 35178991593 success を含む)
- Criteria: `specs/332-exchange-import-input-ui/spec.md` AC-1〜AC-10、D-1〜D-7 (+ `plan.md` の検証計画・Explicitly unverified areas)。spec/planはaccepted (spec review Approved: [Issueコメント](https://github.com/nunu1733/NunuLauncher/issues/332#issuecomment-5706349675)、head `bccab1ca0d` 基準。実装review Approved: 最終コメント、head `9d16111c20` 基準 — 両コメントの存在を監査sessionがIssue comments APIで確認)

## Scope

本監査は、accepted spec 332とplanを正本として、PR #344のhead `9d16111c20fa030fdea5a1aa4c0b23678e55e9d4` の全diff (20 files, +1719/−58) を自ら読んで確認したものである。監査用checkout (`/Users/nunu/Documents/work/NunuLauncher-332`、branch `issue-332-spec-plan`、head `9d16111c20`、clean) でspec/plan/実装/test本文を実読し、automated evidenceは監査session自らの実行結果のみを用いた。

確認した変更面:

- **ClipboardImportTransport.kt (新規)**: 明示操作1回につき `getPrimaryClip()` 1回のみ。text itemのみを取得し `coerceToText` 等のURI/Intent解決をしない。typed結果は `Text` / `EmptyOrUnavailable` (null clip・空text・読取例外を原因非区別で統一) / `NotText` (clip非nullだがtext item無し) の3値。上限検査はtransportで所有しない (holder受領gateに一任し全sourceで1つの上限・案内に統一)。`OnPrimaryClipChangedListener` 等のlistener登録・resume時読取の経路は存在しない (本文実読 + grep機械確認)。
- **ExchangeFlowUi.kt (holder)**: 共通受領helper `receiveAndImport` (envelope gate → editor置換 → 即 `import()`) を新設し、clipboard (`importFromClipboard`) とfile (`onFileRead`) の両sourceを集約。file読取結果のhopを `settleDispatcher` に統一 (writeFileと同一規約)。`ExchangeStatus.Kind` に `CLIPBOARD_EMPTY` / `CLIPBOARD_NOT_TEXT` を追加。display hopを注入可能な `uiDispatcher` に変更 (本番defaultは `Dispatchers.Main` のまま、behavior不変)。
- **ExchangeFlowUi.kt (Import surface)**: clipboard読込 (primary `Button`) → file読込 (`text/plain` + `application/json`、対応型・1 MiB copyを近傍に明示) → 折りたたみfallback (D-2 (a)、`manualOpen` local state) → bounded manual editor (`maxLines = 8` + `heightIn(max = 200.dp)`、D-4確定値) + clear + 取り込む → Cancel。`exchangeStatusTextResource` を純粋なtop-level mappingとして抽出 (JVM test可能)。
- **ExchangeFlowUi.kt (parse-first outcome)**: `ExchangeScreen.ImportOutcomeScreen` に `rawText` fieldを1つ追加 (spec AC-7 retention boundaryの単一保持箇所)。`RecognizedImportInfo` → `ExchangeImportDisplayInfo` への純粋projection (2 overload) でframing/version/entry countを表示し、raw detailはdefault折りたたみ + `heightIn(max = 240.dp)` + `verticalScroll` (export disclosureと同一pattern)。
- **ExchangeImportPipeline.kt**: `ExchangeImportResult.Failure` へのadditive field `recognized: RecognizedImportInfo?` と `RecognizedImportInfo` (framing・codec受理version・authored entry count = `intent.itemIntents.size`) の追加。framing失敗でも判明範囲 (`MARKER`) を保持し、decode成功後の失敗は全3値を保持。**19種の失敗区分・envelope上限・normalizer/codec/validatorのsemanticsは不変** (差分はmetadata付与のみ)。
- **ExchangeFlowController.kt**: import pathの各失敗returnに `prepared.recognizedInfo()` を中継、`InputNotReady` へ `recognized` field追加 (post-decode環境失敗でも認識事実が生存)。generate/write/export側の分岐は未変更。
- **strings (en/ja)**: 15種ずつ同一key setを追加 (clipboard読取button・fallback見出し・clear・対応型/上限copy・`exchange_status_clipboard_empty` / `_not_text` / `exchange_status_file_read_failed`・framing/version/entry数label・raw表示toggle)。ja正本・en対訳とも実読確認。
- **正本/evidence**: spec/plan (accepted status・D-4確定・Change history)、`docs/assessment/assets-332-import-ui/` (screenshot 7枚 + README、diffに含まれることを確認)。
- **tests**: `ClipboardImportTransportTest` (新規2件)、`ExchangeFlowStateHolderTest` (+13件)、`ExchangeImportPipelineTest` (+6件)、`ExchangeImportSurfaceInstrumentationTest` (新規3件、instrumentation source set)。

`ImportNormalizer.kt` / `IntentImportParser.kt` / `IntentCodec` / validator / `SessionExportReconstructor` / `ExchangePackageComposer` / `ExchangeTransports.kt` (export側transport・`FileExchangeTransport.read` 既存bounded read)・`ManualOrganizationPreferences.kt` (hosting、idle/run内両entry)・DB・Launcher3 bridgeには変更がない (diff --statの20 fileが上記範囲に収まることをname-only + grepで機械確認。NO OUT-OF-SCOPE FILES)。依存・権限・通信・DB migrationの追加はなし (manifest/gradle/toml/schemaはdiff 0件)。export flow (生成・送信前確認・transport) のcode変更はなし。

## Criteria check

spec 332の受入条件ごとの確認結果。test名は監査sessionがtest本文を実読し、assert内容を確認したもの (名前のみの採用はしていない)。unit testの実行結果は監査session自らの実行 (下記Executed test surface) による。

- **AC-1 (clipboard 1操作・明示操作以外のaccess不在)**: PASS (unit + 構造 + device evidence)。`ClipboardImportTransportTest.aReadPerformsExactlyOneInjectedClipAccess` (1呼出=1読取をcounterでassert)、`typedReadResultsPassThroughWithoutInterpretation`。holder: `clipboardTextReceiptReplacesTheEditorAndRunsTheCommonImportPath` (受領1操作でeditor置換 + `store.load` 呼出=共通path開始をassert)、`clipboardReceiptReachesTheParseFirstOutcomeWithEphemeralRawDiscardedOnRetry` (受領1操作 → terminal state `ImportOutcomeScreen` 到達をassert)。明示操作以外のaccess不在: `OnPrimaryClipChangedListener` はorganizer配下grep 0件、transport本文は `getPrimaryClip` 1回のみ (監査sessionが実読・grep実施)。device evidence `04` (1押下 → parse-first表示)。
- **AC-2 (file import・1操作・対応型明示・URI一時grant)**: PASS (unit + strings + grep)。`fileTextReceiptRunsTheSameCommonImportPathInOneOperation` / `fileReceiptReachesTheParseFirstOutcomeInOneOperation` (`onFileRead` → 受領helper → 共通path、追加の「取り込む」押下なしをassert)。UI copy: `exchange_import_file_types` をja/en両方で実読 (「テキスト (.txt) またはJSON (.json)・最大1 MiB」)。`takePersistableUriPermission` はorganizer配下grep 0件、`FileExchangeTransport.read` 自体はdiff外のため既存bounded readのregressionは無修正suiteのgreen (監査実行) に一任 — plan記載の検証方針どおり。
- **AC-3 (bounded manual paste + clear + 置換)**: PASS (unit + instrumentation + device evidence)。置換: `clipboardTextReceiptReplacesTheEditorAndRunsTheCommonImportPath`。clear: `ExchangeImportField` のclear button (`holder.onImportTextChange("")`) 実読 + instrumentation `hugeManualPasteStaysBoundedWithInternalScrollAndClearsInOneAction` (clear・bounded断言 — 実行はFindings 2参照)。bounded実装 (`maxLines=8` + `heightIn(max=200.dp)`) は実読。device evidence `01` (折りたたみ初期状態) / `02`。
- **AC-4 (長文で画面が伸びない)**: PASS (instrumentation + device evidence)。instrumentationがeditor高さ < 400px @Density 1f、raw detail ≤ 260pxを機械断言 (実行はFindings 2参照)。device evidence `02` / `05` / `07` で100%・200% font双方のbounded・内部scroll・主要CTA画面内維持を確認 (README記載内容とPNG存在を確認)。
- **AC-5 (全sourceが共通path・UI parse不在)**: PASS (構造 + unit)。grep機械確認: `receiveAndImport` 呼出はclipboard (Ui.kt:420) とfile (Ui.kt:403) の2箇所のみ、manual pasteは既存 `holder.import` (Ui.kt:1016)。3sourceとも単一の `import()` (Ui.kt:475) → `controller.importReply` 唯一呼出 (Ui.kt:477) → `ExchangeImportPipeline.prepare` 唯一呼出 (Controller.kt:140) に収束する。UI層の表示変換は純粋関数 `exchangeImportDisplayInfo` 2 overloadのみ (text走査・trim・文字置換なし、本文実読)。transportはtext取得のみ (ClipboardImportTransport本文に前処理なし)。`displayInfoProjectionMapsOnlyTheRecognizedMetadata` がprojectionの純粋性をunit test。
- **AC-6 (typed失敗の区別と案内)**: PASS (unit)。`clipboardEmptyReadKeepsTheScreenAndSetsTheTypedStatus` (CLIPBOARD_EMPTY・zero-write assert)、`clipboardNonTextReadKeepsTheScreenAndSetsTheTypedStatus` (CLIPBOARD_NOT_TEXT)、`oversizedClipboardTextIsNotAdoptedAndReportsInputOversize` (INPUT_OVERSIZE・state不採用)、`fileReadFailureSetsTheTypedFileStatusAndKeepsTheEditor` (FILE_READ_FAILED)、`oversizedFileTextIsRejectedByTheSharedEnvelopeGate` (file経路のoversizeも同一gate)。mapping: `fileReadFailureResolvesToTheDedicatedImportGuidance` (`exchangeStatusTextResource` が `FILE_READ_FAILED` → 専用 `exchange_status_file_read_failed`、export用copyと別resourceであることをassert)、locale: `fileReadFailureGuidanceExistsInBothLocales` (values/values-ja両方に存在)。stringsの実在は監査sessionもja/en diffで実読。
- **AC-7 (raw非記録・ephemeral retention boundary)**: PASS (unit + 構造 + grep)。保持箇所: `ExchangeScreen.ImportOutcomeScreen.rawText` の1 fieldのみ — 宣言 (Ui.kt:101)、唯一の非空構築 (Ui.kt:519、`replyText` を移し替えるのみで二重保持なし)、表示 (Ui.kt:668/1052以降) をgrep機械確認。retention: `clipboardReceiptReachesTheParseFirstOutcomeWithEphemeralRawDiscardedOnRetry` が「outcome surface上でのみ保持 → `openImport` で空editor → `close` でClosed」をassert、instrumentationのretry後断言も同様。diagnostics/logcat/file書込み: 本diffの追加行をgrep (Log./println/System.out/FileWriter/FileOutputStream/openFileOutput/journal等) しproduction code一致0件 (一致はcomment文とtestのno-op `DiagnosticsPort` stubのみ — 監査session実施)。
- **AC-8 (accessibility evidence)**: 部分PASS + **残りは後続evidence debt (Findings 5)**。200% font: device evidence `06` / `07` + instrumentation `primaryActionsStayDisplayedAndEditorStaysBoundedAtTwoHundredPercentFont` (fontScale 2fで主要CTA表示・editor bounded断言 — 実行はFindings 2参照)。D-4確定根拠はREADMEに記録済み (実読)。**TalkBack実読み上げの通し確認・Switch Access/keyboard実操作のevidenceは本headに存在しない** (README「記録範囲と残り」が自ら明記) — 取得済みと扱わない。
- **AC-9 (representative ChatGPT/Gemini実copy device evidence)**: **OPEN (documented follow-up evidence debt、non-blocking)**。README自ら「representativeなChatGPT/Gemini mobile appからの実copyは含まない」と明記し、`04` / `05` は自app export packageのclipboard読取evidenceであることを正確に記録している。spec Change history・plan Explicitly unverified areas・PR bodyが同一の分離 (#205 AC-10前例どおりの後続evidence pass) を記録済み。本監査はこの残置を確認し、取得済みとは扱わない。
- **AC-10 (parse-first表示・entry count境界fixture・regression)**: PASS (unit + instrumentation存在)。境界fixture `authoredEntryCountCountsBareEntriesAndExcludesOmission`: export scopeに3 ref、document `items` がsemantic entry 1 + bare `{"ref":...}` 1、第3refはomission、というfixtureで `prepared.intent.itemIntents.size == 2` かつ `recognizedInfo().authoredEntryCount == 2` を直接assert (test本文実読 + 監査実行でgreen)。post-validation値 (`completed.authoredItemCount` 等) への誤置換は、countがdecode直後のdocument構造から確定する構造 (`recognizedInfo() = intent.itemIntents.size`、validation前に成立) と `postDecodeFailureCarriesTheAcceptedVersionAndEntryCount` の別assertで検出可能。parse-first表示: instrumentation `parseFirstOutcomeShowsRecognitionAndKeepsRawCollapsedByDefault` (framing/version/entries表示・raw default閉・retryで破棄 — 実行はFindings 2参照) + device evidence `04` / `05`。19種typed失敗のregression: `exchangeFailureText` はdiff未変更 (実読)、無修正の `ImportNormalizerTest` 22件・`ExchangeImportPipelineTest` 既存17件がgreen (監査実行)。export flow regression: `ExchangePackageComposerTest` 6 / `ExchangeDisclosureStateTest` 7 / `ExchangeFlowControllerTest` 11 等が無修正でgreen (監査実行)。

### D-decisionの個別確認 (監査指示項目)

- **D-1/D-2 (主導線・fallback配置)**: 実装は起草推奨 (a) — clipboard primary → file outlined → `exchange-import-fallback-toggle` の折りたたみsection。editorは `manualOpen` local stateで要求されるまでcomposeされない (instrumentation `assertDoesNotExist` で固定)。実読確認。
- **D-3 (file対応型)**: `filePicker.launch(arrayOf("text/plain", "application/json"))` + 対応型/上限copyの近傍明示。実読確認。
- **D-4 (実寸)**: `IMPORT_EDITOR_MAX_LINES = 8` / `IMPORT_EDITOR_MAX_HEIGHT = 200.dp`。確定根拠はREADME (`02` / `07` + instrumentation)。実装値とspec確定値の一致を確認。
- **D-5 (parse-first表示の内容)**: `RecognizedImportInfo` はframing・codec受理version・`intent.itemIntents.size` のみを保持し、label/ref/rationale/confidenceを含まない (data class実読)。表示はseam由来の純粋projectionのみ。
- **D-6 (additive metadata)**: `Failure` への `recognized` field追加と `InputNotReady.recognized` のみで、失敗区分 (19種) ・`ExchangeImportFailure` sealed hierarchyは不変 (実読)。framing失敗で `MARKER` が判明範囲として生き残ることを `markerFramingFailureKeepsTheRecognizedMarkerFraming` が、decode前失敗でmetadataが空であることを `oversizeFailsBeforeRecognitionAndCarriesNoMetadata` / `normalizationFailureCarriesNoRecognizedMetadata` が、decode失敗でframingのみを `decodeFailureCarriesTheRecognizedFramingButNoDecodeFacts` が実assert。
- **D-7 (clipboard読取規約)**: 1押下=1回 `getPrimaryClip`、listener/resume読取なし、text itemのみ (`getItemAt(0).text`、null→NotText)、null/emptyは原因非区別で統一、上限はtransportで所有しない (holder受領gate一任)。本文実読 + grepで確認。`oversizedClipboardTextIsNotAdoptedAndReportsInputOversize` が「transport取得成功 → 受領gateでINPUT_OVERSIZE」の順序を実assert。

## Executed test surface

監査session自らが実行 (head `9d16111c20fa030fdea5a1aa4c0b23678e55e9d4`、worktree clean、source未変更、JDK 21環境):

- `git rev-parse HEAD` → `9d16111c20fa030fdea5a1aa4c0b23678e55e9d4`、`git status` → clean
- `gh repo view nunu1733/NunuLauncher --json nameWithOwner,defaultBranchRef` → `nunu1733/NunuLauncher` / `main` を確認
- `./gradlew spotlessCheck` → **BUILD SUCCESSFUL (exit 0)**。本checkoutは同一headで実装build済みのためup-to-date判定 (入力未変性による再検証)。clean環境での同一commandの実行は同一headのCI `check-style` job (`./gradlew spotlessCheck`) がsuccessしていることでcovering。
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` → **BUILD SUCCESSFUL in 36s (exit 0、所要37秒)**。test taskは実際に実行され、結果XML 134件が監査実行開始後に生成されたことを時刻で確認。XML集計 (監査session自ら): **134 class / 1436 tests / 0 failures / 0 errors / 0 skipped**。exchange関連12 classの内訳: `ClipboardImportTransportTest` 2、`ExchangeFlowControllerTest` 11、`ExchangeImportPipelineTest` 23、`ExchangeFlowStateHolderTest` 19、`ImportNormalizerTest` 22、`IntentImportParserTest` 16、`ExchangePackageComposerTest` 6、`SessionExportReconstructorTest` 5、`ExchangeGenerationGateTest` 4、`ExchangeDisclosureStateTest` 7、`ExchangeTargetScopeCouplingTest` 14、`Issue336ExchangeProjectionTest` 7。
- `assembleLawnWithQuickstepGithubDebug` は監査sessionでは未実行 (時間節約)。同一headのCI `build-debug-apk` successがcovering。
- `ExchangeImportSurfaceInstrumentationTest` は監査sessionでは再実行していない (emulator起動なし)。CIのいずれのlaneでも実行されないため自動検証もない (下記Findings 2)。

## Findings

1. **CI状態**: run 35178991632の `head_sha` が監査対象head `9d16111c20fa030fdea5a1aa4c0b23678e55e9d4` と一致することをactions APIで確認し、conclusion success。さらに同一head SHAのcheck-runs APIで15/15 check (`changes` / `check-style` / `build-debug-apk` / `organizer-unit-tests` / `validate-repo-contract` / instrumentation 8 lane / `final-status` / `high-risk-evidence` run 35178991593) が **success** であることを確認。`final-status` はsuccess。
2. **`ExchangeImportSurfaceInstrumentationTest` がどのCI laneでも実行されない** (正直記録): `ci.yml` のinstrumentation 8 laneはすべてclass明示指定であり、本classは含まれない (ci.yml grep機械確認)。AC-3/AC-4/AC-10の機械断言 (bounded px・raw default折りたたみ・200% font) は、実装sessionの手元emulator実行 (PR本文に3/3 PASSと記載 — 本監査は再実行しておらず検証外) とdevice screenshotに依存している。CI laneへの組込みを推奨する (非ブロッキング。test自体はhead上に存在し、JVM unit test群は監査実行で全green)。
3. **high-risk gate該当性**: PR #344のlabelは空 (API確認)。diffにDB・manifest・gradle/dependency・schema変更がなく、layout DB書込み経路の変更もないため `risk: layout-data` / `risk: migration` 非該当 (plan見立てと一致)。本監査はAGENTS.mdの独立エビデンス要件に準じた自主実施である。
4. **非ブロッキングの観察事項**:
   - `FILE_READ_FAILED` statusの文言がexport側copy (`exchange_transport_file_failed`) から専用import案内 (`exchange_status_file_read_failed`) へ分離された (Phase2 review R1 fix)。意図的な変更であり、mapping test + locale testで固定済み。export側transport失敗の文言使用は不変。
   - holder受領testは `Dispatchers.IO` + 5秒polling (`awaitStoreLoad` / `awaitScreenOutcome`) で非同期tailを待つ。同一headのCI `organizer-unit-tests` successで実機CI環境での動作は確認済み。
   - display hopの `uiDispatcher` 注入化は本番default `Dispatchers.Main` でbehavior不変 (差分実読)。
   - clipboard oversizeはtransportが全文取得後にholder受領gateで `INPUT_OVERSIZE` とする設計で、上限の単一所有 (#205) を維持する (spec D-7どおり)。
5. **AC-8残り (TalkBack通し確認・Switch Access/keyboard実操作evidence) とAC-9 (representativeなChatGPT/Gemini mobile appからの実copy device evidence) は後続evidence debt (non-blocking、[Issue #205 AC-10の前例](https://github.com/nunu1733/NunuLauncher/issues/205) どおり)**。README・plan Explicitly unverified areas・PR bodyの3箇所で明示分離されており、record間の不整合はない。merge後に追跡Issueを起票してdispositionすること。
6. **本記録pushによるhead移動**: 本audit記録はPR branch上のdocs-only commitとして追加されるため、push後のPR headは `9d16111c20` から移動する。deltaは本file追加のみを想定し、merge者は最終head上でCI (`final-status` 含む) が緑であることを確認すること。本監査のverdictは `9d16111c20` に対するものであり、docs-only deltaを許容する。code変更時は本SHAに対する再監査を要する。
7. **独立verdict**: **Approve** (head `9d16111c20fa030fdea5a1aa4c0b23678e55e9d4` に対する)。scope収縮 (20 file、DB/manifest/依存/hosting/export flow無変更)、UI側parse不在 (全sourceが単一pipeline入口に収束)、zero-write契約 (diagnostics/logcat/file書込み0件)、19種失敗区分の不変、AC-7 retention boundary (raw保持1箇所・遷移で破棄)、AC-10境界fixtureは、すべて構造確認と監査session自らのunit test実行 (1436 tests / 0 failures) で確認した。AC-8残りとAC-9はdocumented follow-up evidence debtとして残置する (merge blockとはしない — spec/plan受入どおり)。上記2 (instrumentationのCI未整備) の解消を推奨事項とし、5の追跡Issue起票をmerge後の条件とする。

## 未確認範囲 (明示)

- `ExchangeImportSurfaceInstrumentationTest` の本監査での再実行 (CI lane未整備のため自動検証も存在しない — Findings 2)。実装sessionの手元実行 (3/3 PASS) の再現はしていない。
- D-4 evidence取得操作の再現 (README記述とPNG存在の確認にとどまる。screencapの内容物の再検証はしていない)。
- representativeなChatGPT/Gemini app実copy・TalkBack実読み上げ・Switch Access/keyboard実操作のevidence (後続evidence debt)。
- OEM実機でのclipboard読取挙動・Android 12+ access toast表示の実測 (plan Explicitly unverified areasのとおり。emulator API 36ではtoast非表示を確認済みと記録)。
